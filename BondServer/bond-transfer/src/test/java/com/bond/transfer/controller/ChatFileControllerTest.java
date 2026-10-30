package com.bond.transfer.controller;

import com.bond.common.exception.BusinessException;
import com.bond.common.exception.GlobalExceptionHandler;
import com.bond.transfer.chat.ChatFileAccessService;
import com.bond.transfer.preview.OfficePreviewService;
import com.bond.transfer.preview.PreviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * task-84 方案 C 的 HTTP 形状：token 在 query、失败统一 404、Range 206/416。
 */
@ExtendWith(MockitoExtension.class)
class ChatFileControllerTest {

    @Mock
    private ChatFileAccessService chatFiles;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ChatFileController controller = new ChatFileController(chatFiles);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /{messageId}/tokens：返回 4 类 URL 与文件元信息")
    void issuesTokens() throws Exception {
        when(chatFiles.issue(7L, 9L)).thenReturn(new ChatFileAccessService.Tokens(
                "/api/chat/files/meta?token=m", "/api/chat/files/content?token=c",
                "/api/chat/files/thumbnail?w=320&token=t", null, 600L,
                "报告.docx", 100L, "application/pdf", "sha-abc"));

        mockMvc.perform(post("/api/chat/files/7/tokens").header("X-User-Id", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metaUrl").value("/api/chat/files/meta?token=m"))
                .andExpect(jsonPath("$.data.contentUrl").value("/api/chat/files/content?token=c"))
                .andExpect(jsonPath("$.data.expiresIn").value(600))
                .andExpect(jsonPath("$.data.fileName").value("报告.docx"));
    }

    @Test
    @DisplayName("GET /meta：缺 token / 非法 token 都 404；有效 token 返回元信息")
    void metaStatuses() throws Exception {
        mockMvc.perform(get("/api/chat/files/meta")).andExpect(status().isNotFound());

        when(chatFiles.meta("bad"))
                .thenThrow(new PreviewService.InvalidPreviewTokenException());
        mockMvc.perform(get("/api/chat/files/meta").param("token", "bad"))
                .andExpect(status().isNotFound());

        when(chatFiles.meta("tok")).thenReturn(new ChatFileAccessService.FileMeta(
                "报告.docx", 100L, "application/pdf", "file", "sha-abc"));
        mockMvc.perform(get("/api/chat/files/meta").param("token", "tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("file"))
                .andExpect(jsonPath("$.data.sha256").value("sha-abc"));
    }

    @Test
    @DisplayName("GET /content：200 带 Content-Length/Accept-Ranges，内容为文件字节")
    void contentFull() throws Exception {
        when(chatFiles.openContent("tok", null)).thenReturn(new PreviewService.Content(
                new ByteArrayInputStream("pdf-bytes".getBytes(StandardCharsets.UTF_8)),
                100L, 0L, 9L, false, "application/pdf", "报告.pdf"));

        mockMvc.perform(get("/api/chat/files/content").param("token", "tok"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "9"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().bytes("pdf-bytes".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("GET /content：Range → 206 + Content-Range")
    void contentPartial() throws Exception {
        when(chatFiles.openContent("tok", "bytes=10-29")).thenReturn(new PreviewService.Content(
                new ByteArrayInputStream(new byte[20]), 100L, 10L, 20L, true,
                "application/octet-stream", "a.bin"));

        mockMvc.perform(get("/api/chat/files/content")
                        .param("token", "tok").header(HttpHeaders.RANGE, "bytes=10-29"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 10-29/100"));
    }

    @Test
    @DisplayName("GET /content：Range 越界 → 416 + bytes */total；缺 token → 404")
    void contentUnsatisfiableAndMissingToken() throws Exception {
        when(chatFiles.openContent("tok", "bytes=1000-"))
                .thenThrow(new PreviewService.UnsatisfiableRangeException(100L));

        mockMvc.perform(get("/api/chat/files/content")
                        .param("token", "tok").header(HttpHeaders.RANGE, "bytes=1000-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */100"));

        mockMvc.perform(get("/api/chat/files/content")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /thumbnail：无缩略图 204；限流 429 + Retry-After；304 走 ETag")
    void thumbnailStatuses() throws Exception {
        when(chatFiles.thumbnail("tok", null)).thenReturn(null);
        mockMvc.perform(get("/api/chat/files/thumbnail").param("token", "tok"))
                .andExpect(status().isNoContent());

        when(chatFiles.thumbnail("limited", null))
                .thenThrow(new PreviewService.ThumbnailRateLimitedException(30L));
        mockMvc.perform(get("/api/chat/files/thumbnail").param("token", "limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"));

        when(chatFiles.thumbnail("etag", "64"))
                .thenReturn(new PreviewService.Thumbnail(new byte[]{1}, "\"e1\""));
        mockMvc.perform(get("/api/chat/files/thumbnail")
                        .param("token", "etag").param("w", "64")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"e1\""))
                .andExpect(status().isNotModified());
    }

    @Test
    @DisplayName("GET /office-pdf：转换不可用 503 / 坏文档 415 / 缺 token 404")
    void officePdfErrors() throws Exception {
        when(chatFiles.openOfficePdf(eq("down"), any()))
                .thenThrow(new OfficePreviewService.UnavailableException());
        mockMvc.perform(get("/api/chat/files/office-pdf").param("token", "down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));

        when(chatFiles.openOfficePdf(eq("bad"), any()))
                .thenThrow(new OfficePreviewService.BadDocumentException());
        mockMvc.perform(get("/api/chat/files/office-pdf").param("token", "bad"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(415));

        mockMvc.perform(get("/api/chat/files/office-pdf")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /meta：服务层抛 BusinessException(404) 走统一错误信封")
    void metaBusinessError() throws Exception {
        when(chatFiles.meta("gone")).thenThrow(new BusinessException(404, "文件不存在或已失效"));
        mockMvc.perform(get("/api/chat/files/meta").param("token", "gone"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
