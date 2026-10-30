package com.bond.transfer.controller;

import com.bond.common.dto.TransferTaskDTO;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.TransferService;
import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * task-107：complete 接口契约回归——必须返回任务快照（含 uploadedChunks/chunkCount）。
 *
 * <p>历史上 complete 返回 {@code Result<Void>}（body 无进度字段），客户端从 complete 响应里
 * 读 {@code uploadedChunks} 会读空；这里固定为「complete → 最新任务快照」。</p>
 */
class TransferControllerCompleteTest {

    private MockMvc mvc;
    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = mock(TransferService.class);
        TransferController controller = new TransferController(
                transferService, mock(ObjectStorage.class), mock(ConfigService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("complete → 200 且 data 带 uploadedChunks/chunkCount")
    void completeReturnsLatestProgressSnapshot() throws Exception {
        TransferTaskDTO dto = new TransferTaskDTO();
        dto.setId(5L);
        dto.setUploadedChunks(4);
        dto.setChunkCount(4);
        dto.setStatus(1);
        when(transferService.getTask(5L, 7L)).thenReturn(dto);

        mvc.perform(post("/api/transfer/tasks/5/complete")
                        .header("X-User-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("5"))
                .andExpect(jsonPath("$.data.uploadedChunks").value(4))
                .andExpect(jsonPath("$.data.chunkCount").value(4));
    }
}
