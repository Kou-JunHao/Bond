package com.bond.transfer.share;

import com.bond.common.exception.BusinessException;
import com.bond.transfer.preview.PreviewService;
import com.bond.transfer.share.ShareDtos.PatchShareRequest;
import com.bond.transfer.share.ShareDtos.ShareLinkView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 匿名分享 controller 的 HTTP 契约：HTML 落地页、Range 206、416 映射。
 * 直接调用 controller 方法（不起 Spring 上下文），与 PreviewPageControllerTest 同风格。
 */
class ShareControllerTest {

    private static final String TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private final ShareLinkService service = mock(ShareLinkService.class);
    private final ShareController controller = new ShareController(service);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    @DisplayName("列表：workspaceId 透传给 service（保留参数就必须生效，缺省为 null 查全部）")
    void listForwardsWorkspaceFilter() {
        when(service.list(1L, 7L)).thenReturn(List.of());
        when(service.list(1L, null)).thenReturn(List.of());

        assertTrue(controller.list(1L, 7L).getData().isEmpty());
        assertTrue(controller.list(1L, null).getData().isEmpty());

        verify(service).list(1L, 7L);
        verify(service).list(1L, null);
    }

    @Test
    @DisplayName("落地页：200 text/html;charset=UTF-8 + no-store")
    void landingRendersHtml() {
        when(service.landingHtml(TOKEN)).thenReturn("<html><body>ok</body></html>");

        ResponseEntity<String> response = controller.landing(TOKEN);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML));
        assertTrue(response.getBody().contains("ok"));
        assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    @DisplayName("Range 下载：206 + Content-Range + Content-Length + attachment")
    void partialDownloadHeaders() {
        when(service.planDownload(eq(TOKEN), any(), eq("bytes=10-14"), anyString()))
                .thenReturn(new ShareLinkService.ShareDownloadPlan(
                        new ShareLinkService.ShareDownload("a.txt", "text/plain",
                                100L, 10L, 5L, true, new ByteArrayInputStream(new byte[5])), null));

        ResponseEntity<?> response = controller.download(TOKEN, null, "bytes=10-14", request);

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 10-14/100", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals("5", response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH));
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
        assertEquals("text/plain", response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
    }

    @Test
    @DisplayName("完整下载：200，不带 Content-Range")
    void fullDownloadHeaders() {
        when(service.planDownload(eq(TOKEN), any(), any(), anyString()))
                .thenReturn(new ShareLinkService.ShareDownloadPlan(
                        new ShareLinkService.ShareDownload("b.bin", null,
                                100L, 0L, 100L, false, new ByteArrayInputStream(new byte[100])), null));

        ResponseEntity<?> response = controller.download(TOKEN, null, null, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals("100", response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH));
        assertEquals(MediaType.APPLICATION_OCTET_STREAM_VALUE,
                response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        assertFalse(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).isBlank());
    }

    @Test
    @DisplayName("文件夹分享下载（task-183）：200 + application/zip + attachment + 无 Content-Length（分块流）")
    void folderDownloadReturnsZipStream() throws Exception {
        ShareLinkService.ShareZipDownload zip = new ShareLinkService.ShareZipDownload("docs",
                List.of(new ShareLinkService.ShareZipEntry("a.txt", 3L, "k/a"),
                        new ShareLinkService.ShareZipEntry("sub/", 0L, null)),
                1L);
        when(service.planDownload(eq(TOKEN), any(), any(), anyString()))
                .thenReturn(new ShareLinkService.ShareDownloadPlan(null, zip));

        ResponseEntity<?> response = controller.download(TOKEN, null, null, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/zip", response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        assertNull(response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH),
                "ZIP 大小未知，不能声明 Content-Length（走 chunked）");
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment"), disposition);
        assertTrue(disposition.contains("docs.zip"), disposition);

        // 响应应是可流式写出的 Resource（长度未知，由容器 chunked 输出；真正写字节由 service.writeZip 完成）
        assertTrue(response.getBody() instanceof org.springframework.core.io.Resource);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ((org.springframework.core.io.Resource) response.getBody()).getInputStream().transferTo(sink);
        verify(service).writeZip(eq(zip), any());
    }

    @Test
    @DisplayName("Range 越界：416 + 真实总大小（bytes */N），而不是 500")
    void unsatisfiableRangeMaps416() {
        when(service.planDownload(eq(TOKEN), any(), any(), anyString()))
                .thenThrow(new PreviewService.UnsatisfiableRangeException(100L));

        ResponseEntity<?> response = controller.download(TOKEN, null, "bytes=5000-", request);

        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
        assertEquals("bytes */100", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }

    @Test
    @DisplayName("失效落地页：410 仍返回 text/html 中文页（不是 JSON），no-store，不泄露文件名")
    void landingInvalidReturnsChineseHtml() {
        when(service.landingHtml(TOKEN)).thenThrow(new BusinessException(410, "分享链接已被撤销"));
        when(service.landingStatus(any(BusinessException.class))).thenReturn(410);
        when(service.invalidLandingHtml(any(BusinessException.class)))
                .thenReturn("<html><body>分享已被取消</body></html>");

        ResponseEntity<String> response = controller.landing(TOKEN);

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML));
        assertTrue(response.getBody().contains("分享已被取消"));
        assertFalse(response.getBody().contains("hello.txt"));
        assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    @DisplayName("失效落地页：不存在也返回 404 的 HTML 页面")
    void landingNotFoundReturnsHtml() {
        when(service.landingHtml(TOKEN)).thenThrow(new BusinessException(404, "分享链接不存在"));
        when(service.landingStatus(any(BusinessException.class))).thenReturn(404);
        when(service.invalidLandingHtml(any(BusinessException.class)))
                .thenReturn("<html><body>分享链接不存在</body></html>");

        ResponseEntity<String> response = controller.landing(TOKEN);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    @DisplayName("PATCH：controller 原样透传 userId/linkId/请求体/客户端 IP")
    void patchDelegatesToService() {
        PatchShareRequest patch = new PatchShareRequest(null, null, null);
        ShareLinkView view = new ShareLinkView(5L, "t", "http://x/s/t", 9L, "a.txt", 10L,
                false, null, 0, 0, 0, OffsetDateTime.now(), "active", null,
                System.currentTimeMillis(), null, false);
        MockHttpServletRequest patchRequest = new MockHttpServletRequest();
        patchRequest.setRemoteAddr("9.9.9.9");
        when(service.patch(eq(1L), eq(5L), eq(patch), eq("9.9.9.9"))).thenReturn(view);

        assertEquals(view, controller.patch(1L, 5L, patch, patchRequest).getData());
        verify(service).patch(eq(1L), eq(5L), eq(patch), eq("9.9.9.9"));
    }
}
