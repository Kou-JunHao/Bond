package com.bond.transfer.service;

import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.entitlement.EntitlementMeService;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.PlanCatalog;
import com.bond.transfer.entitlement.RedeemCode;
import com.bond.transfer.mapper.EntitlementMapper;
import com.bond.transfer.mapper.RedeemCodeMapper;
import com.bond.transfer.mapper.UserMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理端权益操作：改档位、加量/开白（user_entitlements）、发放兑换码；全部写操作日志。
 * 本期不接支付，只做手动授权；兑换码链路复用 orders(provider='redeem') 作为审计与幂等键。
 */
@Service
@RequiredArgsConstructor
public class EntitlementAdminService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementAdminService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern CONSTRAINT_PATTERN = Pattern.compile("constraint \"([^\"]+)\"");

    private final EntitlementMapper entitlementMapper;
    private final RedeemCodeMapper redeemCodeMapper;
    private final UserMapper userMapper;
    private final EntitlementResolver entitlementResolver;
    private final EntitlementMeService entitlementMeService;
    private final PlanCatalog planCatalog;
    private final QuotaService quotaService;
    private final ObjectMapper objectMapper;

    /**
     * 设置用户档位与到期时间（months 与 expiresAt 二选一，都为空默认 1 个月）。
     *
     * <p>task-109 两处显式处理：</p>
     * <ol>
     *   <li>{@code users.plan_expires_at} 必须允许写 NULL（free 档没有到期时间）。
     *       不能用 {@code userMapper.updateById(user)}——MyBatis-Plus 默认
     *       {@code FieldStrategy.NOT_NULL} 会跳过 null 字段，导致“免费版仍显示到期时间”。</li>
     *   <li>同一事务内先把该用户其它 active/grace 订阅置为 expired（V22 起 no_overlap
     *       只约束 active 订阅），再插入新订阅，最后写 users；任何一步失败整体回滚。
     *       exclusion 冲突映射为 409，不再裸 500。</li>
     * </ol>
     */
    @Transactional
    public void setUserPlan(Long adminUserId, Long targetUserId, String planCode, Integer months,
                            OffsetDateTime expiresAt) {
        requireUser(targetUserId);
        String code = (planCode == null || planCode.isBlank()) ? "free" : planCode.trim();
        boolean free = "free".equals(code);
        if (!free && entitlementMapper.selectPlanLimits(code) == null) {
            throw new BusinessException(400, "unknown plan: " + code);
        }
        OffsetDateTime expiry = free ? null : expiresAt;
        if (!free && expiry == null) {
            int effectiveMonths = (months == null || months <= 0) ? 1 : months;
            expiry = OffsetDateTime.now().plusMonths(effectiveMonths);
        }

        // 1) 先让旧的 active/grace 订阅失效（区间截断到 now()）：
        //    既保证 V22 迁移窗口内旧约束不冲突，也让历史数据语义干净。
        entitlementMapper.expireActiveSubscriptions(targetUserId);

        // 2) 写入新订阅（仅付费档）；EXCLUDE 冲突要可诊断，不能裸 500。
        if (!free) {
            try {
                entitlementMapper.insertSubscription(targetUserId, code, "active",
                        OffsetDateTime.now(), expiry, "manual", "admin:" + adminUserId);
            } catch (DataIntegrityViolationException e) {
                String constraint = constraintName(e);
                log.warn("plan change rejected by exclusion constraint: user={}, plan={}, constraint={}",
                        targetUserId, code, constraint, e);
                throw new BusinessException(409, "该用户存在重叠订阅，请重试");
            }
        }

        // 3) users 显式 UPDATE（允许 plan_expires_at = NULL）；放在订阅之后，
        //    保证 users 永远指向已经成功落库的档位。
        entitlementMapper.updateUserPlan(targetUserId, code, expiry);

        quotaService.refreshLimitsForUser(targetUserId);
        entitlementResolver.invalidate(targetUserId);
        // 权益接口缓存也要失效，否则客户端在 TTL 内仍看到旧档位
        entitlementMeService.invalidate(targetUserId);
        planCatalog.refresh();
        writeLog(adminUserId, "set-plan", targetUserId,
                safeJson(Map.of("planCode", code, "expiresAt", String.valueOf(expiry))));
    }

    /** 从异常根因里提取 PG 约束名（日志诊断用；不下发给客户端，避免泄漏 schema）。 */
    private static String constraintName(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause() == null
                ? e.getMessage()
                : e.getMostSpecificCause().getMessage();
        if (message == null) {
            return "unknown";
        }
        Matcher matcher = CONSTRAINT_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    /** 加量/开白：直接写 user_entitlements（优先于档位），expiresAt 为空表示永久。 */
    @Transactional
    public void grant(Long adminUserId, Long targetUserId, String key, long value,
                      OffsetDateTime expiresAt, String note) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(400, "entitlement key must not be blank");
        }
        if (value < 0) {
            throw new BusinessException(400, "entitlement value must be >= 0");
        }
        requireUser(targetUserId);
        entitlementMapper.upsertOverride(targetUserId, key.trim(), value, expiresAt, note);
        if ("storage_bytes".equals(key.trim())) {
            quotaService.refreshLimitsForUser(targetUserId);
        }
        entitlementResolver.invalidate(targetUserId);
        // 加量/开白同样影响 /api/entitlements/me 的 limits，主动失效
        entitlementMeService.invalidate(targetUserId);
        writeLog(adminUserId, "grant-entitlement", targetUserId,
                safeJson(Map.of("key", key, "value", value, "expiresAt", String.valueOf(expiresAt))));
    }

    /** 批量生成兑换码（一次有效，绑定档位与时长）。 */
    @Transactional
    public List<String> createRedeemCodes(Long adminUserId, String planCode, int durationDays,
                                          int count, OffsetDateTime expiresAt) {
        if (planCode == null || planCode.isBlank() || entitlementMapper.selectPlanLimits(planCode) == null) {
            throw new BusinessException(400, "unknown plan: " + planCode);
        }
        if (durationDays <= 0) {
            throw new BusinessException(400, "durationDays must be > 0");
        }
        int effectiveCount = Math.max(1, Math.min(count, 500));
        List<String> codes = new ArrayList<>(effectiveCount);
        for (int i = 0; i < effectiveCount; i++) {
            RedeemCode row = new RedeemCode();
            row.setCode(randomCode());
            row.setPlanCode(planCode);
            row.setDurationDays(durationDays);
            row.setExpiresAt(expiresAt);
            row.setStatus("pending");
            row.setCreatedBy(adminUserId);
            redeemCodeMapper.insert(row);
            codes.add(row.getCode());
        }
        writeLog(adminUserId, "create-redeem-codes", null,
                safeJson(Map.of("planCode", planCode, "durationDays", durationDays, "count", effectiveCount)));
        log.info("Generated {} redeem codes for plan {} (durationDays={}, admin={})",
                effectiveCount, planCode, durationDays, adminUserId);
        return codes;
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return user;
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(20);
        sb.append("BOND-");
        for (int i = 0; i < 16; i++) {
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private void writeLog(Long adminUserId, String action, Long targetUserId, String detail) {
        entitlementMapper.insertOperationLog(adminUserId, action, targetUserId, detail);
    }

    private String safeJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return String.valueOf(map);
        }
    }
}
