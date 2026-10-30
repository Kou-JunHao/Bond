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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-112：改档位参数后必须立即失效相关用户缓存，且只失效该 planCode 的用户。
 */
@ExtendWith(MockitoExtension.class)
class EntitlementResolverPlanCacheTest {

    private static final String FREE_LIMITS = "{\"relay_bytes_month\":10737418240,\"storage_bytes\":5368709120,"
            + "\"max_file_size\":2147483648,\"concurrent_transfers\":2,\"max_workspaces\":1,"
            + "\"max_workspace_members\":3,\"relay_down_bytes_per_sec\":5242880,\"share_expires_days\":7,"
            + "\"grace_days\":3,\"share_max_downloads\":30,\"share_max_links\":5,\"trash_retention_days\":7}";
    private static final String PLUS_LIMITS = "{\"relay_bytes_month\":214748364800,\"storage_bytes\":214748364800,"
            + "\"max_file_size\":10737418240,\"concurrent_transfers\":4,\"max_workspaces\":5,"
            + "\"max_workspace_members\":10,\"relay_down_bytes_per_sec\":20971520,\"share_expires_days\":30,"
            + "\"grace_days\":3,\"share_max_downloads\":200,\"share_max_links\":20,\"trash_retention_days\":30}";
    private static final String PLUS_LIMITS_AFTER_UPDATE = "{\"relay_bytes_month\":214748364800,"
            + "\"storage_bytes\":999,\"max_file_size\":10737418240,\"concurrent_transfers\":4,"
            + "\"max_workspaces\":5,\"max_workspace_members\":10,\"relay_down_bytes_per_sec\":20971520,"
            + "\"share_expires_days\":30,\"grace_days\":3,\"share_max_downloads\":200,"
            + "\"share_max_links\":20,\"trash_retention_days\":1}";

    @Mock
    private EntitlementMapper entitlementMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ConfigService configService;

    private EntitlementResolver resolver;

    @BeforeEach
    void setUp() {
        when(configService.getLong("entitlement.cache_seconds", 60L)).thenReturn(60L);
        resolver = new EntitlementResolver(entitlementMapper, userMapper, new ObjectMapper(), configService);
        when(entitlementMapper.selectLatestSubscription(anyLong())).thenReturn(null);
        when(entitlementMapper.selectActiveOverrides(anyLong())).thenReturn(List.of());
    }

    @Test
    @DisplayName("invalidatePlan 只让该档位用户失效：同档位重查、其它档位仍命中缓存")
    void invalidatePlanRefreshesOnlyMatchingPlanCode() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "plus"));
        when(userMapper.selectById(2L)).thenReturn(user(2L, "free"));
        when(entitlementMapper.selectPlanLimits("plus")).thenReturn(PLUS_LIMITS);
        when(entitlementMapper.selectPlanLimits("free")).thenReturn(FREE_LIMITS);

        assertEquals(214748364800L, resolver.resolve(1L).limits().storageBytes());
        assertEquals(5368709120L, resolver.resolve(2L).limits().storageBytes());

        // 模拟运营后台改了 plus 档位但不失效：仍命中旧缓存
        when(entitlementMapper.selectPlanLimits("plus")).thenReturn(PLUS_LIMITS_AFTER_UPDATE);
        assertEquals(214748364800L, resolver.resolve(1L).limits().storageBytes());

        resolver.invalidatePlan("plus");

        assertEquals(999L, resolver.resolve(1L).limits().storageBytes());
        // free 用户缓存未被波及：不再查库
        assertEquals(5368709120L, resolver.resolve(2L).limits().storageBytes());
        verify(userMapper, times(1)).selectById(2L);
    }

    @Test
    @DisplayName("invalidatePlan 对不存在的档位/空缓存是安全的 no-op")
    void invalidateUnknownPlanIsNoOp() {
        when(userMapper.selectById(3L)).thenReturn(user(3L, "free"));
        when(entitlementMapper.selectPlanLimits("free")).thenReturn(FREE_LIMITS);
        assertEquals(7, resolver.resolve(3L).limits().trashRetentionDays());

        resolver.invalidatePlan("plus");

        assertEquals(7, resolver.resolve(3L).limits().trashRetentionDays());
        verify(userMapper, times(1)).selectById(3L);
    }

    private static User user(Long id, String planCode) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setPlanCode(planCode);
        return user;
    }
}
