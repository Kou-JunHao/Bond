package com.bond.transfer.entitlement;

import com.bond.transfer.mapper.EntitlementMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.service.ConfigService;
import com.bond.common.entity.User;
import com.bond.common.util.TtlCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 档位解析器：生效权益 = 套餐默认值 ← 用户级覆盖（user_entitlements）。
 *
 * <p>三态（task-7 单测覆盖）：</p>
 * <ol>
 *   <li>默认：无订阅/无到期 → free 档；</li>
 *   <li>覆盖：user_entitlements 中的有效键覆盖档位值；</li>
 *   <li>过期：订阅/plan_expires_at 过期超过 grace_days → 回退 free（数据不删）。</li>
 * </ol>
 *
 * <p>本地 TTL 缓存（有界：{@value #CACHE_MAX_ENTRIES} 条，防止 entries 永驻成为内存泄漏），
 * TTL 由 system_configs 的 {@code entitlement.cache_seconds} 控制（默认 60s，0=关闭缓存），
 * 授权变更（管理端/兑换）主动 {@link #invalidate(Long)}。</p>
 */
@Service
public class EntitlementResolver {

    private static final Logger log = LoggerFactory.getLogger(EntitlementResolver.class);
    private static final String CACHE_TTL_CONFIG_KEY = "entitlement.cache_seconds";
    private static final long DEFAULT_CACHE_TTL_SECONDS = 60L;
    /** 缓存硬上限：超出后先清过期、再淘汰最旧，绝不无界增长。 */
    private static final int CACHE_MAX_ENTRIES = 10_000;

    private final EntitlementMapper entitlementMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    /** null 表示缓存关闭（entitlement.cache_seconds <= 0）。 */
    private final TtlCache<Long, Entitlements> cache;

    /** 单测便利构造：使用默认 60s 缓存。 */
    public EntitlementResolver(EntitlementMapper entitlementMapper, UserMapper userMapper,
                               ObjectMapper objectMapper) {
        this(entitlementMapper, userMapper, objectMapper, null);
    }

    @Autowired
    public EntitlementResolver(EntitlementMapper entitlementMapper, UserMapper userMapper,
                               ObjectMapper objectMapper, ConfigService configService) {
        this.entitlementMapper = entitlementMapper;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
        long ttlSeconds = configService == null
                ? DEFAULT_CACHE_TTL_SECONDS
                : Math.max(0L, configService.getLong(CACHE_TTL_CONFIG_KEY, DEFAULT_CACHE_TTL_SECONDS));
        this.cache = ttlSeconds > 0
                ? new TtlCache<>(ttlSeconds * 1000L, CACHE_MAX_ENTRIES)
                : null;
        log.info("EntitlementResolver cache TTL = {}s (maxEntries={})", ttlSeconds, CACHE_MAX_ENTRIES);
    }

    public Entitlements resolve(Long userId) {
        if (userId == null) {
            return Entitlements.free();
        }
        if (cache == null) {
            return compute(userId);
        }
        return cache.getOrCompute(userId, () -> compute(userId));
    }

    /** 强制实时解析（跳过缓存读取，用于配额接口等必须立即反映档位变更的场景）。 */
    public Entitlements resolveFresh(Long userId) {
        if (userId == null) {
            return Entitlements.free();
        }
        Entitlements entitlements = compute(userId);
        if (cache != null) {
            cache.put(userId, entitlements);
        }
        return entitlements;
    }

    public void invalidate(Long userId) {
        if (cache != null && userId != null) {
            cache.invalidate(userId);
        }
    }

    public void invalidateAll() {
        if (cache != null) {
            cache.clear();
        }
    }

    /**
     * 档位参数（plans.limits）变更后失效该档位的缓存（task-112）。
     *
     * <p>缓存按用户维度存放 {@link Entitlements}，这里按 {@code planCode} 过滤出受影响的用户并逐个移除，
     * 使下一次删除/配额判断立即使用新档位值，不必等 TTL（默认 60s）过期。缓存关闭时为空操作。</p>
     */
    public void invalidatePlan(String planCode) {
        if (cache == null || planCode == null || planCode.isBlank()) {
            return;
        }
        int removed = 0;
        for (Map.Entry<Long, Entitlements> entry : cache.snapshot().entrySet()) {
            Entitlements entitlements = entry.getValue();
            if (entitlements != null && planCode.equals(entitlements.planCode())) {
                cache.invalidate(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("invalidated {} cached entitlements for plan {}", removed, planCode);
        }
    }

    /**
     * 回收站保留天数（task-110）：取该用户**生效档位**的 {@code limits.trash_retention_days}。
     *
     * <p>优先级（由调用方负责全局兜底）：档位 &gt; 全局配置 {@code trash.retention_days} &gt; 硬编码 7。
     * 本方法只负责档位部分：用户缺失 / 解析异常 / 非法负数时 WARN 并返回 {@code fallbackDays}，
     * 0 是合法值（表示不限期）。</p>
     *
     * @param userId       用户 id（可为 null：直接返回兜底值）
     * @param fallbackDays 档位取不到时的兜底天数（调用方已按「全局配置 &gt; 7」算好）
     */
    public int trashRetentionDays(Long userId, int fallbackDays) {
        int fallback = Math.max(0, fallbackDays);
        if (userId == null) {
            return fallback;
        }
        try {
            int days = resolve(userId).limits().trashRetentionDays();
            if (days >= 0) {
                return days;
            }
            log.warn("trash_retention_days 非法（{}），用户 {} 回落到 {} 天", days, userId, fallback);
        } catch (RuntimeException e) {
            log.warn("failed to resolve trash retention for user {}: {}", userId, e.toString());
        }
        return fallback;
    }

    private Entitlements compute(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Entitlements.free();
        }

        // 1) 有效订阅（含 grace 窗口）优先
        SubscriptionRow subscription = entitlementMapper.selectLatestSubscription(userId);
        if (subscription != null && subscription.getPeriodEnd() != null) {
            PlanLimits planLimits = loadLimits(subscription.getPlanCode());
            OffsetDateTime graceUntil = subscription.getPeriodEnd().plusDays(planLimits.graceDays());
            if (!OffsetDateTime.now().isAfter(graceUntil)) {
                return applyOverrides(userId,
                        new Entitlements(subscription.getPlanCode(), planLimits, subscription.getPeriodEnd()));
            }
        }

        // 2) users.plan_code + plan_expires_at（过期后按 free，grace 内仍保留）
        String planCode = blankToDefault(user.getPlanCode(), "free");
        PlanLimits limits = loadLimits(planCode);
        OffsetDateTime expiresAt = user.getPlanExpiresAt();
        if (expiresAt != null && OffsetDateTime.now().isAfter(expiresAt.plusDays(limits.graceDays()))) {
            log.info("Plan expired beyond grace, falling back to free: userId={}, plan={}, expiredAt={}",
                    userId, planCode, expiresAt);
            planCode = "free";
            limits = loadLimits("free");
            expiresAt = null;
        }
        return applyOverrides(userId, new Entitlements(planCode, limits, expiresAt));
    }

    private Entitlements applyOverrides(Long userId, Entitlements entitlements) {
        List<EntitlementRow> overrides = entitlementMapper.selectActiveOverrides(userId);
        PlanLimits limits = entitlements.limits();
        for (EntitlementRow row : overrides) {
            if (row.getKey() != null && row.getValue() != null) {
                limits = limits.applyOverride(row.getKey(), row.getValue());
            }
        }
        return new Entitlements(entitlements.planCode(), limits, entitlements.expiresAt());
    }

    private PlanLimits loadLimits(String planCode) {
        String json = entitlementMapper.selectPlanLimits(planCode);
        return PlanLimits.fromJson(json, objectMapper);
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
