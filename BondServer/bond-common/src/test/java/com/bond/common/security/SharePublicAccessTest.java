package com.bond.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分享匿名路径的白名单形状：只有 {@code /s/{43 字符 token}}、{@code /download}、POST {@code /verify}
 * 是公开的；登录态接口 {@code /api/shares} 继续走鉴权。
 */
class SharePublicAccessTest {

    private static final String TOKEN = "A".repeat(43);

    @Test
    @DisplayName("匿名路径：落地页/下载/提取码校验放行")
    void shareAnonymousPathsArePublic() {
        assertTrue(PublicEndpoints.isPublic("GET", "/s/" + TOKEN));
        assertTrue(PublicEndpoints.isPublic("GET", "/s/" + TOKEN + "/download"));
        assertTrue(PublicEndpoints.isPublic("POST", "/s/" + TOKEN + "/verify"));
    }

    @Test
    @DisplayName("登录态接口与非法形状：保持鉴权（不放行）")
    void nonAnonymousPathsAreNotPublic() {
        assertFalse(PublicEndpoints.isPublic("GET", "/api/shares"));
        assertFalse(PublicEndpoints.isPublic("POST", "/api/shares"));
        assertFalse(PublicEndpoints.isPublic("DELETE", "/api/shares/1"));
        assertFalse(PublicEndpoints.isPublic("POST", "/s/" + TOKEN));
        assertFalse(PublicEndpoints.isPublic("GET", "/s/short-token"));
        assertFalse(PublicEndpoints.isPublic("GET", "/s/" + TOKEN + "/download/extra"));
    }

    @Test
    @DisplayName("路径穿越/重复斜杠：白名单不放行")
    void traversalRejected() {
        assertFalse(PublicEndpoints.isPublic("GET", "/s/" + TOKEN + "/../download"));
        assertFalse(PublicEndpoints.isPublic("POST", "/s/" + TOKEN + "/verify/../x"));
        assertFalse(PublicEndpoints.isPublic("GET", "//s/" + TOKEN));
        assertFalse(PublicEndpoints.isPublic("GET", "/s/%2e%2e/" + TOKEN));
    }
}
