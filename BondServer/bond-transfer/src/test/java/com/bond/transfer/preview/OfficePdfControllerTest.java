package com.bond.transfer.preview;

import com.bond.transfer.controller.PersonalFileController;
import com.bond.transfer.controller.WorkspaceController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * /office-pdf 端点的状态码与响应头（task-59）。
 */
class OfficePdfControllerTest {

    private static final byte[] PDF = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '7'};

    private PreviewService previewService;
    private PersonalFileController personalController;
    private WorkspaceController workspaceController;

    @BeforeEach
    void setUp() {
        previewService = mock(PreviewService.class);
        personalController = new PersonalFileController(
                mock(com.bond.transfer.service.PersonalSpaceService.class),
                mock(com.bond.transfer.service.WorkspaceService.class),
                previewService,
                mock(com.bond.transfer.service.FileSearchService.class));
        workspaceController = new WorkspaceController(
                mock(com.bond.transfer.service.WorkspaceService.class),
                mock(com.bond.transfer.service.WorkspaceInvitationService.class),
                previewService,
                mock(com.bond.transfer.service.FileSearchService.class),
                mock(com.bond.transfer.acl.WorkspaceAclService.class));
    }

    private static PreviewService.Content content(InputStream stream, long total, long offset, long length,
                                                  boolean partial) {
        return new PreviewService.Content(stream, total, offset, length, partial,
                OfficePreviewService.CONTENT_TYPE_PDF, "报告.pdf");
    }

    @Test
    @DisplayName("缺 token → 404（不调 PreviewService，不泄露文件是否存在）")
    void missingTokenReturns404() {
        ResponseEntity<?> response = personalController.officePdf(5L, null, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verifyNoInteractions(previewService);
    }

    @Test
    @DisplayName("非法/过期令牌 → 404")
    void invalidTokenReturns404() {
        when(previewService.openPersonalOfficePdf("bad", 5L, null))
                .thenThrow(new PreviewService.InvalidPreviewTokenException());

        ResponseEntity<?> response = personalController.officePdf(5L, "bad", null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("完整下载 → 200 + application/pdf + Content-Length + Accept-Ranges: bytes")
    void fullPdfReturns200() {
        when(previewService.openPersonalOfficePdf("t", 5L, null))
                .thenReturn(content(new ByteArrayInputStream(PDF), PDF.length, 0, PDF.length, false));

        ResponseEntity<?> response = personalController.officePdf(5L, "t", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        assertEquals(PDF.length, response.getHeaders().getContentLength());
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.startsWith("inline"));
    }

    @Test
    @DisplayName("Range 命中 → 206 + Content-Range，Content-Length 为区间长度")
    void rangeReturns206() {
        when(previewService.openPersonalOfficePdf("t", 5L, "bytes=100-299"))
                .thenReturn(content(new ByteArrayInputStream(PDF), 1000, 100, 200, true));

        ResponseEntity<?> response = personalController.officePdf(5L, "t", "bytes=100-299");

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 100-299/1000", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(200, response.getHeaders().getContentLength());
    }

    @Test
    @DisplayName("Range 越界 → 416 + Content-Range: bytes */total")
    void unsatisfiableRangeReturns416() {
        when(previewService.openPersonalOfficePdf("t", 5L, "bytes=5000-"))
                .thenThrow(new PreviewService.UnsatisfiableRangeException(1000));

        ResponseEntity<?> response = personalController.officePdf(5L, "t", "bytes=5000-");

        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
        assertEquals("bytes */1000", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }

    private static String bodyOf(ResponseEntity<?> response) {
        Object body = response.getBody();
        if (body instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(body);
    }

    @Test
    @DisplayName("坏文档 → 415 + JSON 错误体（code=415）")
    void badDocumentReturns415Json() {
        when(previewService.openPersonalOfficePdf("t", 5L, null))
                .thenThrow(new OfficePreviewService.BadDocumentException());

        ResponseEntity<?> response = personalController.officePdf(5L, "t", null);

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode());
        assertTrue(bodyOf(response).contains("415"));
    }

    @Test
    @DisplayName("转换服务不可用 → 503 + JSON 错误体（含“文档转换服务暂时不可用”）")
    void unavailableReturns503Json() {
        when(previewService.openPersonalOfficePdf("t", 5L, null))
                .thenThrow(new OfficePreviewService.UnavailableException());

        ResponseEntity<?> response = personalController.officePdf(5L, "t", null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertTrue(bodyOf(response).contains("文档转换服务暂时不可用"));
    }

    @Test
    @DisplayName("源文件超 100MB → 413")
    void tooLargeReturns413() {
        when(previewService.openPersonalOfficePdf("t", 5L, null))
                .thenThrow(new OfficePreviewService.TooLargeException());

        ResponseEntity<?> response = personalController.officePdf(5L, "t", null);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
    }

    @Test
    @DisplayName("Office 预览关闭 → 404（不暴露服务端能力）")
    void disabledReturns404() {
        when(previewService.openPersonalOfficePdf("t", 5L, null))
                .thenThrow(new OfficePreviewService.DisabledException());

        ResponseEntity<?> response = personalController.officePdf(5L, "t", null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("工作区路径同样返回 PDF（200）")
    void workspaceOfficePdfReturns200() {
        when(previewService.openWorkspaceOfficePdf("t", 9L, 5L, null))
                .thenReturn(content(new ByteArrayInputStream(PDF), PDF.length, 0, PDF.length, false));

        ResponseEntity<?> response = workspaceController.officePdf(9L, 5L, "t", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
    }

    @Test
    @DisplayName("工作区路径缺 token → 404")
    void workspaceMissingTokenReturns404() {
        ResponseEntity<?> response = workspaceController.officePdf(9L, 5L, "  ", null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verifyNoInteractions(previewService);
    }
}
