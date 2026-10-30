package com.bond.transfer.trash;

import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.service.ConfigService;
import org.slf4j.Logger;

/**
 * 回收站保留期决策（task-110），个人空间与工作区两条删除路径共用。
 *
 * <p>语义：{@code trash_expires_at = 删除时刻 now() + daysFor(ownerId)}，在**删除那一刻**算定并落库，
 * 之后用户调档不会改变已存在条目的过期时间（避免“升级后历史条目突然续命”）。</p>
 *
 * <p>优先级（由高到低）：</p>
 * <ol>
 *   <li>用户（工作区取 owner）生效档位的 {@code plans.limits.trash_retention_days}（free 7 / plus 30 / team 90）；</li>
 *   <li>全局配置 {@value #CONFIG_KEY}（system_configs，缺失时按 7）；</li>
 *   <li>硬编码 {@value #HARD_FALLBACK_DAYS} 天。</li>
 * </ol>
 *
 * <p>0 表示不限期（只有档位/配置显式设为 0 才会出现）；目录递归软删由调用方**一次**算好
 * {@code expiresAt} 再传给整棵子树，保证父子时间戳完全一致。</p>
 */
public final class TrashRetentionPolicy {

    /** 全局兜底配置键。 */
    public static final String CONFIG_KEY = "trash.retention_days";

    /** 硬编码兜底天数。 */
    public static final int HARD_FALLBACK_DAYS = 7;

    private TrashRetentionPolicy() {
    }

    /**
     * 计算保留天数：档位 &gt; 全局配置 &gt; 硬编码 7；档位解析失败/非法时 WARN 并用全局兜底。
     *
     * <p>返回 0 表示不限期（调用方应把 expiresAt 置 null）。</p>
     */
    public static int daysFor(EntitlementResolver entitlementResolver, ConfigService configService,
                              Long ownerId, Logger log) {
        int fallback = fallbackDays(configService);
        if (ownerId != null) {
            try {
                int days = entitlementResolver.resolve(ownerId).limits().trashRetentionDays();
                if (days >= 0) {
                    return days;
                }
                if (log != null) {
                    log.warn("trash_retention_days 非法（{}），用户 {} 回落到全局兜底 {} 天",
                            days, ownerId, fallback);
                }
            } catch (RuntimeException e) {
                if (log != null) {
                    log.warn("failed to resolve trash retention for user {}: {}", ownerId, e.toString());
                }
            }
        }
        return fallback;
    }

    /** 全局兜底天数：{@value #CONFIG_KEY}；缺失/负数按 7 处理。 */
    public static int fallbackDays(ConfigService configService) {
        int configured = configService.getInt(CONFIG_KEY, HARD_FALLBACK_DAYS);
        return configured >= 0 ? configured : HARD_FALLBACK_DAYS;
    }
}
