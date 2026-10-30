package com.bond.transfer.entitlement;

import com.bond.common.entity.User;
import com.bond.transfer.mapper.EntitlementMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.service.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-7 验收要求的单测：EntitlementResolver 的「默认 / 覆盖 / 过期」三态（外加 grace 宽限）。
 */
@ExtendWith(MockitoExtension.class)
class EntitlementResolverTest {

    private static final String FREE_JSON = "{\"relay_bytes_month\":10737418240,\"storage_bytes\":5368709120,"
            + "\"max_file_size\":2147483648,\"concurrent_transfers\":2,\"max_workspaces\":1,"
            + "\"max_workspace_members\":3,\"relay_down_bytes_per_sec\":5242880,"
            + "\"share_expires_days\":7,\"grace_days\":3}";
    private static final String PLUS_JSON = "{\"relay_bytes_month\":214748364800,\"storage_bytes\":214748364800,"
            + "\"max_file_size\":10737418240,\"concurrent_transfers\":4,\"max_workspaces\":5,"
            + "\"max_workspace_members\":10,\"relay_down_bytes_per_sec\":20971520,"
            + "\"share_expires_days\":30,\"grace_days\":3}";

    @Mock
    private EntitlementMapper entitlementMapper;
    @Mock
    private UserMapper userMapper;

    private EntitlementResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new EntitlementResolver(entitlementMapper, userMapper, new ObjectMapper());
        lenient().when(entitlementMapper.selectPlanLimits(eq("free"))).thenReturn(FREE_JSON);
        lenient().when(entitlementMapper.selectPlanLimits(eq("plus"))).thenReturn(PLUS_JSON);
        lenient().when(entitlementMapper.selectLatestSubscription(anyLong())).thenReturn(null);
        lenient().when(entitlementMapper.selectActiveOverrides(anyLong())).thenReturn(List.of());
    }

    @Test
    @DisplayName("默认态：无订阅、无到期 → free 档默认权益")
    void defaultFreePlan() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "free", null));

        Entitlements entitlements = resolver.resolve(7L);

        assertEquals("free", entitlements.planCode());
        assertEquals(PlanLimits.FREE_DEFAULTS.storageBytes(), entitlements.limits().storageBytes());
        assertEquals(PlanLimits.FREE_DEFAULTS.relayBytesMonth(), entitlements.limits().relayBytesMonth());
    }

    @Test
    @DisplayName("覆盖态：user_entitlements 优先于档位默认值（加量/开白）")
    void userOverrideWinsOverPlan() {
        when(userMapper.selectById(8L)).thenReturn(user(8L, "free", null));
        EntitlementRow row = new EntitlementRow();
        row.setKey("storage_bytes");
        row.setValue(50L * 1024 * 1024 * 1024);
        when(entitlementMapper.selectActiveOverrides(8L)).thenReturn(List.of(row));

        Entitlements entitlements = resolver.resolve(8L);

        assertEquals(50L * 1024 * 1024 * 1024, entitlements.limits().storageBytes());
        assertEquals(PlanLimits.FREE_DEFAULTS.relayBytesMonth(), entitlements.limits().relayBytesMonth());
    }

    @Test
    @DisplayName("过期态：plus 到期超过 grace 3 天 → 回退 free（数据不删，超额只读）")
    void expiredPlanFallsBackToFree() {
        when(userMapper.selectById(9L)).thenReturn(user(9L, "plus", OffsetDateTime.now().minusDays(30)));

        Entitlements entitlements = resolver.resolve(9L);

        assertEquals("free", entitlements.planCode());
        assertEquals(PlanLimits.FREE_DEFAULTS.storageBytes(), entitlements.limits().storageBytes());
    }

    @Test
    @DisplayName("grace 宽限期内：仍保留原档位权益")
    void planInGraceKeepsEntitlements() {
        when(userMapper.selectById(10L)).thenReturn(user(10L, "plus", OffsetDateTime.now().minusDays(1)));

        Entitlements entitlements = resolver.resolve(10L);

        assertEquals("plus", entitlements.planCode());
        assertTrue(entitlements.limits().storageBytes() > PlanLimits.FREE_DEFAULTS.storageBytes());
    }

    @Test
    @DisplayName("缓存可配：entitlement.cache_seconds=0 → 关闭缓存，每次实时解析")
    void cacheDisabledWhenConfiguredZero() {
        ConfigService config = mock(ConfigService.class);
        when(config.getLong("entitlement.cache_seconds", 60L)).thenReturn(0L);
        EntitlementResolver uncached = new EntitlementResolver(
                entitlementMapper, userMapper, new ObjectMapper(), config);
        when(userMapper.selectById(11L)).thenReturn(user(11L, "free", null));

        uncached.resolve(11L);
        uncached.resolve(11L);

        verify(userMapper, times(2)).selectById(11L);
    }

    @Test
    @DisplayName("缓存可配：TTL 内命中缓存不重复查库，invalidate 后重新解析")
    void cacheHitThenInvalidateRecomputes() {
        ConfigService config = mock(ConfigService.class);
        when(config.getLong("entitlement.cache_seconds", 60L)).thenReturn(30L);
        EntitlementResolver cached = new EntitlementResolver(
                entitlementMapper, userMapper, new ObjectMapper(), config);
        when(userMapper.selectById(12L)).thenReturn(user(12L, "free", null));

        cached.resolve(12L);
        cached.resolve(12L);
        verify(userMapper, times(1)).selectById(12L);

        cached.invalidate(12L);
        cached.resolve(12L);
        verify(userMapper, times(2)).selectById(12L);
    }

    private static User user(Long id, String planCode, OffsetDateTime expiresAt) {
        User user = new User();
        user.setId(id);
        user.setPlanCode(planCode);
        user.setPlanExpiresAt(expiresAt);
        return user;
    }
}
