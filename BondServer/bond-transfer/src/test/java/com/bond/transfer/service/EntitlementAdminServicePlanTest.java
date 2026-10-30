package com.bond.transfer.service;

import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.entitlement.EntitlementMeService;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.PlanCatalog;
import com.bond.transfer.mapper.EntitlementMapper;
import com.bond.transfer.mapper.RedeemCodeMapper;
import com.bond.transfer.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-109：调档两个后端缺陷的回归测试。
 *
 * <ul>
 *   <li>调回 free 必须把 {@code users.plan_expires_at} 显式置 NULL（MP updateById 会跳过 null）；</li>
 *   <li>连续调档不得裸 500：旧订阅先 expired，EXCLUDE 冲突映射 409；</li>
 *   <li>整个 setUserPlan 必须带 @Transactional（失败整体回滚）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class EntitlementAdminServicePlanTest {

    private static final long ADMIN_ID = 1L;
    private static final long USER_ID = 7L;

    @Mock private EntitlementMapper entitlementMapper;
    @Mock private RedeemCodeMapper redeemCodeMapper;
    @Mock private UserMapper userMapper;
    @Mock private EntitlementResolver entitlementResolver;
    @Mock private EntitlementMeService entitlementMeService;
    @Mock private PlanCatalog planCatalog;
    @Mock private QuotaService quotaService;
    @Mock private ObjectMapper objectMapper;

    private EntitlementAdminService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new EntitlementAdminService(entitlementMapper, redeemCodeMapper, userMapper,
                entitlementResolver, entitlementMeService, planCatalog, quotaService, objectMapper);
        lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
    }

    private void existingUser() {
        User user = new User();
        user.setId(USER_ID);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
    }

    @Test
    @DisplayName("调回 free：显式 UPDATE plan_expires_at=NULL，且不写新订阅")
    void downgradeToFreeClearsPlanExpiresAt() {
        existingUser();

        service.setUserPlan(ADMIN_ID, USER_ID, "free", null, null);

        // 关键断言：第三个参数必须是 null —— MP updateById 无法表达“置空”
        verify(entitlementMapper).updateUserPlan(USER_ID, "free", null);
        // 旧 active 订阅先失效（V22 后 no_overlap 只约束 active）
        verify(entitlementMapper).expireActiveSubscriptions(USER_ID);
        verify(entitlementMapper, never()).insertSubscription(
                anyLong(), anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("调回 free 时即使传了 months/expiresAt，也强制写 NULL")
    void freeIgnoresProvidedExpiry() {
        existingUser();

        service.setUserPlan(ADMIN_ID, USER_ID, "free", 12, OffsetDateTime.now().plusYears(1));

        verify(entitlementMapper).updateUserPlan(USER_ID, "free", null);
    }

    @Test
    @DisplayName("付费档：先失效旧订阅，再插新订阅，最后写 users（顺序固定）")
    void paidPlanWritesSubscriptionThenUsers() {
        existingUser();
        when(entitlementMapper.selectPlanLimits("plus")).thenReturn("{}");

        service.setUserPlan(ADMIN_ID, USER_ID, "plus", 2, null);

        var inOrder = org.mockito.Mockito.inOrder(entitlementMapper);
        inOrder.verify(entitlementMapper).expireActiveSubscriptions(USER_ID);
        inOrder.verify(entitlementMapper).insertSubscription(
                eq(USER_ID), eq("plus"), eq("active"), any(), any(), eq("manual"), eq("admin:" + ADMIN_ID));
        ArgumentCaptor<OffsetDateTime> expiry = ArgumentCaptor.forClass(OffsetDateTime.class);
        inOrder.verify(entitlementMapper).updateUserPlan(eq(USER_ID), eq("plus"), expiry.capture());
        assertNotNull(expiry.getValue(), "付费档必须有到期时间");
    }

    @Test
    @DisplayName("连续两次调档不冲突：每次先 expired 旧订阅，再插新订阅")
    void consecutivePlanChangesDoNotConflict() {
        existingUser();
        when(entitlementMapper.selectPlanLimits("plus")).thenReturn("{}");
        when(entitlementMapper.selectPlanLimits("team")).thenReturn("{}");

        service.setUserPlan(ADMIN_ID, USER_ID, "plus", 1, null);
        service.setUserPlan(ADMIN_ID, USER_ID, "team", 1, null);

        verify(entitlementMapper, times(2)).expireActiveSubscriptions(USER_ID);
        verify(entitlementMapper, times(2)).insertSubscription(
                eq(USER_ID), anyString(), eq("active"), any(), any(), eq("manual"), eq("admin:" + ADMIN_ID));
        verify(entitlementMapper, times(2)).updateUserPlan(eq(USER_ID), anyString(), any());
    }

    @Test
    @DisplayName("EXCLUDE 冲突（no_overlap）→ 409 业务异常，且不回写 users/不刷新配额")
    void exclusionConflictMapsTo409() {
        existingUser();
        when(entitlementMapper.selectPlanLimits("plus")).thenReturn("{}");
        DataIntegrityViolationException conflict = new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: conflicting key value violates exclusion constraint \"no_overlap\""));
        doThrow(conflict).when(entitlementMapper).insertSubscription(
                eq(USER_ID), eq("plus"), eq("active"), any(), any(), eq("manual"), eq("admin:" + ADMIN_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setUserPlan(ADMIN_ID, USER_ID, "plus", 1, null));

        assertEquals(409, ex.getCode());
        verify(entitlementMapper, never()).updateUserPlan(anyLong(), anyString(), any());
        verify(quotaService, never()).refreshLimitsForUser(anyLong());
    }

    @Test
    @DisplayName("未知档位：400 且不产生任何副作用")
    void unknownPlanRejectedWithoutSideEffects() {
        existingUser();
        when(entitlementMapper.selectPlanLimits("bogus")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setUserPlan(ADMIN_ID, USER_ID, "bogus", 1, null));

        assertEquals(400, ex.getCode());
        verify(entitlementMapper, never()).expireActiveSubscriptions(anyLong());
        verify(entitlementMapper, never()).updateUserPlan(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("setUserPlan 必须带 @Transactional（冲突时整体回滚）")
    void setUserPlanIsTransactional() throws NoSuchMethodException {
        var method = EntitlementAdminService.class.getMethod(
                "setUserPlan", Long.class, Long.class, String.class, Integer.class, OffsetDateTime.class);
        assertNotNull(method.getAnnotation(Transactional.class),
                "调档是先失效旧订阅再插新订阅的多写操作，必须整体回滚");
    }
}
