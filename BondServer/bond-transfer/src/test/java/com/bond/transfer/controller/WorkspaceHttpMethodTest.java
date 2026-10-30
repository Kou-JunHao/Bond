package com.bond.transfer.controller;

import com.bond.common.dto.WorkspaceDTO;
import com.bond.transfer.preview.PreviewService;
import com.bond.transfer.service.WorkspaceInvitationService;
import com.bond.transfer.service.WorkspaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回归测试：生产事故 16ee65f —— 在同一方法上叠加 {@code @PutMapping} + {@code @PatchMapping}
 * 时，Spring 的 {@code RequestMappingHandlerMapping} 只会注册其中一个，另一个请求会抛
 * {@code HttpRequestMethodNotSupportedException}，再被兜底成 HTTP 500，看起来像后端 bug。
 *
 * <p>{@code WorkspaceController.update} 必须使用显式
 * {@code @RequestMapping(method = {PUT, PATCH})}。本测试用 standalone MockMvc
 * 断言 PUT 与 PATCH 都能命中同一个 handler 并返回 200 —— 如果注解被改回叠加写法，
 * 其中一个断言会失败。</p>
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceHttpMethodTest {

    private static final Long WORKSPACE_ID = 7L;
    private static final Long OWNER_ID = 100L;

    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private WorkspaceInvitationService invitationService;
    @Mock
    private PreviewService previewService;

    private MockMvc mockMvc() {
        return MockMvcBuilders
                .standaloneSetup(new WorkspaceController(workspaceService, invitationService, previewService,
                        org.mockito.Mockito.mock(com.bond.transfer.service.FileSearchService.class),
                        org.mockito.Mockito.mock(com.bond.transfer.acl.WorkspaceAclService.class)))
                .build();
    }

    private WorkspaceDTO workspaceDTO() {
        WorkspaceDTO dto = new WorkspaceDTO();
        dto.setId(WORKSPACE_ID);
        dto.setName("team");
        return dto;
    }

    @Test
    @DisplayName("PUT /api/workspaces/{id} → 200")
    void putUpdateWorks() throws Exception {
        when(workspaceService.updateWorkspace(eq(WORKSPACE_ID), eq(OWNER_ID), eq("team"), isNull(), isNull()))
                .thenReturn(workspaceDTO());

        mockMvc().perform(put("/api/workspaces/{id}", WORKSPACE_ID)
                        .header("X-User-Id", String.valueOf(OWNER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"team\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString().contains("\"code\":200")));
    }

    @Test
    @DisplayName("PATCH /api/workspaces/{id} → 200（曾因 @PutMapping/@PatchMapping 叠加恒 500）")
    void patchUpdateWorks() throws Exception {
        when(workspaceService.updateWorkspace(eq(WORKSPACE_ID), eq(OWNER_ID), isNull(), eq("desc"), eq(4096L)))
                .thenReturn(workspaceDTO());

        mockMvc().perform(patch("/api/workspaces/{id}", WORKSPACE_ID)
                        .header("X-User-Id", String.valueOf(OWNER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"desc\",\"maxSize\":4096}"))
                .andExpect(status().isOk())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString().contains("\"code\":200")));
    }
}
