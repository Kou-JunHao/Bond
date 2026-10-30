package com.bond.transfer.entitlement;

import com.bond.common.dto.AdminPlanDTO;
import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.EntitlementMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.service.AuditService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * task-112：档位参数运营后台的单测。
 *
 * <p>覆盖：三档位（含停用）返回、partial update 保其它键、null 删键、白名单/类型/边界校验、
 * 无变化幂等、写库后立即失效 plan 缓存并写审计 diff、写库失败不审计。</p>
 */
@ExtendWith(MockitoExtension.class)
class PlanAdminServiceTest {

    private static final String FREE_LIMITS = "{\"relay_bytes_month\":10737418240,\"storage_bytes\":5368709120,"
            + "\"max_file_size\":2147483648,\"concurrent_transfers\":2,\"max_workspaces\":1,"
            + "\"max_workspace_members\":3,\"relay_down_bytes_per_sec\":5242880,\"share_expires_days\":7,"
            + "\"grace_days\":3,\"share_max_downloads\":30,\"share_max_links\":5,\"trash_retention_days\":7}";
    private static final String PLUS_LIMITS = "{\"relay_bytes_month\":214748364800,\"storage_bytes\":214748364800,"
            + "\"max_file_size\":10737418240,\"concurrent_transfers\":4,\"max_workspaces\":5,"
            + "\"max_workspace_members\":10,\"relay_down_bytes_per_sec\":20971520,\"share_expires_days\":30,"
            + "\"grace_days\":3,\"share_max_downloads\":200,\"share_max_links\":20,\"trash_retention_days\":30}";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private EntitlementMapper entitlementMapper;
    @Mock
    private EntitlementResolver entitlementResolver;
    @Mock
    private AuditService auditService;
    @Mock
    private UserMapper userMapper;

    private PlanAdminService service;

    @BeforeEach
    void setUp() {
        service = new PlanAdminService(entitlementMapper, entitlementResolver, auditService,
                userMapper, objectMapper);
    }

    @Test
    @DisplayName("GET 返回三个档位全部（含 isActive=false），limits 保持 JSON 数值类型")
    void listReturnsAllPlansIncludingInactive() {
        when(entitlementMapper.selectAllPlans()).thenReturn(List.of(
                row("free", "免费版", 0, "month", true, FREE_LIMITS),
                row("plus", "Plus", 1999, "month", true, PLUS_LIMITS),
                row("team", "团队版", 4999, "month", false, PLUS_LIMITS)));

        List<AdminPlanDTO> plans = service.list();

        assertEquals(3, plans.size());
        assertEquals("free", plans.get(0).code());
        assertEquals("Plus", plans.get(1).name());
        assertEquals(1999, plans.get(1).priceCents());
        assertEquals("month", plans.get(1).period());
        assertTrue(plans.get(1).isActive());
        assertFalse(plans.get(2).isActive());
        assertEquals(5368709120L, num(plans.get(0).limits().get("storage_bytes")));
        assertEquals(7, num(plans.get(0).limits().get("trash_retention_days")));
    }

    @Test
    @DisplayName("partial update：只改提供键、保留其它键，写库后立即失效该 plan 缓存并写 before/after 审计")
    void partialUpdateKeepsOtherKeysAndInvalidatesPlanCache() throws Exception {
        when(entitlementMapper.selectPlan("plus")).thenReturn(row("plus", "Plus", 1999, "month", true, PLUS_LIMITS));
        when(userMapper.selectById(9L)).thenReturn(admin(9L, "管理员"));

        AdminPlanDTO dto = service.update("PLUS ", patch(PlanAdminService.KEY_TRASH_RETENTION_DAYS, 1), 9L, "1.2.3.4");

        assertEquals(1, num(dto.limits().get("trash_retention_days")));

        ArgumentCaptor<String> limitsJson = ArgumentCaptor.forClass(String.class);
        verify(entitlementMapper).updatePlanLimits(eq("plus"), limitsJson.capture());
        Map<String, Object> written = read(limitsJson.getValue());
        assertEquals(1, num(written.get("trash_retention_days")));
        assertEquals(214748364800L, num(written.get("storage_bytes")));
        assertEquals(30, num(written.get("share_expires_days")));

        verify(entitlementResolver).invalidatePlan("plus");

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(9L), eq("管理员"), eq("update-plan"), eq("plan"), isNull(),
                detail.capture(), eq("1.2.3.4"));
        Map<String, Object> audit = read(detail.getValue());
        assertEquals("plus", audit.get("code"));
        assertEquals(30, num(((Map<String, Object>) audit.get("before")).get("trash_retention_days")));
        assertEquals(1, num(((Map<String, Object>) audit.get("after")).get("trash_retention_days")));
    }

    @Test
    @DisplayName("null 表示删除该键：库里不再有 grace_days，审计记录 before=3 / after=null")
    void nullDeletesKey() throws Exception {
        when(entitlementMapper.selectPlan("plus")).thenReturn(row("plus", "Plus", 1999, "month", true, PLUS_LIMITS));
        when(userMapper.selectById(9L)).thenReturn(admin(9L, "管理员"));

        service.update("plus", patch("grace_days", null), 9L, "1.2.3.4");

        ArgumentCaptor<String> limitsJson = ArgumentCaptor.forClass(String.class);
        verify(entitlementMapper).updatePlanLimits(eq("plus"), limitsJson.capture());
        assertFalse(read(limitsJson.getValue()).containsKey("grace_days"));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(any(), anyString(), eq("update-plan"), eq("plan"), isNull(),
                detail.capture(), anyString());
        Map<String, Object> audit = read(detail.getValue());
        Map<String, Object> after = (Map<String, Object>) audit.get("after");
        assertTrue(after.containsKey("grace_days"));
        assertNull(after.get("grace_days"));
    }

    @Test
    @DisplayName("未知键 → 400 带键名，且不写库、不失效缓存、不审计")
    void rejectsUnknownKeyBeforeAnyWrite() {
        when(entitlementMapper.selectPlan("plus")).thenReturn(row("plus", "Plus", 1999, "month", true, PLUS_LIMITS));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.update("plus", patch("foo", 1), 9L, "1.2.3.4"));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("foo"));
        verify(entitlementMapper, never()).updatePlanLimits(anyString(), anyString());
        verify(entitlementResolver, never()).invalidatePlan(anyString());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("类型非法（字符串/小数/布尔）→ 400，消息带键名 + 期望类型 + 实际值")
    void rejectsWrongTypes() {
        when(entitlementMapper.selectPlan("plus")).thenReturn(row("plus", "Plus", 1999, "month", true, PLUS_LIMITS));

        List<Map<String, Object>> cases = List.of(
                patch("storage_bytes", "1024"),
                patch("grace_days", 1.5),
                patch("share_max_links", true));

        for (Map<String, Object> body : cases) {
            String key = body.keySet().iterator().next();
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.update("plus", body, 9L, "1.2.3.4"));
            assertEquals(400, error.getCode());
            assertTrue(error.getMessage().contains(key), "message must mention key: " + error.getMessage());
            assertTrue(error.getMessage().contains("期望"), "message must state expected type: " + error.getMessage());
        }
        verify(entitlementMapper, never()).updatePlanLimits(anyString(), anyString());
    }

    @Test
    @DisplayName("越界：trash 不在 [0,3650] / 存储类 0 → 400；中途非法整体不写库")
    void rejectsOutOfBounds() {
        when(entitlementMapper.selectPlan("plus")).thenReturn(row("plus", "Plus", 1999, "month", true, PLUS_LIMITS));

        for (Object bad : new Object[] {-1, 3651}) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.update("plus", patch(PlanAdminService.KEY_TRASH_RETENTION_DAYS, bad), 9L, "1.2.3.4"));
            assertEquals(400, error.getCode());
            assertTrue(error.getMessage().contains(PlanAdminService.KEY_TRASH_RETENTION_DAYS));
        }
        assertThrows(BusinessException.class,
                () -> service.update("plus", patch("storage_bytes", 0), 9L, "1.2.3.4"));

        // 第一个键合法、第二个非法：整体失败，不产生部分写入
        Map<String, Object> mixed = patch(PlanAdminService.KEY_TRASH_RETENTION_DAYS, 1);
        mixed.put("storage_bytes", -5);
        assertThrows(BusinessException.class, () -> service.update("plus", mixed, 9L, "1.2.3.4"));

        verify(entitlementMapper, never()).updatePlanLimits(anyString(), anyString());
        verify(entitlementResolver, never()).invalidatePlan(anyString());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("0 合法：trash=0 不限期、relay_down=0 不限速、share_expires=0 永久")
    void acceptsZeroForUnlimitedKeys() throws Exception {
        when(entitlementMapper.selectPlan("plus")).thenReturn(row("plus", "Plus", 1999, "month", true, PLUS_LIMITS));
        when(userMapper.selectById(9L)).thenReturn(admin(9L, "管理员"));

        Map<String, Object> patch = patch(PlanAdminService.KEY_TRASH_RETENTION_DAYS, 0);
        patch.put("relay_down_bytes_per_sec", 0);
        patch.put("share_expires_days", 0);

        service.update("plus", patch, 9L, "1.2.3.4");

        ArgumentCaptor<String> limitsJson = ArgumentCaptor.forClass(String.class);
        verify(entitlementMapper).updatePlanLimits(eq("plus"), limitsJson.capture());
        Map<String, Object> written = read(limitsJson.getValue());
        assertEquals(0, num(written.get(PlanAdminService.KEY_TRASH_RETENTION_DAYS)));
        assertEquals(0, num(written.get("relay_down_bytes_per_sec")));
        assertEquals(0, num(written.get("share_expires_days")));
    }

    @Test
    @DisplayName("值未变化 → 幂等返回 200，不写库、不审计、不失效缓存")
    void noChangeIsIdempotent() {
        when(entitlementMapper.selectPlan("plus")).thenReturn(row("plus", "Plus", 1999, "month", true, PLUS_LIMITS));

        AdminPlanDTO dto = service.update("plus",
                patch(PlanAdminService.KEY_TRASH_RETENTION_DAYS, 30), 9L, "1.2.3.4");

        assertEquals(30, num(dto.limits().get(PlanAdminService.KEY_TRASH_RETENTION_DAYS)));
        verify(entitlementMapper, never()).updatePlanLimits(anyString(), anyString());
        verifyNoInteractions(entitlementResolver, auditService);
    }

    @Test
    @DisplayName("档位不存在 → 404")
    void unknownPlanReturns404() {
        when(entitlementMapper.selectPlan("nope")).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.update("nope", patch("grace_days", 1), 9L, "1.2.3.4"));

        assertEquals(404, error.getCode());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("写库失败：异常上抛（事务回滚），不写审计、不失效缓存")
    void writeFailurePropagatesWithoutAudit() {
        when(entitlementMapper.selectPlan("plus")).thenReturn(row("plus", "Plus", 1999, "month", true, PLUS_LIMITS));
        when(entitlementMapper.updatePlanLimits(anyString(), anyString()))
                .thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class,
                () -> service.update("plus", patch("grace_days", 5), 9L, "1.2.3.4"));

        verify(entitlementResolver, never()).invalidatePlan(anyString());
        verifyNoInteractions(auditService);
    }

    private static Map<String, Object> row(String code, String name, int priceCents, String period,
                                           boolean active, String limitsJson) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", code);
        row.put("name", name);
        row.put("priceCents", priceCents);
        row.put("period", period);
        row.put("isActive", active);
        row.put("limits", limitsJson);
        return row;
    }

    private static Map<String, Object> patch(String key, Object value) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(key, value);
        return patch;
    }

    private static User admin(Long id, String nickname) {
        User user = new User();
        user.setId(id);
        user.setNickname(nickname);
        user.setUsername("admin");
        return user;
    }

    private Map<String, Object> read(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private static long num(Object value) {
        return ((Number) value).longValue();
    }
}
