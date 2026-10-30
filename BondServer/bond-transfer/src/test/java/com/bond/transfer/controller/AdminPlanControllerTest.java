package com.bond.transfer.controller;

import com.bond.common.dto.AdminPlanDTO;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.GlobalExceptionHandler;
import com.bond.transfer.entitlement.PlanAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * task-112：管理端档位参数接口的契约测试（standalone MockMvc，不连库）。
 */
@ExtendWith(MockitoExtension.class)
class AdminPlanControllerTest {

    @Mock
    private PlanAdminService planAdminService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminPlanController(planAdminService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/admin/plans 返回三档位（含停用），limits 数值不被字符串化")
    void listReturnsAllPlansWithNumericLimits() throws Exception {
        when(planAdminService.list()).thenReturn(List.of(
                new AdminPlanDTO("free", "免费版", 0, "month", true,
                        Map.of("storage_bytes", 5368709120L, "trash_retention_days", 7)),
                new AdminPlanDTO("plus", "Plus", 1999, "month", true,
                        Map.of("storage_bytes", 214748364800L, "trash_retention_days", 30)),
                new AdminPlanDTO("team", "团队版", 4999, "month", false,
                        Map.of("trash_retention_days", 90))));

        mockMvc.perform(get("/api/admin/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].code").value("free"))
                .andExpect(jsonPath("$.data[0].limits.storage_bytes").value(5368709120L))
                .andExpect(jsonPath("$.data[1].priceCents").value(1999))
                .andExpect(jsonPath("$.data[2].isActive").value(false))
                .andExpect(jsonPath("$.data[2].limits.trash_retention_days").value(90));
    }

    @Test
    @DisplayName("PUT 接收扁平 limits body（不套 limits 外壳），返回更新后档位")
    void updateAcceptsFlatLimitsBodyAndReturnsPlan() throws Exception {
        when(planAdminService.update(eq("plus"), anyMap(), anyLong(), anyString()))
                .thenReturn(new AdminPlanDTO("plus", "Plus", 1999, "month", true,
                        Map.of("trash_retention_days", 1, "storage_bytes", 214748364800L)));

        mockMvc.perform(put("/api/admin/plans/plus")
                        .header("X-User-Id", "9")
                        .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trash_retention_days\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.limits.trash_retention_days").value(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> ip = ArgumentCaptor.forClass(String.class);
        verify(planAdminService).update(eq("plus"), patch.capture(), eq(9L), ip.capture());
        assertEquals(1, ((Number) patch.getValue().get("trash_retention_days")).intValue());
        assertEquals("203.0.113.7", ip.getValue());
    }

    @Test
    @DisplayName("null 值透传为「删键」：{\"grace_days\":null} 不改写语义")
    void updatePassesNullThroughForKeyDeletion() throws Exception {
        when(planAdminService.update(eq("plus"), anyMap(), anyLong(), anyString()))
                .thenReturn(new AdminPlanDTO("plus", "Plus", 1999, "month", true, Map.of()));

        mockMvc.perform(put("/api/admin/plans/plus")
                        .header("X-User-Id", "9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grace_days\":null}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(planAdminService).update(eq("plus"), patch.capture(), eq(9L), anyString());
        Map<String, Object> captured = new LinkedHashMap<>(patch.getValue());
        assertEquals(true, captured.containsKey("grace_days"));
        assertEquals(null, captured.get("grace_days"));
    }

    @Test
    @DisplayName("空 body / 空对象：合法 partial（无需修改），返回 200")
    void emptyBodyIsValidNoOp() throws Exception {
        // 无 body 时 @RequestBody(required=false) 传 null，anyMap() 不匹配 null，故这里用 any()
        when(planAdminService.update(eq("plus"), any(), anyLong(), anyString()))
                .thenReturn(new AdminPlanDTO("plus", "Plus", 1999, "month", true, Map.of()));

        mockMvc.perform(put("/api/admin/plans/plus").header("X-User-Id", "9"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/admin/plans/plus")
                        .header("X-User-Id", "9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("非法键 → 400，body message 带键名")
    void invalidKeyReturns400WithKeyName() throws Exception {
        when(planAdminService.update(eq("plus"), anyMap(), anyLong(), anyString()))
                .thenThrow(new BusinessException(400, "不支持的 limits 键：foo"));

        mockMvc.perform(put("/api/admin/plans/plus")
                        .header("X-User-Id", "9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"foo\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("foo")));
    }

    @Test
    @DisplayName("档位不存在 → 404")
    void unknownPlanReturns404() throws Exception {
        when(planAdminService.update(eq("nope"), anyMap(), anyLong(), anyString()))
                .thenThrow(new BusinessException(404, "档位不存在：nope"));

        mockMvc.perform(put("/api/admin/plans/nope")
                        .header("X-User-Id", "9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grace_days\":1}"))
                .andExpect(status().isNotFound());
    }
}
