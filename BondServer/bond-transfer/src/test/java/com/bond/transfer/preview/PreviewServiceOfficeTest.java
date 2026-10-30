package com.bond.transfer.preview;

import com.bond.common.entity.WorkspaceFile;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Office 预览的 URL 签发 / 令牌 / Range 委派（task-59）。
 */
class PreviewServiceOfficeTest {

    private static final String TOKEN = "office-token";

    private PreviewTokenService tokens;
    private WorkspaceService workspaceService;
    private OfficePreviewService officePreview;
    private PreviewService service;

    @BeforeEach
    void setUp() {
        tokens = mock(PreviewTokenService.class);
        workspaceService = mock(WorkspaceService.class);
        officePreview = mock(OfficePreviewService.class);
        service = new PreviewService(tokens, workspaceService, mock(ThumbnailService.class),
                mock(UsageService.class), mock(ThumbnailRateLimiter.class), officePreview);
    }

    private static WorkspaceFile file(String name) {
        WorkspaceFile f = new WorkspaceFile();
        f.setId(5L);
        f.setWorkspaceId(7L);
        f.setFileName(name);
        f.setFileSize(2048L);
        f.setMinioPath("workspaces/7/uuid/" + name);
        f.setIsDirectory(false);
        return f;
    }

    @Test
    @DisplayName("受支持的 Office 文件：preview-url 返回 officePdfUrl（scope=office）")
    void issuesOfficePdfUrlForSupportedFile() {
        WorkspaceFile docx = file("报告.docx");
        when(workspaceService.getPersonalFileForPreview(1L, 5L)).thenReturn(docx);
        when(tokens.issue(anyLong(), anyLong(), anyString())).thenAnswer(inv -> "tk-" + inv.getArgument(2));
        when(tokens.getTtlSeconds()).thenReturn(600L);
        when(officePreview.isEnabled()).thenReturn(true);
        when(officePreview.supports(docx)).thenReturn(true);

        PreviewService.PreviewUrl urls = service.personalPreviewUrls(1L, 5L);

        assertNotNull(urls.officePdfUrl(), "Office 文件应签发 officePdfUrl");
        assertEquals("/api/files/5/office-pdf?token=tk-office", urls.officePdfUrl());
        assertEquals("/api/files/5/content?token=tk-content", urls.contentUrl());
        assertTrue(urls.previewUrlTemplate().contains("page={page}"));
        assertEquals(600L, urls.expiresIn());
        verify(tokens).issue(5L, 1L, PreviewTokenService.SCOPE_OFFICE);
    }

    @Test
    @DisplayName("Office 预览关闭：officePdfUrl=null，且不签发 office token")
    void officePdfUrlNullWhenDisabled() {
        WorkspaceFile docx = file("报告.docx");
        when(workspaceService.getPersonalFileForPreview(1L, 5L)).thenReturn(docx);
        when(tokens.issue(anyLong(), anyLong(), anyString())).thenAnswer(inv -> "tk-" + inv.getArgument(2));
        when(tokens.getTtlSeconds()).thenReturn(600L);
        when(officePreview.isEnabled()).thenReturn(false);

        PreviewService.PreviewUrl urls = service.personalPreviewUrls(1L, 5L);

        assertNull(urls.officePdfUrl());
        verify(tokens, never()).issue(anyLong(), anyLong(), eq(PreviewTokenService.SCOPE_OFFICE));
    }

    @Test
    @DisplayName("非 Office 文件：不签发 officePdfUrl（isEnabled=true 也不签）")
    void officePdfUrlNullForUnsupportedFile() {
        WorkspaceFile png = file("封面.png");
        when(workspaceService.getPersonalFileForPreview(1L, 5L)).thenReturn(png);
        when(tokens.issue(anyLong(), anyLong(), anyString())).thenAnswer(inv -> "tk-" + inv.getArgument(2));
        when(tokens.getTtlSeconds()).thenReturn(600L);
        when(officePreview.isEnabled()).thenReturn(true);
        when(officePreview.supports(png)).thenReturn(false);

        PreviewService.PreviewUrl urls = service.personalPreviewUrls(1L, 5L);

        assertNull(urls.officePdfUrl());
    }

    @Test
    @DisplayName("令牌 scope 必须为 office：无效/错 scope → InvalidPreviewTokenException（对外 404）")
    void openOfficePdfRejectsWrongScope() {
        when(tokens.resolve(TOKEN, 5L, PreviewTokenService.SCOPE_OFFICE)).thenReturn(Optional.empty());

        assertThrows(PreviewService.InvalidPreviewTokenException.class,
                () -> service.openPersonalOfficePdf(TOKEN, 5L, null));
    }

    @Test
    @DisplayName("全量读取：Range 头为空 → 透传全量区间，不计量（usageService 不参与）")
    void openOfficePdfFullRange() {
        WorkspaceFile docx = file("报告.docx");
        when(tokens.resolve(TOKEN, 5L, PreviewTokenService.SCOPE_OFFICE))
                .thenReturn(Optional.of(new PreviewTokenService.Ticket(5L, 1L, PreviewTokenService.SCOPE_OFFICE,
                        System.currentTimeMillis() + 600_000)));
        when(workspaceService.getPersonalFileForPreview(1L, 5L)).thenReturn(docx);
        when(officePreview.ensure(docx)).thenReturn(new OfficePreviewService.CachedPdf("previews/abc.pdf", 1000L));
        when(officePreview.openRange("previews/abc.pdf", 1000L, 0L, 1000L))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        PreviewService.Content content = service.openPersonalOfficePdf(TOKEN, 5L, null);

        assertEquals(1000L, content.totalSize());
        assertEquals(0L, content.offset());
        assertEquals(1000L, content.length());
        assertTrue(!content.partial());
        assertEquals("报告.pdf", content.fileName(), "预览文件名扩展名替换为 .pdf");
        assertEquals(OfficePreviewService.CONTENT_TYPE_PDF, content.contentType());
    }

    @Test
    @DisplayName("Range 读取：bytes=100-299 → 偏移/长度透传给 openRange，partial=true")
    void openOfficePdfRange() {
        WorkspaceFile docx = file("报告.docx");
        when(tokens.resolve(TOKEN, 5L, PreviewTokenService.SCOPE_OFFICE))
                .thenReturn(Optional.of(new PreviewTokenService.Ticket(5L, 1L, PreviewTokenService.SCOPE_OFFICE,
                        System.currentTimeMillis() + 600_000)));
        when(workspaceService.getPersonalFileForPreview(1L, 5L)).thenReturn(docx);
        when(officePreview.ensure(docx)).thenReturn(new OfficePreviewService.CachedPdf("previews/abc.pdf", 1000L));
        when(officePreview.openRange("previews/abc.pdf", 1000L, 100L, 200L))
                .thenReturn(new ByteArrayInputStream(new byte[]{4}));

        PreviewService.Content content = service.openPersonalOfficePdf(TOKEN, 5L, "bytes=100-299");

        assertEquals(100L, content.offset());
        assertEquals(200L, content.length());
        assertTrue(content.partial());
        verify(officePreview).openRange("previews/abc.pdf", 1000L, 100L, 200L);
    }

    @Test
    @DisplayName("Range 越界：bytes=5000- → UnsatisfiableRangeException（对外 416 + 总长）")
    void openOfficePdfUnsatisfiableRange() {
        WorkspaceFile docx = file("报告.docx");
        when(tokens.resolve(TOKEN, 5L, PreviewTokenService.SCOPE_OFFICE))
                .thenReturn(Optional.of(new PreviewTokenService.Ticket(5L, 1L, PreviewTokenService.SCOPE_OFFICE,
                        System.currentTimeMillis() + 600_000)));
        when(workspaceService.getPersonalFileForPreview(1L, 5L)).thenReturn(docx);
        when(officePreview.ensure(docx)).thenReturn(new OfficePreviewService.CachedPdf("previews/abc.pdf", 1000L));

        PreviewService.UnsatisfiableRangeException ex = assertThrows(
                PreviewService.UnsatisfiableRangeException.class,
                () -> service.openPersonalOfficePdf(TOKEN, 5L, "bytes=5000-"));

        assertEquals(1000L, ex.getTotalSize());
    }
}
