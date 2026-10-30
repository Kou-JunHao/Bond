package com.bond.transfer.trash;

import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.Entitlements;
import com.bond.transfer.entitlement.PlanLimits;
import com.bond.transfer.service.ConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * task-110：回收站保留期统一策略的单测。
 *
 * <p>优先级：档位 {@code trash_retention_days} &gt; 全局配置 {@code trash.retention_days}
 * （默认 7）&gt; 硬编码 7；0 表示不限期。个人空间与工作区两条删除路径共用本策略。</p>
 */
@ExtendWith(MockitoExtension.class)
class TrashRetentionPolicyTest {

    private static final Long OWNER = 7L;

    @Mock
    private EntitlementResolver entitlementResolver;
    @Mock
    private ConfigService configService;

    private static Entitlements plan(int trashRetentionDays) {
        PlanLimits limits = new PlanLimits(
                10_737_418_240L, 5_368_709_120L, 2_147_483_648L,
                2, 1, 3, 5_242_880L, 7, 3, 30, 5, trashRetentionDays);
        return new Entitlements("team", limits, null);
    }

    @Test
    @DisplayName("档位优先：team 档 90 天覆盖全局配置 7 天")
    void tierValueWinsOverGlobalConfig() {
        when(configService.getInt(TrashRetentionPolicy.CONFIG_KEY, 7)).thenReturn(7);
        when(entitlementResolver.resolve(OWNER)).thenReturn(plan(90));

        assertEquals(90, TrashRetentionPolicy.daysFor(entitlementResolver, configService, OWNER, null));
    }

    @Test
    @DisplayName("档位为 0 = 不限期，不用全局配置覆盖")
    void zeroTierMeansUnlimited() {
        when(configService.getInt(TrashRetentionPolicy.CONFIG_KEY, 7)).thenReturn(30);
        when(entitlementResolver.resolve(OWNER)).thenReturn(plan(0));

        assertEquals(0, TrashRetentionPolicy.daysFor(entitlementResolver, configService, OWNER, null));
    }

    @Test
    @DisplayName("owner 缺失（个人空间解析不到）→ 全局配置兜底")
    void missingOwnerFallsBackToGlobalConfig() {
        when(configService.getInt(TrashRetentionPolicy.CONFIG_KEY, 7)).thenReturn(30);

        assertEquals(30, TrashRetentionPolicy.daysFor(entitlementResolver, configService, null, null));
    }

    @Test
    @DisplayName("档位解析异常 → 全局配置兜底，不把异常抛给删除路径")
    void resolverFailureFallsBackToGlobalConfig() {
        when(configService.getInt(TrashRetentionPolicy.CONFIG_KEY, 7)).thenReturn(30);
        when(entitlementResolver.resolve(OWNER)).thenThrow(new IllegalStateException("plans broken"));

        assertEquals(30, TrashRetentionPolicy.daysFor(entitlementResolver, configService, OWNER, null));
    }

    @Test
    @DisplayName("全局配置本身非法（负数）→ 回落硬编码 7 天")
    void invalidGlobalConfigFallsBackToHardcodedSeven() {
        when(configService.getInt(TrashRetentionPolicy.CONFIG_KEY, 7)).thenReturn(-1);

        assertEquals(7, TrashRetentionPolicy.daysFor(entitlementResolver, configService, null, null));
    }

    @Test
    @DisplayName("档位解析到 FREE_DEFAULTS 时 = 7 天")
    void freeDefaultsAreSevenDays() {
        when(configService.getInt(TrashRetentionPolicy.CONFIG_KEY, 7)).thenReturn(30);
        when(entitlementResolver.resolve(OWNER)).thenReturn(Entitlements.free());

        assertEquals(7, TrashRetentionPolicy.daysFor(entitlementResolver, configService, OWNER, null));
    }
}
