package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bond.common.dto.PageResult;
import com.bond.common.email.ConfigCipher;
import com.bond.common.email.EmailDeliveryRecorder;
import com.bond.common.email.EmailGateway;
import com.bond.common.entity.EmailDeliveryLog;
import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.RateLimitedException;
import com.bond.transfer.dto.EmailDeliveryDTO;
import com.bond.transfer.dto.EmailDeliveryStatsDTO;
import com.bond.transfer.mapper.EmailDeliveryLogMapper;
import com.bond.transfer.mapper.UserMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 管理端「邮件投递日志」查询与统计（task-80）。
 *
 * <p>只读取 {@code email_delivery_log}（元数据表），不触碰正文；分页按 created_at 倒序，
 * 与仓库现有 {@link PageResult} 风格一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailDeliveryAdminService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_STATS_DAYS = 90;

    /** 同一投递记录两次手动重发的最小间隔（task-134）。 */
    static final long RESEND_MIN_INTERVAL_MILLIS = 10_000L;
    /** lastError 最大长度（task-134：≤200 字符）。 */
    static final int LAST_ERROR_MAX = 200;
    /** 明显是服务商 key 的片段。 */
    private static final Pattern SECRET_KEY_PATTERN = Pattern.compile("re_[A-Za-z0-9_\\-]{6,}");
    /** 形如 api_key=xxx / password: xxx / bearer xxx 的敏感键值。 */
    private static final Pattern SECRET_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|authorization|bearer|access[_-]?token|refresh[_-]?token|password)(\\s*[=:]\\s*)\\S+");

    private static final String AUDIT_ACTION_RESEND = "mail.delivery.resend";
    /** 解密后装载收件人的临时键（调用 sendTemplate 前移除）。 */
    private static final String RESEND_TO_KEY = "__resend_to";

    private final EmailDeliveryLogMapper emailDeliveryLogMapper;
    private final ObjectMapper objectMapper;
    /** 复用线上发信网关（task-134：重发走原模板）。 */
    private final EmailGateway emailGateway;
    /** 管理操作审计（task-134）。 */
    private final AuditService auditService;
    private final UserMapper userMapper;

    /** 单实例内存限流（10 秒/条），不引入 Redis。 */
    private final ConcurrentHashMap<Long, Long> lastResendAt = new ConcurrentHashMap<>();
    /** 重发载荷解密器（懒加载，与网关侧同一密钥来源）。 */
    private volatile ConfigCipher resendCipher;

    /** 分页查询，可按 status / purpose 筛选。 */
    public PageResult<EmailDeliveryDTO> page(int page, int size, String status, String purpose) {
        int pageNo = Math.max(1, page);
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        long offset = (long) (pageNo - 1) * pageSize;

        // total 必须与 items 用同一过滤条件，且不能受分页参数影响：
        // 显式 count + 带 LIMIT/OFFSET 的列表查询（本模块未注册 MyBatis-Plus 分页插件，
        // selectPage 的 total 会恒为 0——task-80 生产缺陷即此原因）。
        Long counted = emailDeliveryLogMapper.selectCount(filterWrapper(status, purpose));
        long total = counted == null ? 0L : counted;

        LambdaQueryWrapper<EmailDeliveryLog> listWrapper = filterWrapper(status, purpose)
                .orderByDesc(EmailDeliveryLog::getCreatedAt, EmailDeliveryLog::getId)
                .last("LIMIT " + pageSize + " OFFSET " + offset);
        List<EmailDeliveryLog> rows = emailDeliveryLogMapper.selectList(listWrapper);

        List<EmailDeliveryDTO> items = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows != null) {
            for (EmailDeliveryLog log : rows) {
                items.add(toDto(log));
            }
        }
        return PageResult.of(items, total, pageNo, pageSize);
    }

    /** 统一的过滤条件（count 与 list 共用，保证 total 与 items 一致）。 */
    private static LambdaQueryWrapper<EmailDeliveryLog> filterWrapper(String status, String purpose) {
        return Wrappers.<EmailDeliveryLog>lambdaQuery()
                .eq(status != null && !status.isBlank(), EmailDeliveryLog::getStatus,
                        status == null ? null : status.trim().toLowerCase())
                .eq(purpose != null && !purpose.isBlank(), EmailDeliveryLog::getPurpose,
                        purpose == null ? null : purpose.trim());
    }

    /** 最近 N 天统计：总数/成功/失败/失败率 + 按用途分组。 */
    public EmailDeliveryStatsDTO stats(int days) {
        int window = days <= 0 ? 7 : Math.min(days, MAX_STATS_DAYS);
        Map<String, Object> totals = emailDeliveryLogMapper.statsTotals(window);
        long total = number(totals, "total");
        long sent = number(totals, "sent");
        long failed = number(totals, "failed");
        // 失败率：百分比（0–100，2 位小数）；无数据时为 0.0，字段始终返回。
        double failureRate = total == 0 ? 0.0 : Math.round(failed * 10_000.0 / total) / 100.0;

        List<EmailDeliveryStatsDTO.PurposeStat> byPurpose = new ArrayList<>();
        List<Map<String, Object>> rows = emailDeliveryLogMapper.statsByPurpose(window);
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                byPurpose.add(new EmailDeliveryStatsDTO.PurposeStat(
                        row.get("purpose") == null ? "(none)" : String.valueOf(row.get("purpose")),
                        number(row, "total"),
                        number(row, "failed")));
            }
        }
        return new EmailDeliveryStatsDTO(window, total, sent, failed, failureRate, byPurpose);
    }

    /**
     * 手动重发一条投递记录（task-134）：使用**原模板 + 原变量 + 原收件人**重新发送。
     *
     * <p>安全与可观测：载荷取自 {@code email_delivery_log.resend_payload}（AES-GCM 加密），
     * 接口不回显收件人/变量值；失败直接返回 provider 的真实错误（{@code EmailSendException}
     * 已由网关包装为 503 中文消息，200 之外不假装成功）。</p>
     */
    public ResendOutcome resend(long id, Long actorId, String clientIp) {
        EmailDeliveryLog row = emailDeliveryLogMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(404, "投递记录不存在");
        }
        enforceResendRateLimit(id);
        if (row.getTemplateCode() == null || row.getTemplateCode().isBlank()) {
            throw new BusinessException(400, "该投递记录没有可复用的模板，无法重发");
        }
        Map<String, String> vars = decryptVariables(row.getResendPayload());
        if (vars == null) {
            throw new BusinessException(400,
                    "该投递记录缺少重发所需信息（历史记录或未保存载荷），不支持重发");
        }

        // 限流时间戳先落，避免连续点击造成并发重发。
        lastResendAt.put(id, System.currentTimeMillis());

        try {
            emailGateway.sendTemplate(
                    vars.remove(RESEND_TO_KEY), row.getTemplateCode(), vars);
        } catch (RuntimeException e) {
            auditResend(actorId, clientIp, row, false, e);
            throw e;
        }
        auditResend(actorId, clientIp, row, true, null);

        EmailDeliveryLog latest = findLatestByMask(row.getToMasked(), row.getUserId());
        String message = EmailDeliveryRecorder.EmailDeliveryRecord.STATUS_SENT.equals(row.getStatus())
                ? "该记录原状态为已发送；已按原模板重新发送，新记录见 latestDelivery"
                : "已按原模板重新发送，新记录见 latestDelivery";
        return new ResendOutcome(toDto(latest == null ? row : latest), message);
    }

    /** 重发结果：新记录 + 给管理端展示的附加说明（task-134）。 */
    public record ResendOutcome(EmailDeliveryDTO latestDelivery, String message) {
    }

    /** 重发限流（同一记录 10 秒内只允许一次，返回 429）。 */
    private void enforceResendRateLimit(long id) {
        long now = System.currentTimeMillis();
        Long last = lastResendAt.get(id);
        if (last == null) {
            return;
        }
        long elapsed = now - last;
        if (elapsed < RESEND_MIN_INTERVAL_MILLIS) {
            long retryAfter = (RESEND_MIN_INTERVAL_MILLIS - Math.max(0, elapsed) + 999) / 1000;
            throw new RateLimitedException("重发过于频繁，请 " + retryAfter + " 秒后再试",
                    Math.max(1, retryAfter));
        }
    }

    /** 解密重发载荷；缺失/解密失败返回 null（不可重发，不影响历史展示）。 */
    private Map<String, String> decryptVariables(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            ConfigCipher cipher = resendCipher;
            if (cipher == null) {
                cipher = ConfigCipher.fromEnvironment();
                resendCipher = cipher;
            }
            String json = cipher.decrypt(payload);
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            Object to = parsed.get("to");
            if (to == null || String.valueOf(to).isBlank()) {
                return null;
            }
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put(RESEND_TO_KEY, String.valueOf(to));
            Object variables = parsed.get("variables");
            if (variables instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    vars.put(String.valueOf(entry.getKey()),
                            entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                }
            }
            return vars;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("email delivery resend payload unusable: id-cipher={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private void auditResend(Long actorId, String clientIp, EmailDeliveryLog row,
                             boolean ok, RuntimeException error) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("originalId", row.getId());
            detail.put("toMasked", row.getToMasked());
            detail.put("templateCode", row.getTemplateCode());
            detail.put("ok", ok);
            if (!ok) {
                detail.put("error", sanitizeLastError(error == null ? null : error.getMessage()));
            }
            auditService.record(actorId, displayName(actorId), AUDIT_ACTION_RESEND,
                    "email_delivery_log", row.getId(),
                    objectMapper.writeValueAsString(detail), clientIp);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("record resend audit failed (not blocking): id={} err={}",
                    row.getId(), e.getClass().getSimpleName());
        }
    }

    private EmailDeliveryLog findLatestByMask(String toMasked, Long userId) {
        try {
            return emailDeliveryLogMapper.selectOne(Wrappers.<EmailDeliveryLog>lambdaQuery()
                    .eq(EmailDeliveryLog::getToMasked, toMasked)
                    .eq(userId != null, EmailDeliveryLog::getUserId, userId)
                    .orderByDesc(EmailDeliveryLog::getId)
                    .last("LIMIT 1"));
        } catch (RuntimeException e) {
            log.warn("query latest delivery row failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private String displayName(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            User user = userMapper.selectById(userId);
            if (user == null) {
                return null;
            }
            return user.getNickname() != null && !user.getNickname().isBlank()
                    ? user.getNickname() : user.getUsername();
        } catch (RuntimeException e) {
            log.warn("resolve admin display name failed: userId={} err={}",
                    userId, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * lastError 脱敏（task-134）：去掉服务商 key 形态与敏感键值、折叠空白、截断 200 字符。
     */
    static String sanitizeLastError(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.replaceAll("[\\r\\n\\t]+", " ").trim();
        value = SECRET_KEY_PATTERN.matcher(value).replaceAll("re_***");
        value = SECRET_ASSIGNMENT_PATTERN.matcher(value).replaceAll("$1$2***");
        return value.length() <= LAST_ERROR_MAX ? value : value.substring(0, LAST_ERROR_MAX);
    }

    private EmailDeliveryDTO toDto(EmailDeliveryLog log) {
        return new EmailDeliveryDTO(
                log.getId(),
                log.getUserId(),
                log.getToMasked(),
                log.getPurpose(),
                log.getTemplateCode(),
                log.getSubject(),
                log.getStatus(),
                log.getFailureReason(),
                parseVariableKeys(log.getVariableKeys()),
                log.getPlaceholdersLeft(),
                log.getProvider(),
                log.getDurationMs(),
                log.getAttempts() == null ? 1 : log.getAttempts(),
                sanitizeLastError(log.getFailureReason()),
                log.getCreatedAt());
    }

    /** variable_keys 是 JSON 数组字符串；解析失败时退化为空列表（不阻塞管理端展示）。 */
    List<String> parseVariableKeys(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("parse email delivery variable_keys failed: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private static long number(Map<String, Object> row, String key) {
        if (row == null) {
            return 0L;
        }
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
