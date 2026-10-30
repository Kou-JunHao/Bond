package com.bond.transfer.service;

import com.bond.common.dto.AdminLoginBlockDTO;
import com.bond.common.dto.AdminLoginGuardDTO;
import com.bond.common.dto.AdminLoginLogDTO;
import com.bond.common.dto.AdminLoginLogStatsDTO;
import com.bond.common.dto.PageResult;
import com.bond.common.entity.AdminLoginBlock;
import com.bond.common.entity.AdminLoginLog;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.AdminLoginBlockQueryMapper;
import com.bond.transfer.mapper.AdminLoginLogQueryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 管理端登录审计 / 封禁查询与策略管理（task-144，bond-transfer 管理端）。
 *
 * <p>写路径（登录失败计数、封禁判定）在 bond-auth 的 {@code AdminLoginGuardService}；
 * 本服务只负责管理后台读取与人工处置：日志分页/统计、封禁列表/解除、策略 GET/PUT。
 * 项目未注册 MyBatis-Plus 分页插件，统一 {@code LIMIT/OFFSET + 独立 count}。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminLoginAuditAdminService {

    static final String KEY_ENABLED = "admin_guard.enabled";
    static final String KEY_WINDOW = "admin_guard.fail_window_seconds";
    static final String KEY_THRESHOLD_IP = "admin_guard.fail_threshold_ip";
    static final String KEY_THRESHOLD_USER = "admin_guard.fail_threshold_user";
    static final String KEY_BAN = "admin_guard.ban_seconds";
    static final String KEY_ALLOWLIST = "admin_guard.ip_allowlist";

    static final int DEFAULT_WINDOW = 600;
    static final int DEFAULT_THRESHOLD_IP = 5;
    static final int DEFAULT_THRESHOLD_USER = 10;
    static final int DEFAULT_BAN_SECONDS = 900;

    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int STATS_TOP_LIMIT = 10;

    private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AdminLoginLogQueryMapper logMapper;
    private final AdminLoginBlockQueryMapper blockMapper;
    private final ConfigService configService;

    // ---------------------------------------------------------------- logs

    public PageResult<AdminLoginLogDTO> pageLogs(int page, int size, String ip, String username,
                                                 String outcome, String from, String to) {
        int safePage = normalizePage(page);
        int safeSize = normalizePageSize(size);
        OffsetDateTime fromTime = parseFrom(from);
        OffsetDateTime toTime = parseTo(to);

        List<AdminLoginLog> rows = logMapper.selectPage(fromTime, toTime, trimToNull(ip), trimToNull(username),
                normalizeOutcome(outcome), safeSize, (long) (safePage - 1) * safeSize);
        long total = logMapper.countPage(fromTime, toTime, trimToNull(ip), trimToNull(username),
                normalizeOutcome(outcome));

        Set<String> blockedIps = new HashSet<>();
        Set<String> blockedUsers = new HashSet<>();
        for (Map<String, Object> row : logMapper.selectActiveScopes()) {
            Object scope = row.get("scope");
            Object value = row.get("value");
            if (scope == null || value == null) {
                continue;
            }
            if ("ip".equals(scope.toString())) {
                blockedIps.add(value.toString());
            } else if ("user".equals(scope.toString())) {
                blockedUsers.add(value.toString());
            }
        }

        List<AdminLoginLogDTO> items = new ArrayList<>(rows.size());
        for (AdminLoginLog row : rows) {
            boolean blockedNow = blockedIps.contains(row.getIp()) || blockedUsers.contains(row.getUsername());
            items.add(AdminLoginLogDTO.of(row, blockedNow));
        }
        return PageResult.of(items, total, safePage, safeSize);
    }

    public AdminLoginLogStatsDTO stats(int days) {
        int safeDays = Math.max(1, Math.min(90, days));
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(safeDays);

        Map<String, Object> totals = logMapper.totals(since);
        long total = asLong(totals, "total");
        long success = asLong(totals, "success");

        AdminLoginLogStatsDTO dto = new AdminLoginLogStatsDTO();
        dto.setDays(safeDays);
        dto.setTotal(total);
        dto.setSuccess(success);
        dto.setFailed(Math.max(0L, total - success));
        for (Map<String, Object> row : logMapper.countByOutcome(since)) {
            dto.getByOutcome().put(String.valueOf(row.get("outcome")), asLong(row, "cnt"));
        }
        for (Map<String, Object> row : logMapper.topIps(since, STATS_TOP_LIMIT)) {
            AdminLoginLogStatsDTO.IpStat stat = new AdminLoginLogStatsDTO.IpStat();
            stat.setIp(String.valueOf(row.get("value")));
            stat.setCount(asLong(row, "cnt"));
            stat.setFailed(asLong(row, "failed"));
            dto.getTopIps().add(stat);
        }
        for (Map<String, Object> row : logMapper.topUsers(since, STATS_TOP_LIMIT)) {
            AdminLoginLogStatsDTO.UserStat stat = new AdminLoginLogStatsDTO.UserStat();
            stat.setUsername(String.valueOf(row.get("value")));
            stat.setCount(asLong(row, "cnt"));
            stat.setFailed(asLong(row, "failed"));
            dto.getTopUsers().add(stat);
        }
        for (Map<String, Object> row : logMapper.daily(since)) {
            AdminLoginLogStatsDTO.DailyStat stat = new AdminLoginLogStatsDTO.DailyStat();
            stat.setDate(String.valueOf(row.get("day")));
            stat.setSuccess(asLong(row, "success"));
            stat.setFailed(asLong(row, "failed"));
            dto.getDaily().add(stat);
        }
        return dto;
    }

    // --------------------------------------------------------------- blocks

    public PageResult<AdminLoginBlockDTO> pageBlocks(Boolean active, int page, int size,
                                                     String scope, String value) {
        int safePage = normalizePage(page);
        int safeSize = normalizePageSize(size);
        String safeScope = normalizeScope(scope);
        String safeValue = trimToNull(value);

        List<AdminLoginBlock> rows = blockMapper.selectPage(active, safeScope, safeValue, safeSize,
                (long) (safePage - 1) * safeSize);
        long total = blockMapper.countPage(active, safeScope, safeValue);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<AdminLoginBlockDTO> items = new ArrayList<>(rows.size());
        for (AdminLoginBlock row : rows) {
            items.add(AdminLoginBlockDTO.of(row, now));
        }
        return PageResult.of(items, total, safePage, safeSize);
    }

    public void lift(Long id, Long actorId) {
        if (id == null || id <= 0) {
            throw new BusinessException(400, "封禁 id 非法");
        }
        int rows = blockMapper.lift(id, actorId);
        if (rows == 0) {
            throw new BusinessException(404, "封禁不存在或已解除");
        }
        log.info("admin login block lifted: id={} actor={}", id, actorId);
    }

    // ---------------------------------------------------------------- guard

    public AdminLoginGuardDTO guard() {
        AdminLoginGuardDTO dto = new AdminLoginGuardDTO();
        dto.setEnabled(configService.getBoolean(KEY_ENABLED, true));
        dto.setFailWindowSeconds(configService.getInt(KEY_WINDOW, DEFAULT_WINDOW));
        dto.setFailThresholdIp(configService.getInt(KEY_THRESHOLD_IP, DEFAULT_THRESHOLD_IP));
        dto.setFailThresholdUser(configService.getInt(KEY_THRESHOLD_USER, DEFAULT_THRESHOLD_USER));
        dto.setBanSeconds(configService.getInt(KEY_BAN, DEFAULT_BAN_SECONDS));
        dto.setIpAllowlist(parseAllowlist(configService.get(KEY_ALLOWLIST, "")));
        return dto;
    }

    /**
     * 部分更新策略（扁平字段，null = 不修改）。先全部校验，再单事务批量写配置并刷新缓存。
     */
    public AdminLoginGuardDTO updateGuard(Map<String, Object> body, Long actorId) {
        Map<String, String> updates = new LinkedHashMap<>();
        if (body != null) {
            Object enabled = body.get("enabled");
            if (enabled != null) {
                if (!(enabled instanceof Boolean)) {
                    throw new BusinessException(400, "enabled 必须是布尔值");
                }
                updates.put(KEY_ENABLED, String.valueOf(enabled));
            }
            Integer window = intField(body, "failWindowSeconds", 60, 86400);
            if (window != null) updates.put(KEY_WINDOW, String.valueOf(window));
            Integer thresholdIp = intField(body, "failThresholdIp", 3, 100);
            if (thresholdIp != null) updates.put(KEY_THRESHOLD_IP, String.valueOf(thresholdIp));
            Integer thresholdUser = intField(body, "failThresholdUser", 3, 100);
            if (thresholdUser != null) updates.put(KEY_THRESHOLD_USER, String.valueOf(thresholdUser));
            Integer ban = intField(body, "banSeconds", 60, 86400);
            if (ban != null) updates.put(KEY_BAN, String.valueOf(ban));

            Object allowlist = body.get("ipAllowlist");
            if (allowlist != null) {
                List<String> entries = parseAllowlist(allowlist);
                for (String entry : entries) {
                    if (!isIpLiteral(entry)) {
                        throw new BusinessException(400, "ipAllowlist 含非法 IP: " + entry);
                    }
                }
                updates.put(KEY_ALLOWLIST, String.join(",", entries));
            }
        }
        if (updates.isEmpty()) {
            return guard();
        }
        configService.batchUpdate(updates, actorId);
        log.info("admin login guard updated: keys={} actor={}", updates.keySet(), actorId);
        return guard();
    }

    // -------------------------------------------------------------- helpers

    private static Integer intField(Map<String, Object> body, String name, int min, int max) {
        Object raw = body.get(name);
        if (raw == null) {
            return null;
        }
        int value;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else if (raw instanceof String text) {
            try {
                value = Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                throw new BusinessException(400, name + " 必须是整数");
            }
        } else {
            throw new BusinessException(400, name + " 必须是整数");
        }
        if (value < min || value > max) {
            throw new BusinessException(400, name + " 超出范围（" + min + "~" + max + "）：" + value);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseAllowlist(Object raw) {
        List<String> entries = new ArrayList<>();
        if (raw == null) {
            return entries;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) {
                    entries.add(item.toString().trim());
                }
            }
        } else {
            for (String part : raw.toString().split(",")) {
                entries.add(part.trim());
            }
        }
        entries.removeIf(String::isEmpty);
        return entries;
    }

    private static boolean isIpLiteral(String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.length() > 64) {
            return false;
        }
        if (!candidate.matches("[0-9a-fA-F.:]+")) {
            return false;
        }
        try {
            InetAddress.getByName(candidate);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String normalizeOutcome(String outcome) {
        String value = trimToNull(outcome);
        if (value == null) {
            return null;
        }
        value = value.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "success", "bad_credentials", "user_disabled", "blocked", "captcha_required", "error" -> value;
            default -> throw new BusinessException(400, "outcome 非法：" + outcome);
        };
    }

    private static String normalizeScope(String scope) {
        String value = trimToNull(scope);
        if (value == null) {
            return null;
        }
        value = value.toLowerCase(Locale.ROOT);
        if (!"ip".equals(value) && !"user".equals(value)) {
            throw new BusinessException(400, "scope 只能是 ip 或 user");
        }
        return value;
    }

    private static OffsetDateTime parseFrom(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
        }
        OffsetDateTime parsed = tryParse(value);
        if (parsed == null) {
            throw new BusinessException(400, "from 格式非法，应为 yyyy-MM-dd 或 ISO-8601 时间");
        }
        return parsed;
    }

    private static OffsetDateTime parseTo(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        OffsetDateTime parsed = tryParse(value);
        if (parsed == null) {
            throw new BusinessException(400, "to 格式非法，应为 yyyy-MM-dd 或 ISO-8601 时间");
        }
        return parsed;
    }

    private static OffsetDateTime tryParse(String value) {
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value, DATE).atStartOfDay().atOffset(ZONE);
            }
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int normalizePage(int page) {
        return Math.max(1, page);
    }

    private static int normalizePageSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long asLong(Map<String, Object> row, String column) {
        if (row == null) {
            return 0L;
        }
        Object raw = row.get(column);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return raw == null ? 0L : Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
