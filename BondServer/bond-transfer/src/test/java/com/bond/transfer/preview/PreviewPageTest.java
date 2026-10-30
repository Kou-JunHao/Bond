package com.bond.transfer.preview;

import com.bond.common.entity.WorkspaceFile;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-22 A（服务层）：PDF 分页的页码/宽度容错与越界结果。
 *
 * <p>契约：非法页码（非数字/空/0/负数）回退第 1 页；非法宽度回退 640；越界返回 jpeg=null 但保留 totalPages，
 * 供控制器回 416 + X-Total-Pages。</p>
 */
class PreviewPageTest {

    private static final byte[] JPEG = {1, 2, 3};
    private static final String TOKEN = "preview-token";

    private PreviewTokenService tokens;
    private WorkspaceService workspaceService;
    private ThumbnailService thumbnails;
    private PreviewService previewService;

    @BeforeEach
    void setUp() {
        tokens = mock(PreviewTokenService.class);
        workspaceService = mock(WorkspaceService.class);
        thumbnails = mock(ThumbnailService.class);
        previewService = new PreviewService(tokens, workspaceService, thumbnails,
                mock(UsageService.class), mock(ThumbnailRateLimiter.class), mock(OfficePreviewService.class));
    }

    @Test
    @DisplayName("非法 page/w（含 0、负数、非数字）回退第 1 页与 640 宽，而不是 4xx")
    void invalidPageFallsBackToFirstPage() {
        WorkspaceFile file = pdfFile();
        stubTicketAndFile(file);
        when(thumbnails.previewPage(file, 1, ThumbnailService.DEFAULT_PREVIEW_WIDTH))
                .thenReturn(new ThumbnailService.Page(JPEG, 3, "\"e\""));

        PreviewService.Page result = previewService.personalPreviewPage(TOKEN, 5L, "abc", "abc");

        assertNotNull(result.jpeg());
        assertEquals(3, result.totalPages());
        verify(thumbnails).previewPage(file, 1, ThumbnailService.DEFAULT_PREVIEW_WIDTH);

        // 0 / 负数也回退
        when(thumbnails.previewPage(file, 1, ThumbnailService.DEFAULT_PREVIEW_WIDTH))
                .thenReturn(new ThumbnailService.Page(JPEG, 3, "\"e\""));
        previewService.personalPreviewPage(TOKEN, 5L, "0", "9999");
        verify(thumbnails, org.mockito.Mockito.times(2))
                .previewPage(file, 1, ThumbnailService.DEFAULT_PREVIEW_WIDTH);
    }

    @Test
    @DisplayName("合法 page/w 原样透传（page=3, w=128）")
    void validPagePassesThrough() {
        WorkspaceFile file = pdfFile();
        stubTicketAndFile(file);
        when(thumbnails.previewPage(file, 3, 128))
                .thenReturn(new ThumbnailService.Page(JPEG, 10, "\"e\""));

        PreviewService.Page result = previewService.personalPreviewPage(TOKEN, 5L, "3", "128");

        assertEquals(10, result.totalPages());
        verify(thumbnails).previewPage(file, 3, 128);
    }

    @Test
    @DisplayName("服务层越界结果：jpeg=null 但 totalPages 保留，供控制器 416 带总页数")
    void outOfRangeKeepsTotalPages() {
        WorkspaceFile file = pdfFile();
        stubTicketAndFile(file);
        when(thumbnails.previewPage(file, 99, 320))
                .thenReturn(new ThumbnailService.Page(null, 12, "\"e\""));

        PreviewService.Page result = previewService.personalPreviewPage(TOKEN, 5L, "99", "320");

        assertNull(result.jpeg());
        assertEquals(12, result.totalPages());
    }

    private void stubTicketAndFile(WorkspaceFile file) {
        when(tokens.resolve(TOKEN, 5L, PreviewTokenService.SCOPE_PREVIEW))
                .thenReturn(Optional.of(new PreviewTokenService.Ticket(
                        5L, 7L, PreviewTokenService.SCOPE_PREVIEW, Long.MAX_VALUE)));
        when(workspaceService.getPersonalFileForPreview(7L, 5L)).thenReturn(file);
    }

    private static WorkspaceFile pdfFile() {
        WorkspaceFile file = new WorkspaceFile();
        file.setId(5L);
        file.setFileName("doc.pdf");
        file.setContentType("application/pdf");
        file.setFileSize(1024L);
        file.setMinioPath("workspaces/1/5.pdf");
        return file;
    }
}
