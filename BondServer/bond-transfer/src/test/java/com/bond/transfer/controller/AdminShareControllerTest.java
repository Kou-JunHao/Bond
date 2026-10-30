package com.bond.transfer.controller;

import com.bond.common.dto.AdminShareDTO;
import com.bond.common.dto.PageResult;
import com.bond.common.entity.User;
import com.bond.common.exception.GlobalExceptionHandler;
import com.bond.transfer.interceptor.AdminInterceptor;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.share.AdminShareService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** task-89：管理端分享治理接口测试（AdminInterceptor 真实生效）。 */
class AdminShareControllerTest {

    private static final Long ADMIN_ID = 9L;

    private AdminShareService service;
    private UserMapper userMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AdminShareService.class);
        userMapper = mock(UserMapper.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminShareController(service))
                .addInterceptors(new AdminInterceptor(userMapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET 列表：200 + {items,total,page,size}")
    void listReturnsPage() throws Exception {
        AdminShareDTO dto = new AdminShareDTO();
        dto.setId(42L);
        dto.setToken("abcdefghijklmnop");
        dto.setUrl("https://bond.example.com/s/abcdefghijklmnop");
        dto.setFileName("报告.pdf");
        dto.setOwnerUsername("alice");
        dto.setStatus(0);
        dto.setDownloadCount(2);
        when(userMapper.selectById(ADMIN_ID)).thenReturn(adminUser());
        when(service.list(1, 20, null, null, null))
                .thenReturn(PageResult.of(List.of(dto), 1L, 1, 20));

        mockMvc.perform(get("/api/admin/shares")
                        .header("X-User-Id", ADMIN_ID)
                        .param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items[0].token").value("abcdefghijklmnop"))
                .andExpect(jsonPath("$.data.items[0].url").value("https://bond.example.com/s/abcdefghijklmnop"));
    }

    @Test
    @DisplayName("非管理员访问列表 → 403（AdminInterceptor 语义）")
    void listRejectsNonAdmin() throws Exception {
        when(userMapper.selectById(ADMIN_ID)).thenReturn(plainUser());

        mockMvc.perform(get("/api/admin/shares").header("X-User-Id", ADMIN_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE 缺 X-User-Id → 401")
    void revokeWithoutActorHeaderReturns401() throws Exception {
        mockMvc.perform(delete("/api/admin/shares/42"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE 强制撤销 → 204（actor 透传给 service）")
    void revokeReturns204() throws Exception {
        when(userMapper.selectById(ADMIN_ID)).thenReturn(adminUser());

        mockMvc.perform(delete("/api/admin/shares/42").header("X-User-Id", ADMIN_ID))
                .andExpect(status().isNoContent());

        verify(service).revoke(eq(42L), eq(ADMIN_ID), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("DELETE 已撤销 → 404（业务异常映射）")
    void revokeReturns404() throws Exception {
        when(userMapper.selectById(ADMIN_ID)).thenReturn(adminUser());
        org.mockito.Mockito.doThrow(new com.bond.common.exception.BusinessException(404, "分享链接不存在或已撤销"))
                .when(service).revoke(eq(42L), eq(ADMIN_ID), org.mockito.ArgumentMatchers.anyString());

        mockMvc.perform(delete("/api/admin/shares/42").header("X-User-Id", ADMIN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    private static User adminUser() {
        User user = new User();
        user.setId(ADMIN_ID);
        user.setUsername("admin");
        user.setIsAdmin(true);
        return user;
    }

    private static User plainUser() {
        User user = new User();
        user.setId(ADMIN_ID);
        user.setUsername("ccc");
        user.setIsAdmin(false);
        return user;
    }
}
