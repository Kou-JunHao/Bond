package com.bond.transfer.controller;

import com.bond.common.dto.TransferTaskDTO;
import com.bond.common.exception.GlobalExceptionHandler;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.TransferService;
import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * task-99：建任务请求的 Integer/Long 拆箱防护。
 *
 * <p>未传 chunkSize 时服务端默认 4 MiB；非法 chunkSize/缺失 fileSize/无接收方/空文件名
 * 统一返回 400 中文业务错误，绝不 500 泄漏实现细节。</p>
 */
class TransferControllerTaskRequestTest {

    private static final Long USER_ID = 7L;
    private static final long DEFAULT_CHUNK = 4L * 1024 * 1024;

    private TransferService transferService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        transferService = mock(TransferService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new TransferController(
                        transferService, mock(ObjectStorage.class), mock(ConfigService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("缺 chunkSize：200，且落库参数为默认 4 MiB")
    void missingChunkSizeUsesDefault() throws Exception {
        when(transferService.createTask(anyLong(), any(), any(), any(), anyString(), anyLong(),
                anyInt(), any(), any())).thenReturn(new TransferTaskDTO());

        mockMvc.perform(post("/api/transfer/tasks")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverDeviceId\":123,\"fileName\":\"x.bin\","
                                + "\"fileSize\":1048576,\"transferType\":\"direct\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(transferService).createTask(USER_ID, null, null, 123L, "x.bin", 1048576L,
                (int) DEFAULT_CHUNK, null, "direct");
    }

    @Test
    @DisplayName("chunkSize=123（非 2 的幂）：400 中文业务错误")
    void invalidChunkSizeRejected() throws Exception {
        mockMvc.perform(post("/api/transfer/tasks")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverDeviceId\":123,\"fileName\":\"x.bin\","
                                + "\"fileSize\":1048576,\"chunkSize\":123}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("分片大小必须是 2 的幂且大于 0"));

        verify(transferService, never()).createTask(anyLong(), any(), any(), any(), anyString(),
                anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("chunkSize=0 / -1：400")
    void nonPositiveChunkSizeRejected() throws Exception {
        for (int chunkSize : new int[]{0, -1}) {
            mockMvc.perform(post("/api/transfer/tasks")
                            .header("X-User-Id", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"receiverDeviceId\":123,\"fileName\":\"x.bin\","
                                    + "\"fileSize\":1048576,\"chunkSize\":" + chunkSize + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
    }

    @Test
    @DisplayName("缺 fileSize：400（不拆箱 NPE）")
    void missingFileSizeRejected() throws Exception {
        mockMvc.perform(post("/api/transfer/tasks")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverDeviceId\":123,\"fileName\":\"x.bin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件大小不能为空"));
    }

    @Test
    @DisplayName("无接收方（receiverId/receiverDeviceId 均空）：400")
    void missingReceiverRejected() throws Exception {
        mockMvc.perform(post("/api/transfer/tasks")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"x.bin\",\"fileSize\":1048576}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("接收方不能为空"));
    }

    @Test
    @DisplayName("空文件名：400")
    void blankFileNameRejected() throws Exception {
        mockMvc.perform(post("/api/transfer/tasks")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverDeviceId\":123,\"fileName\":\"  \",\"fileSize\":1048576}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件名不能为空"));
    }
}
