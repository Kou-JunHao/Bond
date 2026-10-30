package com.bond.transfer.controller;

import com.bond.common.exception.GlobalExceptionHandler;
import com.bond.transfer.acl.WorkspaceAclDtos;
import com.bond.transfer.acl.WorkspaceAclService;
import com.bond.transfer.preview.PreviewService;
import com.bond.transfer.service.FileSearchService;
import com.bond.transfer.service.WorkspaceInvitationService;
import com.bond.transfer.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * task-183B：ACL 端点的 HTTP 路由/序列化契约（standalone MockMvc，不启动容器）。
 *
 * <p>重点防两件事：</p>
 * <ol>
 *   <li>{@code PUT /{id}/members/{userId}}（改角色）与 {@code PUT /{id}/members/{memberUserId}/acl}
 *       不能互相遮蔽 —— 路径不同但只差一段，路由冲突会退化成 405/500；</li>
 *   <li>视图 JSON 字段名与客户端约定一致（accessMode/grants/scopeType/scopePath/canRead/canWrite）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceAclControllerTest {

    private static final Long WS = 7L;
    private static final Long OPERATOR = 2L;
    private static final Long TARGET = 9L;

    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private WorkspaceInvitationService invitationService;
    @Mock
    private PreviewService previewService;
    @Mock
    private FileSearchService fileSearchService;
    @Mock
    private WorkspaceAclService workspaceAclService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new WorkspaceController(
                        workspaceService, invitationService, previewService, fileSearchService, workspaceAclService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static WorkspaceAclDtos.View view(String mode) {
        return new WorkspaceAclDtos.View(WS, TARGET, mode, "restricted".equals(mode)
                ? List.of(new WorkspaceAclDtos.Grant("folder", "/docs", null, true, false))
                : List.of());
    }

    @Test
    @DisplayName("GET /api/workspaces/{id}/members/{userId}/acl → 200 + accessMode/grants JSON")
    void memberAclRoute() throws Exception {
        when(workspaceAclService.memberAcl(OPERATOR, WS, TARGET)).thenReturn(view("restricted"));

        mvc.perform(get("/api/workspaces/{id}/members/{userId}/acl", WS, TARGET)
                        .header("X-User-Id", String.valueOf(OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"accessMode\":\"restricted\""), body);
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"scopeType\":\"folder\""), body);
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"scopePath\":\"/docs\""), body);
                });

        verify(workspaceAclService).memberAcl(OPERATOR, WS, TARGET);
    }

    @Test
    @DisplayName("PUT /api/workspaces/{id}/members/{userId}/acl → 200，body 与 IP 透传 service")
    void replaceAclRoute() throws Exception {
        when(workspaceAclService.replaceAcl(eq(OPERATOR), eq(WS), eq(TARGET), any(), anyString()))
                .thenReturn(view("all"));

        mvc.perform(put("/api/workspaces/{id}/members/{userId}/acl", WS, TARGET)
                        .header("X-User-Id", String.valueOf(OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessMode\":\"all\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getContentAsString().contains("\"accessMode\":\"all\"")));

        verify(workspaceAclService).replaceAcl(eq(OPERATOR), eq(WS), eq(TARGET),
                any(WorkspaceAclDtos.PutRequest.class), anyString());
    }

    @Test
    @DisplayName("GET /api/workspaces/{id}/acl/me → 200（本人有效范围）")
    void myAclRoute() throws Exception {
        when(workspaceAclService.myAcl(TARGET, WS)).thenReturn(view("restricted"));

        mvc.perform(get("/api/workspaces/{id}/acl/me", WS)
                        .header("X-User-Id", String.valueOf(TARGET)))
                .andExpect(status().isOk())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getContentAsString().contains("\"accessMode\":\"restricted\"")));
    }
}
