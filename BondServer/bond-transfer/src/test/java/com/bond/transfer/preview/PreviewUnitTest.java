package com.bond.transfer.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-19 单元测试：尺寸白名单、类型分发、Range 解析、短期令牌（签发/绑定/过期/篡改）。
 */
class PreviewUnitTest {

    private static final String SECRET = "unit-test-preview-secret-0123456789abcdef";

    @Test
    @DisplayName("缩略图宽度只接受 64/128/320/640，非法值回退 320")
    void widthWhitelist() {
        assertEquals(64, ThumbnailService.resolveWidth(64));
        assertEquals(128, ThumbnailService.resolveWidth(128));
        assertEquals(320, ThumbnailService.resolveWidth(320));
        assertEquals(640, ThumbnailService.resolveWidth(640));
        assertEquals(320, ThumbnailService.resolveWidth(9999));
        assertEquals(320, ThumbnailService.resolveWidth(-1));
        assertEquals(320, ThumbnailService.resolveWidth((Integer) null));
        // task-21：w 按字符串宽内容错，w=abc 必须回退 320 而不是 500
        assertEquals(320, ThumbnailService.resolveWidth("abc"));
        assertEquals(320, ThumbnailService.resolveWidth(""));
        assertEquals(320, ThumbnailService.resolveWidth("  "));
        assertEquals(320, ThumbnailService.resolveWidth("9999"));
        assertEquals(640, ThumbnailService.resolveWidth("640"));
        assertEquals(320, ThumbnailService.resolveWidth((String) null));
    }

    @Test
    @DisplayName("类型分发：图片/PDF/视频/文本与未知")
    void kindDispatch() {
        assertEquals(ThumbnailService.Kind.IMAGE, ThumbnailService.detectKind("image/png", "a.png"));
        assertEquals(ThumbnailService.Kind.IMAGE, ThumbnailService.detectKind(null, "x.JPG"));
        assertEquals(ThumbnailService.Kind.PDF, ThumbnailService.detectKind("application/pdf", "a.pdf"));
        assertEquals(ThumbnailService.Kind.VIDEO, ThumbnailService.detectKind("video/mp4", "a.mp4"));
        assertEquals(ThumbnailService.Kind.VIDEO, ThumbnailService.detectKind(null, "movie.MKV"));
        assertEquals(ThumbnailService.Kind.UNSUPPORTED, ThumbnailService.detectKind("text/plain", "readme.txt"));
        assertEquals(ThumbnailService.Kind.UNSUPPORTED, ThumbnailService.detectKind(null, "archive.zip"));
    }

    @Test
    @DisplayName("ffmpeg 不存在：视频缩略图返回 null（接口层 204），不抛异常")
    void missingFfmpegReturnsNull() {
        ThumbnailService service = new ThumbnailService(null, "definitely-no-such-ffmpeg-binary-xyz");
        com.bond.common.entity.WorkspaceFile video = new com.bond.common.entity.WorkspaceFile();
        video.setId(1L);
        video.setFileName("a.mp4");
        video.setContentType("video/mp4");
        video.setFileSize(1024L);
        video.setMinioPath("transfers/1/a.mp4");
        assertEquals(null, service.getOrGenerate(video, 320));
    }

    @Test
    @DisplayName("Range：正常区间/开区间/后缀/越界/多区间回退")
    void rangeParsing() {
        RangeParser.Spec spec = RangeParser.parse("bytes=0-99", 1000);
        assertTrue(spec.partial());
        assertEquals(0, spec.offset());
        assertEquals(100, spec.length());

        spec = RangeParser.parse("bytes=900-", 1000);
        assertEquals(900, spec.offset());
        assertEquals(100, spec.length());

        spec = RangeParser.parse("bytes=-100", 1000);
        assertEquals(900, spec.offset());
        assertEquals(100, spec.length());

        spec = RangeParser.parse("bytes=0-99", 1000);
        assertEquals(100, spec.length());

        // 越界 → unsatisfiable（控制器 416）
        spec = RangeParser.parse("bytes=5000-", 1000);
        assertTrue(spec.unsatisfiable());

        // 多区间不支持 → 回退整文件 200
        spec = RangeParser.parse("bytes=0-99,200-299", 1000);
        assertFalse(spec.partial());
        assertEquals(1000, spec.length());

        // 无 Range → 整文件
        spec = RangeParser.parse(null, 1000);
        assertFalse(spec.partial());
        assertEquals(1000, spec.length());
    }

    @Test
    @DisplayName("预览令牌：正常解析、fileId/scope 绑定、篡改/过期拒绝")
    void previewTokens() {
        PreviewTokenService tokens = new PreviewTokenService(SECRET, 600);
        String token = tokens.issue(123L, 7L, "content");
        PreviewTokenService.Ticket ticket = tokens.resolve(token, 123L, "content").orElseThrow();
        assertEquals(123L, ticket.fileId());
        assertEquals(7L, ticket.userId());

        assertFalse(tokens.resolve(token, 999L, "content").isPresent(), "fileId binding");
        assertFalse(tokens.resolve(token, 123L, "thumb").isPresent(), "scope binding");
        assertFalse(tokens.resolve(token + "x", 123L, "content").isPresent(), "tampered signature");

        PreviewTokenService expired = new PreviewTokenService(SECRET, -1);
        String expiredToken = expired.issue(1L, 1L, "content");
        assertFalse(tokens.resolve(expiredToken, 1L, "content").isPresent(), "expired token");
    }

    @Test
    @DisplayName("预览接口放行规则：仅 token 形状的 content/thumbnail/preview 为公开路径")
    void publicEndpointShapes() {
        assertTrue(com.bond.common.security.PublicEndpoints.isPublic("GET", "/api/files/123/content"));
        assertTrue(com.bond.common.security.PublicEndpoints.isPublic("GET", "/api/files/123/thumbnail"));
        assertTrue(com.bond.common.security.PublicEndpoints.isPublic("GET", "/api/workspaces/9/files/123/content"));
        assertFalse(com.bond.common.security.PublicEndpoints.isPublic("GET", "/api/files/123/download"));
        assertFalse(com.bond.common.security.PublicEndpoints.isPublic("GET", "/api/files/123/content/x"));
        assertFalse(com.bond.common.security.PublicEndpoints.isPublic("POST", "/api/files/123/content"));
        // task-22：PDF 分页预览同样只放行精确形状
        assertTrue(com.bond.common.security.PublicEndpoints.isPublic("GET", "/api/files/123/preview"));
        assertTrue(com.bond.common.security.PublicEndpoints.isPublic("GET", "/api/workspaces/9/files/123/preview"));
        assertFalse(com.bond.common.security.PublicEndpoints.isPublic("GET", "/api/files/123/preview/x"));
        assertFalse(com.bond.common.security.PublicEndpoints.isPublic("POST", "/api/files/123/preview"));
    }

    @Test
    @DisplayName("PDF 页码：非法值（非数字/空/0/负数）回退第 1 页，正常页码原样保留")
    void pageResolution() {
        assertEquals(1, PreviewService.resolvePage(null));
        assertEquals(1, PreviewService.resolvePage(""));
        assertEquals(1, PreviewService.resolvePage("  "));
        assertEquals(1, PreviewService.resolvePage("abc"));
        assertEquals(1, PreviewService.resolvePage("0"));
        assertEquals(1, PreviewService.resolvePage("-3"));
        assertEquals(2, PreviewService.resolvePage("2"));
        assertEquals(3, PreviewService.resolvePage(" 3 "));
    }

    @Test
    @DisplayName("PDF 预览宽度：默认 640，非法值回退 640")
    void previewWidthResolution() {
        assertEquals(640, ThumbnailService.resolvePreviewWidth(null));
        assertEquals(640, ThumbnailService.resolvePreviewWidth("abc"));
        assertEquals(640, ThumbnailService.resolvePreviewWidth("9999"));
        assertEquals(64, ThumbnailService.resolvePreviewWidth("64"));
        assertEquals(640, ThumbnailService.resolvePreviewWidth("640"));
    }
}
