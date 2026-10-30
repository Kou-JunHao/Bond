package com.bond.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-84：聊天文件读取四端点匿名可达（令牌在 query），但签发端点仍需登录。
 */
class ChatFilePublicEndpointsTest {

    @Test
    @DisplayName("四个读取端点都在白名单内")
    void readEndpointsArePublic() {
        assertTrue(PublicEndpoints.isPublic("GET", "/api/chat/files/meta"));
        assertTrue(PublicEndpoints.isPublic("GET", "/api/chat/files/content"));
        assertTrue(PublicEndpoints.isPublic("GET", "/api/chat/files/thumbnail"));
        assertTrue(PublicEndpoints.isPublic("GET", "/api/chat/files/office-pdf"));
    }

    @Test
    @DisplayName("签发令牌的 POST 端点不在白名单（必须登录）")
    void tokenIssuingIsNotPublic() {
        assertFalse(PublicEndpoints.isPublic("POST", "/api/chat/files/7/tokens"));
    }

    @Test
    @DisplayName("形状必须精确：子路径/多余字符/大小写不匹配都不放行")
    void exactShapeOnly() {
        assertFalse(PublicEndpoints.isPublic("GET", "/api/chat/files/meta/extra"));
        assertFalse(PublicEndpoints.isPublic("GET", "/api/chat/files/metax"));
        assertFalse(PublicEndpoints.isPublic("GET", "/api/chat/files/7/meta"));
        assertFalse(PublicEndpoints.isPublic("GET", "/api/Chat/Files/meta"));
    }

    @Test
    @DisplayName("路径穿越的聊天文件请求不放行")
    void traversalRejected() {
        assertFalse(PublicEndpoints.isPublic("GET", "/api/chat/files/../admin/meta"));
        assertFalse(PublicEndpoints.isPublic("GET", "/api/chat/files//content"));
        assertFalse(PublicEndpoints.isPublic("GET", "/api/chat/files/%2e%2e/content"));
    }

    @Test
    @DisplayName("非 GET 方法不放行（例如 HEAD/POST 到 content）")
    void otherMethodsRejected() {
        assertFalse(PublicEndpoints.isPublic("HEAD", "/api/chat/files/content"));
        assertFalse(PublicEndpoints.isPublic("POST", "/api/chat/files/content"));
    }
}
