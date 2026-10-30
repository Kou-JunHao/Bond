package com.bond.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-87：Web 面 favicon 的匿名白名单与静态资源 content-type 契约。
 *
 * <p>白名单只放行精确的 {@code GET /favicon.svg}：其他方法、带子路径/尾斜杠的变体都不放行，
 * 避免把 {@code /favicon.svg/...} 当成匿名前缀。</p>
 */
class FaviconPublicEndpointsTest {

    @Test
    @DisplayName("GET /favicon.svg 匿名可访问；POST/子路径/尾斜杠不放行")
    void faviconIsPublicExactGetOnly() {
        assertTrue(PublicEndpoints.isPublic("GET", "/favicon.svg"));
        assertFalse(PublicEndpoints.isPublic("POST", "/favicon.svg"));
        assertFalse(PublicEndpoints.isPublic("GET", "/favicon.svg/"));
        assertFalse(PublicEndpoints.isPublic("GET", "/favicon.svg/x"));
        assertFalse(PublicEndpoints.isPublic("GET", "/static/favicon.svg"));
    }

    @Test
    @DisplayName("Spring 静态资源按扩展名推断 image/svg+xml（无需手工 Content-Type）")
    void svgFilesResolveToSvgMediaType() {
        MediaType mediaType = MediaTypeFactory.getMediaType("favicon.svg").orElseThrow();
        assertEquals(MediaType.parseMediaType("image/svg+xml"), mediaType);
    }
}
