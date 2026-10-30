package com.bond.transfer.preview;

import com.bond.transfer.controller.PersonalFileController;
import com.bond.transfer.controller.WorkspaceController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * task-22 A（控制器层）：PDF 分页预览的 HTTP 契约。
 *
 * <p>200 → image/jpeg + X-Total-Pages + ETag；越界 → 416 且**必须**带 X-Total-Pages；
 * 非 PDF → 204；缺 token / 畸形 token → 404。</p>
 */
class PreviewPageControllerTest {

    private static final byte[] JPEG = {1, 2, 3};
    private static final String TOKEN = "preview-token";

    private PreviewService previewService;
    private PersonalFileController personalController;
    private WorkspaceController workspaceController;

    @BeforeEach
    void setUp() {
        previewService = mock(PreviewService.class);
        personalController = new PersonalFileController(null, null, previewService, null);
        workspaceController = new WorkspaceController(null, null, previewService, null, null);
    }

    @Test
    @DisplayName("个人空间 preview：200 + X-Total-Pages + ETag + image/jpeg")
    void personalPreviewReturnsJpeg() {
        when(previewService.personalPreviewPage(TOKEN, 5L, "2", "640"))
                .thenReturn(new PreviewService.Page(JPEG, 7, "\"e1\""));

        ResponseEntity<byte[]> response = personalController.preview(5L, "2", "640", TOKEN);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("7", response.getHeaders().getFirst("X-Total-Pages"));
        assertEquals("\"e1\"", response.getHeaders().getETag());
        assertEquals(MediaType.IMAGE_JPEG, response.getHeaders().getContentType());
        assertArrayEquals(JPEG, response.getBody());
    }

    @Test
    @DisplayName("个人空间 preview：越界 → 416 且带 X-Total-Pages（客户端据此拉回有效页）")
    void personalPreviewOutOfRangeReturns416() {
        when(previewService.personalPreviewPage(TOKEN, 5L, "99", "320"))
                .thenReturn(new PreviewService.Page(null, 12, "\"e\""));

        ResponseEntity<byte[]> response = personalController.preview(5L, "99", "320", TOKEN);

        assertEquals(416, response.getStatusCode().value());
        assertEquals("12", response.getHeaders().getFirst("X-Total-Pages"));
        assertNull(response.getBody());
    }

    @Test
    @DisplayName("个人空间 preview：非 PDF/生成失败 → 204")
    void personalPreviewNonPdfReturns204() {
        when(previewService.personalPreviewPage(TOKEN, 5L, "1", "320")).thenReturn(null);

        ResponseEntity<byte[]> response = personalController.preview(5L, "1", "320", TOKEN);

        assertEquals(204, response.getStatusCode().value());
    }

    @Test
    @DisplayName("个人空间 preview：缺 token/空 token → 404，且不触达预览服务")
    void personalPreviewMissingTokenReturns404() {
        assertEquals(404, personalController.preview(5L, "1", "320", null).getStatusCode().value());
        assertEquals(404, personalController.preview(5L, "1", "320", "  ").getStatusCode().value());
        verifyNoInteractions(previewService);
    }

    @Test
    @DisplayName("个人空间 preview：畸形/过期 token → 404；未知异常 → 204")
    void personalPreviewInvalidTokenReturns404() {
        when(previewService.personalPreviewPage(TOKEN, 5L, "1", "320"))
                .thenThrow(new PreviewService.InvalidPreviewTokenException());
        assertEquals(404, personalController.preview(5L, "1", "320", TOKEN).getStatusCode().value());

        when(previewService.personalPreviewPage(eq(TOKEN), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        assertEquals(204, personalController.preview(5L, "1", "320", TOKEN).getStatusCode().value());
    }

    @Test
    @DisplayName("工作区 preview：200 / 416 都带 X-Total-Pages；缺 token → 404")
    void workspacePreviewStatusAndHeader() {
        when(previewService.workspacePreviewPage(TOKEN, 9L, 5L, "3", "320"))
                .thenReturn(new PreviewService.Page(JPEG, 8, "\"w\""));
        ResponseEntity<byte[]> ok = workspaceController.preview(9L, 5L, "3", "320", TOKEN);
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("8", ok.getHeaders().getFirst("X-Total-Pages"));
        assertArrayEquals(JPEG, ok.getBody());

        when(previewService.workspacePreviewPage(TOKEN, 9L, 5L, "999", "320"))
                .thenReturn(new PreviewService.Page(null, 8, "\"w\""));
        ResponseEntity<byte[]> overflow = workspaceController.preview(9L, 5L, "999", "320", TOKEN);
        assertEquals(416, overflow.getStatusCode().value());
        assertEquals("8", overflow.getHeaders().getFirst("X-Total-Pages"));

        // 缺 token：404，不触达服务
        assertEquals(404, workspaceController.preview(9L, 5L, "1", "320", null).getStatusCode().value());
        verify(previewService, times(2))
                .workspacePreviewPage(any(), anyLong(), anyLong(), any(), any());
    }
}
