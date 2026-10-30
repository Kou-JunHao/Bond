package com.bond.transfer.controller;

import com.bond.transfer.trash.TrashBatchResult;
import com.bond.transfer.trash.TrashItem;
import com.bond.transfer.trash.TrashService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回收站接口的状态码/响应体契约回归（task-37）。
 *
 * <p>用 standalone MockMvc 直接命中 {@link TrashController}：200 列表、207 部分失败、
 * 204 清空 + X-Trash-Purged。真实鉴权不在这里测（/api/** 由 InternalAuthFilter 负责）。</p>
 */
@ExtendWith(MockitoExtension.class)
class TrashControllerTest {

    private static final Long USER = 1L;
    private static final Long WORKSPACE_ID = 5L;

    @Mock
    private TrashService trashService;

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new TrashController(trashService,
                org.mockito.Mockito.mock(com.bond.transfer.acl.WorkspaceAclService.class))).build();
    }

    private static TrashItem item() {
        return new TrashItem(9L, "a.pdf", 10L, false, "file", "/docs/a.pdf", 1L, null, 0);
    }

    @Test
    @DisplayName("GET /api/trash?workspaceId= → 200，列表只含顶层条目")
    void listReturnsItems() throws Exception {
        when(trashService.list(USER, WORKSPACE_ID)).thenReturn(List.of(item()));

        mockMvc().perform(get("/api/trash")
                        .param("workspaceId", String.valueOf(WORKSPACE_ID))
                        .header("X-User-Id", String.valueOf(USER)))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"code\":200"));
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("a.pdf"));
                });
    }

    @Test
    @DisplayName("POST /api/trash/restore 部分失败 → 207，body code=207 且带 failed 明细")
    void restorePartialReturns207() throws Exception {
        TrashBatchResult partial = new TrashBatchResult(
                List.of(1L), List.of(new TrashBatchResult.Failed(2L, "名称冲突")));
        when(trashService.restore(eq(USER), eq(List.of(1L, 2L)))).thenReturn(partial);

        mockMvc().perform(post("/api/trash/restore")
                        .header("X-User-Id", String.valueOf(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1,2]}"))
                .andExpect(status().isMultiStatus())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"code\":207"));
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("名称冲突"));
                });
    }

    @Test
    @DisplayName("POST /api/trash/purge 全部成功 → 200")
    void purgeAllSucceedsReturns200() throws Exception {
        when(trashService.purge(eq(USER), eq(List.of(1L, 2L))))
                .thenReturn(new TrashBatchResult(List.of(1L, 2L), List.of()));

        mockMvc().perform(post("/api/trash/purge")
                        .header("X-User-Id", String.valueOf(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getContentAsString().contains("\"code\":200")));
    }

    @Test
    @DisplayName("DELETE /api/trash → 204，X-Trash-Purged 返回实际删除行数")
    void emptyReturns204WithPurgedHeader() throws Exception {
        when(trashService.empty(USER, null)).thenReturn(3);

        mockMvc().perform(delete("/api/trash").header("X-User-Id", String.valueOf(USER)))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-Trash-Purged", "3"));

        verify(trashService).empty(USER, null);
    }

    @Test
    @DisplayName("GET /api/trash 不带 workspaceId → 走个人空间（null 透传）")
    void listWithoutWorkspaceUsesPersonalSpace() throws Exception {
        when(trashService.list(USER, null)).thenReturn(List.of(item()));

        mockMvc().perform(get("/api/trash").header("X-User-Id", String.valueOf(USER)))
                .andExpect(status().isOk());
        verify(trashService).list(USER, null);
    }
}
