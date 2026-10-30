package com.bond.transfer.entitlement;

import com.bond.common.dto.EntitlementMeDTO;
import com.bond.common.dto.StorageQuotaDTO;
import com.bond.common.util.TtlCache;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-29 / task-30：{@code GET /api/entitlements/me} 的组装逻辑 + Plan A 缓存。
 *
 * <p>核心断言：展示的 limits 必须等于 EntitlementResolver 的解析结果（与执行侧同源），
 * 不允许本服务再读一遍 plans 表；缓存命中时不得再产生任何 SQL（通过 mock 调用次数验证）。</p>
 */
@ExtendWith(MockitoExtension.class)
class EntitlementMeServiceTest {

    private static final long USER_ID = 7L;
    private static final long PERSONAL_ID = 100L;
    /** 与生产默认一致的 15s TTL，但时钟可推进，测试里不需要真的 sleep。 */
    private static final long TTL_MS = 15_000L;

    @Mock
    private EntitlementResolver entitlementResolver;
    @Mock
    private UsageService usageService;
    @Mock
    private PersonalSpaceService personalSpaceService;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private PlanCatalog planCatalog;

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private EntitlementMeService service;

    @BeforeEach
    void setUp() {
        TtlCache<Long, EntitlementMeDTO> cache = new TtlCache<>(TTL_MS, 50_000, clock::get);
        service = new EntitlementMeService(
                entitlementResolver, usageService, personalSpaceService, workspaceService, planCatalog, cache);
        when(personalSpaceService.resolveWorkspaceId(USER_ID)).thenReturn(PERSONAL_ID);
        when(usageService.currentPeriod()).thenReturn("2026-10");
    }

    private static PlanLimits plan(long relay, long storage, long maxFile) {
        return new PlanLimits(relay, storage, maxFile, 2, 1, 3, 5242880L, 7, 3, 30, 5, 30);
    }

    private static StorageQuotaDTO quota(long used, long max, long fileCount) {
        StorageQuotaDTO dto = new StorageQuotaDTO();
        dto.setUsedBytes(used);
        dto.setMaxBytes(max);
        dto.setFileCount(fileCount);
        dto.setPlanCode("free");
        return dto;
    }

    @Test
    @DisplayName("free 档：limits 取 PlanLimits.FREE_DEFAULTS，无用量行时全 0、剩余=额度")
    void freePlanUsesResolverDefaults() {
        when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(Entitlements.free());
        when(planCatalog.name("free")).thenReturn("Free");
        when(workspaceService.getQuota(eq(PERSONAL_ID), eq(USER_ID), any(Entitlements.class)))
                .thenReturn(quota(0L, 5368709120L, 0L));
        when(usageService.currentMonthDown(USER_ID)).thenReturn(0L);

        EntitlementMeDTO dto = service.me(USER_ID);

        assertEquals("free", dto.getPlanCode());
        assertEquals("Free", dto.getPlanName());
        assertEquals("active", dto.getStatus());
        assertEquals("2026-10", dto.getPeriod());
        assertNull(dto.getExpiresAt());

        assertEquals(PlanLimits.FREE_DEFAULTS.relayBytesMonth(), dto.getLimits().getRelayBytesMonth());
        assertEquals(PlanLimits.FREE_DEFAULTS.storageBytes(), dto.getLimits().getStorageBytes());
        assertEquals(PlanLimits.FREE_DEFAULTS.maxFileSize(), dto.getLimits().getMaxFileSize());
        assertEquals(1, dto.getLimits().getMaxWorkspaces());
        assertEquals(3, dto.getLimits().getMaxWorkspaceMembers());
        assertEquals(2, dto.getLimits().getConcurrentTransfers());
        assertEquals(5242880L, dto.getLimits().getRelayDownBytesPerSec());
        assertEquals(3, dto.getLimits().getGraceDays());
        assertEquals(7, dto.getLimits().getShareExpiresDays());
        assertEquals(30, dto.getLimits().getShareMaxDownloads());
        assertEquals(5, dto.getLimits().getShareMaxLinks());
        assertEquals(7, dto.getLimits().getTrashRetentionDays());

        assertEquals(0L, dto.getUsage().getRelayBytesDown());
        assertEquals(0L, dto.getUsage().getStorageBytes());
        assertEquals(0L, dto.getUsage().getFileCount());
        assertEquals(10737418240L, dto.getUsage().getRelayBytesRemaining());
        assertEquals(5368709120L, dto.getUsage().getStorageBytesRemaining());
        assertFalse(dto.isThrottled());
        assertFalse(dto.isStorageFull());
    }

    @Test
    @DisplayName("用户级覆盖生效：limits 原样来自 resolver（不重新读 plans），状态 active")
    void userOverridesComeFromResolverOnly() {
        PlanLimits overridden = new PlanLimits(
                1_000_000L, 2_000_000_000L, 5_000_000L, 4, 5, 10, 20_000_000L, 30, 3, 200, 20, 15);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(20);
        when(entitlementResolver.resolveFresh(USER_ID))
                .thenReturn(new Entitlements("plus", overridden, expiresAt));
        when(planCatalog.name("plus")).thenReturn("Plus");
        when(workspaceService.getQuota(eq(PERSONAL_ID), eq(USER_ID), any(Entitlements.class)))
                .thenReturn(quota(100L, 2_000_000_000L, 3L));
        when(usageService.currentMonthDown(USER_ID)).thenReturn(1234L);

        EntitlementMeDTO dto = service.me(USER_ID);

        assertEquals("plus", dto.getPlanCode());
        assertEquals("Plus", dto.getPlanName());
        assertEquals("active", dto.getStatus());
        assertEquals(expiresAt, dto.getExpiresAt());
        assertEquals(1_000_000L, dto.getLimits().getRelayBytesMonth());
        assertEquals(2_000_000_000L, dto.getLimits().getStorageBytes());
        assertEquals(5_000_000L, dto.getLimits().getMaxFileSize());
        assertEquals(4, dto.getLimits().getConcurrentTransfers());
        assertEquals(5, dto.getLimits().getMaxWorkspaces());
        assertEquals(10, dto.getLimits().getMaxWorkspaceMembers());
        assertEquals(20_000_000L, dto.getLimits().getRelayDownBytesPerSec());
        assertEquals(30, dto.getLimits().getShareExpiresDays());
        assertEquals(1234L, dto.getUsage().getRelayBytesDown());
        assertEquals(1_000_000L - 1234L, dto.getUsage().getRelayBytesRemaining());

        verify(entitlementResolver).resolveFresh(USER_ID);
    }

    @Test
    @DisplayName("usage_counters 无当前周期行 → relayBytesDown=0，剩余=满额")
    void noUsageRowMeansZero() {
        PlanLimits limits = plan(1000L, 2000L, 10L);
        when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(new Entitlements("free", limits, null));
        when(planCatalog.name("free")).thenReturn("Free");
        when(workspaceService.getQuota(eq(PERSONAL_ID), eq(USER_ID), any(Entitlements.class)))
                .thenReturn(quota(0L, 2000L, 0L));
        when(usageService.currentMonthDown(USER_ID)).thenReturn(0L);

        EntitlementMeDTO dto = service.me(USER_ID);

        assertEquals(0L, dto.getUsage().getRelayBytesDown());
        assertEquals(1000L, dto.getUsage().getRelayBytesRemaining());
        assertFalse(dto.isThrottled());
    }

    @Test
    @DisplayName("达到额度：isThrottled / isStorageFull = true，剩余量夹到 0（不为负）")
    void exhaustedTrafficAndFullStorage() {
        PlanLimits limits = plan(100L, 200L, 10L);
        when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(new Entitlements("free", limits, null));
        when(planCatalog.name("free")).thenReturn("Free");
        when(workspaceService.getQuota(eq(PERSONAL_ID), eq(USER_ID), any(Entitlements.class)))
                .thenReturn(quota(200L, 200L, 9L));
        when(usageService.currentMonthDown(USER_ID)).thenReturn(150L);

        EntitlementMeDTO dto = service.me(USER_ID);

        assertTrue(dto.isThrottled());
        assertTrue(dto.isStorageFull());
        assertEquals(0L, dto.getUsage().getRelayBytesRemaining());
        assertEquals(0L, dto.getUsage().getStorageBytesRemaining());
        assertEquals(150L, dto.getUsage().getRelayBytesDown());
        assertEquals(200L, dto.getUsage().getStorageBytes());
        assertEquals(9L, dto.getUsage().getFileCount());
    }

    @Test
    @DisplayName("档位过期超过宽限期：resolver 回落 free，本接口只透传（不再读 plans）")
    void expiredPlanFallsBackToFree() {
        // 过期回落逻辑在 EntitlementResolver（已有专属单测）；此处验证本服务只使用 resolver 结果，
        // 不会因为 users.plan_code 仍是旧档位而展示旧额度。
        when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(Entitlements.free());
        when(workspaceService.getQuota(eq(PERSONAL_ID), eq(USER_ID), any(Entitlements.class)))
                .thenReturn(quota(0L, 0L, 0L));
        when(usageService.currentMonthDown(USER_ID)).thenReturn(0L);

        EntitlementMeDTO dto = service.me(USER_ID);

        assertEquals("free", dto.getPlanCode());
        assertEquals(PlanLimits.FREE_DEFAULTS.relayBytesMonth(), dto.getLimits().getRelayBytesMonth());
        assertEquals(PlanLimits.FREE_DEFAULTS.storageBytes(), dto.getLimits().getStorageBytes());
        assertEquals("free", dto.getPlanName(), "PlanCatalog 无此名称时回退 planCode");
        assertEquals("active", dto.getStatus());
        assertNull(dto.getExpiresAt());
    }

    @Test
    @DisplayName("已到期但仍在宽限期内：status=grace，额度仍按原档位展示")
    void withinGraceReportsGraceStatus() {
        PlanLimits limits = plan(5000L, 6000L, 10L);
        when(entitlementResolver.resolveFresh(USER_ID))
                .thenReturn(new Entitlements("plus", limits, OffsetDateTime.now().minusDays(1)));
        when(planCatalog.name("plus")).thenReturn("Plus");
        when(workspaceService.getQuota(eq(PERSONAL_ID), eq(USER_ID), any(Entitlements.class)))
                .thenReturn(quota(0L, 6000L, 0L));
        when(usageService.currentMonthDown(USER_ID)).thenReturn(0L);

        EntitlementMeDTO dto = service.me(USER_ID);

        assertEquals("grace", dto.getStatus());
        assertEquals("plus", dto.getPlanCode());
        assertEquals(5000L, dto.getLimits().getRelayBytesMonth());
    }

    @Test
    @DisplayName("task-115：limits 含 shareMaxDownloads/shareMaxLinks/trashRetentionDays，按档位取值，0 原样透出")
    void limitsExposeShareAndTrashFieldsAcrossTiers() {
        record Case(String planCode, int trashDays, int shareMaxDownloads, int shareMaxLinks) { }
        List<Case> cases = List.of(
                new Case("free", 7, 30, 5),
                new Case("plus", 30, 200, 20),
                new Case("team", 90, 1000, 50));

        for (Case c : cases) {
            PlanLimits limits = new PlanLimits(10L, 20L, 30L, 2, 1, 3, 4L, 7, 3,
                    c.shareMaxDownloads(), c.shareMaxLinks(), c.trashDays());
            when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(new Entitlements(c.planCode(), limits, null));
            when(workspaceService.getQuota(eq(PERSONAL_ID), eq(USER_ID), any(Entitlements.class)))
                    .thenReturn(quota(0L, 20L, 0L));
            when(usageService.currentMonthDown(USER_ID)).thenReturn(0L);

            EntitlementMeDTO dto = service.me(USER_ID);

            assertEquals(c.planCode(), dto.getPlanCode());
            assertEquals(c.trashDays(), dto.getLimits().getTrashRetentionDays(), c.planCode());
            assertEquals(c.shareMaxDownloads(), dto.getLimits().getShareMaxDownloads(), c.planCode());
            assertEquals(c.shareMaxLinks(), dto.getLimits().getShareMaxLinks(), c.planCode());
            service.invalidate(USER_ID);
        }
    }

    @Test
    @DisplayName("task-115：trashRetentionDays=0（不限期）原样透出，不被夹成默认值")
    void zeroTrashRetentionIsPassedThrough() {
        PlanLimits limits = new PlanLimits(10L, 20L, 30L, 2, 1, 3, 4L, 7, 3, 30, 5, 0);
        when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(new Entitlements("plus", limits, null));
        when(workspaceService.getQuota(eq(PERSONAL_ID), eq(USER_ID), any(Entitlements.class)))
                .thenReturn(quota(0L, 20L, 0L));
        when(usageService.currentMonthDown(USER_ID)).thenReturn(0L);

        EntitlementMeDTO dto = service.me(USER_ID);

        assertEquals(0, dto.getLimits().getTrashRetentionDays());
    }

    // ---------------------------------------------------------------- task-30 缓存

    @Test
    @DisplayName("缓存命中：第二次调用不再解析档位/查个人空间/查用量，直接复用同一 DTO")
    void cacheHitSkipsAllQueries() {
        when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(Entitlements.free());
        when(planCatalog.name("free")).thenReturn("Free");
        when(workspaceService.getQuota(eq(PERSONAL_ID), eq(USER_ID), any(Entitlements.class)))
                .thenReturn(quota(10L, 5368709120L, 2L));
        when(usageService.currentMonthDown(USER_ID)).thenReturn(1024L);

        EntitlementMeDTO first = service.me(USER_ID);
        EntitlementMeDTO second = service.me(USER_ID);

        assertSame(first, second, "缓存命中应返回缓存中的同一实例");
        verify(entitlementResolver, times(1)).resolveFresh(USER_ID);
        verify(personalSpaceService, times(1)).resolveWorkspaceId(USER_ID);
        verify(workspaceService, times(1)).getQuota(eq(PERSONAL_ID), eq(USER_ID), any(Entitlements.class));
        verify(usageService, times(1)).currentMonthDown(USER_ID);
    }

    @Test
    @DisplayName("TTL 到期：时钟推进超过 15s 后重新加载（额度变更可见）")
    void ttlExpiryTriggersRecompute() {
        when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(Entitlements.free());
        when(workspaceService.getQuota(eq(PERSONAL_ID), eq(USER_ID), any(Entitlements.class)))
                .thenReturn(quota(0L, 5368709120L, 0L));
        when(usageService.currentMonthDown(USER_ID)).thenReturn(0L);

        service.me(USER_ID);
        clock.addAndGet(TTL_MS + 1);
        service.me(USER_ID);

        verify(entitlementResolver, times(2)).resolveFresh(USER_ID);
        verify(workspaceService, times(2)).getQuota(eq(PERSONAL_ID), eq(USER_ID), any(Entitlements.class));
    }

    @Test
    @DisplayName("主动失效：invalidate(userId) 后下一次调用重新加载（管理端改档/兑换场景）")
    void invalidateForcesRecompute() {
        when(entitlementResolver.resolveFresh(USER_ID)).thenReturn(Entitlements.free());
        when(workspaceService.getQuota(eq(PERSONAL_ID), eq(USER_ID), any(Entitlements.class)))
                .thenReturn(quota(0L, 5368709120L, 0L));
        when(usageService.currentMonthDown(USER_ID)).thenReturn(0L);

        service.me(USER_ID);
        service.invalidate(USER_ID);
        service.me(USER_ID);

        verify(entitlementResolver, times(2)).resolveFresh(USER_ID);
    }
}
