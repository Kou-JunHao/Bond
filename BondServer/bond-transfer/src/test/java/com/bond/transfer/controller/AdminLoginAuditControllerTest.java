package com.bond.transfer.controller;

import com.bond.common.dto.AdminLoginBlockDTO;
import com.bond.common.dto.AdminLoginGuardDTO;
import com.bond.common.dto.AdminLoginLogDTO;
import com.bond.common.dto.PageResult;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.GlobalExceptionHandler;
import com.bond.transfer.service.AdminLoginAuditAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * task-144：管理端登录审计控制器（standalone MockMvc，不启动 Spring 容器）。
 */
class AdminLoginAuditControllerTest {

    private AdminLoginAuditAdminService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AdminLoginAuditAdminService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminLoginAuditController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static AdminLoginLogDTO logDto() {
        AdminLoginLogDTO dto = new AdminLoginLogDTO();
        dto.setId(1L);
        dto.setUsername("admin");
        dto.setIp("1.2.3.4");
        dto.setOutcome("bad_credentials");
        dto.setCreatedAtMillis(1_700_000_000_000L);
        return dto;
    }

    @Test
    @DisplayName("GET /api/admin/login-logs 返回分页信封")
    void logsReturnsPage() throws Exception {
        when(service.pageLogs(eq(2), eq(10), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(PageResult.of(List.of(logDto()), 1L, 2, 10));

        mockMvc.perform(get("/api/admin/login-logs").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].username").value("admin"));
    }

    @Test
    @DisplayName("GET /api/admin/login-logs 非法 outcome → 400 信封")
    void logsInvalidOutcomeReturns400() throws Exception {
        when(service.pageLogs(any(Integer.class), any(Integer.class), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(400, "outcome 非法：boom"));

        mockMvc.perform(get("/api/admin/login-logs").param("outcome", "boom"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("outcome 非法：boom"));
    }

    @Test
    @DisplayName("GET /api/admin/login-logs/stats 返回统计")
    void statsReturnsStats() throws Exception {
        com.bond.common.dto.AdminLoginLogStatsDTO stats = new com.bond.common.dto.AdminLoginLogStatsDTO();
        stats.setDays(7);
        stats.setTotal(10L);
        stats.setSuccess(7L);
        stats.setFailed(3L);
        when(service.stats(7)).thenReturn(stats);

        mockMvc.perform(get("/api/admin/login-logs/stats").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(7))
                .andExpect(jsonPath("$.data.success").value(7))
                .andExpect(jsonPath("$.data.failed").value(3));
    }

    @Test
    @DisplayName("GET /api/admin/login-blocks 支持 active 过滤")
    void blocksReturnsPage() throws Exception {
        AdminLoginBlockDTO dto = new AdminLoginBlockDTO();
        dto.setId(3L);
        dto.setScope("ip");
        dto.setValue("1.2.3.4");
        dto.setActive(true);
        dto.setRemainingSeconds(120L);
        when(service.pageBlocks(eq(true), eq(1), eq(20), isNull(), isNull()))
                .thenReturn(PageResult.of(List.of(dto), 1L, 1, 20));

        mockMvc.perform(get("/api/admin/login-blocks").param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].scope").value("ip"))
                .andExpect(jsonPath("$.data.items[0].active").value(true));
    }

    @Test
    @DisplayName("POST /api/admin/login-blocks/{id}/lift → 204 无正文")
    void liftReturnsNoContent() throws Exception {
        doNothing().when(service).lift(5L, 7L);

        mockMvc.perform(post("/api/admin/login-blocks/5/lift").header("X-User-Id", "7"))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Content-Type"));
    }

    @Test
    @DisplayName("POST lift 不存在 → 404 信封")
    void liftNotFoundReturns404() throws Exception {
        doThrow(new BusinessException(404, "封禁不存在或已解除"))
                .when(service).lift(anyLong(), anyLong());

        mockMvc.perform(post("/api/admin/login-blocks/9/lift").header("X-User-Id", "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("GET/PUT /api/admin/login-guard 均返回策略信封")
    void guardReadAndUpdate() throws Exception {
        AdminLoginGuardDTO dto = new AdminLoginGuardDTO();
        dto.setEnabled(true);
        dto.setFailWindowSeconds(600);
        dto.setFailThresholdIp(5);
        dto.setFailThresholdUser(10);
        dto.setBanSeconds(900);
        dto.setIpAllowlist(List.of("10.0.0.1"));
        when(service.guard()).thenReturn(dto);
        when(service.updateGuard(any(), eq(7L))).thenReturn(dto);

        mockMvc.perform(get("/api/admin/login-guard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failThresholdIp").value(5));

        mockMvc.perform(put("/api/admin/login-guard")
                        .header("X-User-Id", "7")
                        .contentType("application/json")
                        .content("{\"failThresholdIp\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ipAllowlist[0]").value("10.0.0.1"));
    }
}
