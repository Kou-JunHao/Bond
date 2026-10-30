package com.bond.transfer.controller;

import com.bond.common.dto.FileSearchItem;
import com.bond.common.dto.FileSearchResult;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.GlobalExceptionHandler;
import com.bond.transfer.preview.PreviewService;
import com.bond.transfer.service.FileSearchService;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceInvitationService;
import com.bond.transfer.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * task-117：文件搜索 HTTP 契约测试（standalone MockMvc，不连 DB）。
 */
@ExtendWith(MockitoExtension.class)
class FileSearchControllerTest {

    private static final Long USER_ID = 7L;

    @Mock
    private FileSearchService fileSearchService;
    @Mock
    private PersonalSpaceService personalSpaceService;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private PreviewService previewService;
    @Mock
    private WorkspaceInvitationService invitationService;

    private MockMvc personalMvc;
    private MockMvc workspaceMvc;

    @BeforeEach
    void setUp() {
        personalMvc = MockMvcBuilders
                .standaloneSetup(new PersonalFileController(personalSpaceService, workspaceService,
                        previewService, fileSearchService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        workspaceMvc = MockMvcBuilders
                .standaloneSetup(new WorkspaceController(workspaceService, invitationService,
                        previewService, fileSearchService,
                        org.mockito.Mockito.mock(com.bond.transfer.acl.WorkspaceAclService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static FileSearchResult result() {
        FileSearchItem item = new FileSearchItem(11L, "报告.pdf", "/a/报告.pdf", false, 12L,
                "application/pdf", 1_760_000_000_000L, 9L);
        return new FileSearchResult(List.of(item), "next-cursor", true, 2L);
    }

    @Test
    @DisplayName("个人空间搜索：200 + 信封 data{items,total,nextCursor,hasMore} + 字段名 name/path/size")
    void personalSearchReturnsEnvelope() throws Exception {
        when(fileSearchService.search(USER_ID, "报告", null, 50, null)).thenReturn(result());

        personalMvc.perform(get("/api/files/search")
                        .header("X-User-Id", USER_ID)
                        .param("q", "报告")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value("next-cursor"))
                .andExpect(jsonPath("$.data.items[0].name").value("报告.pdf"))
                .andExpect(jsonPath("$.data.items[0].path").value("/a/报告.pdf"))
                .andExpect(jsonPath("$.data.items[0].size").value(12))
                .andExpect(jsonPath("$.data.items[0].isDirectory").value(false))
                .andExpect(jsonPath("$.data.items[0].workspaceId").value("9"));
    }

    @Test
    @DisplayName("workspaceId/limit/cursor 原样转给 service")
    void passesAllParameters() throws Exception {
        when(fileSearchService.search(USER_ID, "abc", 5L, 10, "cursor-1")).thenReturn(result());

        personalMvc.perform(get("/api/files/search")
                        .header("X-User-Id", USER_ID)
                        .param("q", "abc")
                        .param("workspaceId", "5")
                        .param("limit", "10")
                        .param("cursor", "cursor-1"))
                .andExpect(status().isOk());

        verify(fileSearchService).search(USER_ID, "abc", 5L, 10, "cursor-1");
    }

    @Test
    @DisplayName("缺 q → 400（缺参处理器）")
    void missingQueryIs400() throws Exception {
        personalMvc.perform(get("/api/files/search").header("X-User-Id", USER_ID))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(fileSearchService);
    }

    @Test
    @DisplayName("q 空白 → 400（业务校验）")
    void blankQueryIs400() throws Exception {
        when(fileSearchService.search(USER_ID, "   ", null, null, null))
                .thenThrow(new BusinessException(400, "搜索关键词不能为空"));

        personalMvc.perform(get("/api/files/search")
                        .header("X-User-Id", USER_ID)
                        .param("q", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("q 超长 → 400，message 带长度说明")
    void tooLongQueryIs400() throws Exception {
        when(fileSearchService.search(USER_ID, "x".repeat(101), null, null, null))
                .thenThrow(new BusinessException(400, "搜索关键词过长（最多 100 字符）"));

        personalMvc.perform(get("/api/files/search")
                        .header("X-User-Id", USER_ID)
                        .param("q", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("搜索关键词过长（最多 100 字符）"));
    }

    @Test
    @DisplayName("工作区搜索：GET /api/workspaces/{id}/files/search → 200")
    void workspaceSearchReturnsEnvelope() throws Exception {
        when(fileSearchService.search(USER_ID, "x", 5L, null, null)).thenReturn(result());

        workspaceMvc.perform(get("/api/workspaces/5/files/search")
                        .header("X-User-Id", USER_ID)
                        .param("q", "x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        verify(fileSearchService).search(USER_ID, "x", 5L, null, null);
    }

    @Test
    @DisplayName("非成员访问工作区搜索 → 403")
    void nonMemberIs403() throws Exception {
        when(fileSearchService.search(USER_ID, "x", 6L, null, null))
                .thenThrow(new BusinessException(403, "不是该工作区成员"));

        workspaceMvc.perform(get("/api/workspaces/6/files/search")
                        .header("X-User-Id", USER_ID)
                        .param("q", "x"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("非法 cursor → 400")
    void invalidCursorIs400() throws Exception {
        when(fileSearchService.search(USER_ID, "x", null, null, "bad"))
                .thenThrow(new BusinessException(400, "cursor 非法"));

        personalMvc.perform(get("/api/files/search")
                        .header("X-User-Id", USER_ID)
                        .param("q", "x")
                        .param("cursor", "bad"))
                .andExpect(status().isBadRequest());
    }
}
