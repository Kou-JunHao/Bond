package com.bond.transfer.controller;

import com.bond.common.dto.PageResult;
import com.bond.common.dto.TransferTaskDTO;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.TransferService;
import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * task-54：传输中心列表分页/过滤/详情的契约（controller 层，只读）。
 */
@ExtendWith(MockitoExtension.class)
class TransferCenterListTest {

    private static final long USER = 10L;

    @Mock
    private TransferService transferService;
    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private ConfigService configService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TransferController(transferService, objectStorage, configService)).build();
    }

    @Test
    @DisplayName("传 page 时返回分页信封，status 透传为过滤条件")
    void listPagedReturnsEnvelopeAndStatusFilter() throws Exception {
        when(transferService.listTasksPaged(USER, null, 2, 1, 2))
                .thenReturn(PageResult.of(List.of(task(7L, "a.bin", 100L, 50L)), 7L, 1, 2));

        mvc.perform(get("/api/transfer/tasks")
                        .header("X-User-Id", USER)
                        .param("page", "1")
                        .param("size", "2")
                        .param("status", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"code\":200")))
                .andExpect(content().string(containsString("\"total\":7")))
                .andExpect(content().string(containsString("\"page\":1")))
                .andExpect(content().string(containsString("\"size\":2")))
                .andExpect(content().string(containsString("\"fileName\":\"a.bin\"")))
                .andExpect(content().string(containsString("\"progressBytes\":50")));

        verify(transferService, times(1)).listTasksPaged(USER, null, 2, 1, 2);
        verify(transferService, times(0)).listTasks(USER, null);
    }

    @Test
    @DisplayName("不传 page 时保持旧行为：data 为数组（老客户端兼容）")
    void listWithoutPageKeepsLegacyArray() throws Exception {
        when(transferService.listTasks(USER, "up")).thenReturn(List.of(task(8L, "b.zip", 2048L, 1024L)));

        mvc.perform(get("/api/transfer/tasks")
                        .header("X-User-Id", USER)
                        .param("direction", "up"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"code\":200")))
                .andExpect(content().string(containsString("\"fileName\":\"b.zip\"")));

        verify(transferService, times(0)).listTasksPaged(eq(USER), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("详情：带 progressBytes/totalBytes/peerName")
    void detailReturnsProgressAndPeer() throws Exception {
        TransferTaskDTO dto = task(9L, "movie.mkv", 1000L, 420L);
        dto.setPeerName("bob");
        dto.setSpeedBytesPerSec(null);
        when(transferService.getTask(9L, USER)).thenReturn(dto);

        mvc.perform(get("/api/transfer/tasks/9").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"code\":200")))
                .andExpect(content().string(containsString("\"fileName\":\"movie.mkv\"")))
                .andExpect(content().string(containsString("\"progressBytes\":420")))
                .andExpect(content().string(containsString("\"totalBytes\":1000")))
                .andExpect(content().string(containsString("\"peerName\":\"bob\"")));
    }

    @Test
    @DisplayName("未传 X-User-Id：请求被拒（不会走到 service）")
    void missingUserHeaderRejected() throws Exception {
        mvc.perform(get("/api/transfer/tasks").param("page", "1"))
                .andExpect(status().isBadRequest());
    }

    private static TransferTaskDTO task(Long id, String fileName, long totalBytes, long progressBytes) {
        TransferTaskDTO dto = new TransferTaskDTO();
        dto.setId(id);
        dto.setFileName(fileName);
        dto.setTotalBytes(totalBytes);
        dto.setProgressBytes(progressBytes);
        dto.setStatus(2);
        dto.setUploadedChunks(0);
        return dto;
    }
}
