package com.bond.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** task-159：邀请落地页 /i/{token} 的白名单形状（精确长度、仅 GET、防穿越）。 */
class InviteLandingPublicEndpointsTest {

    private static final String TOKEN_32 = "0123456789abcdef0123456789abcdef";
    private static final String TOKEN_43 = "0123456789abcdef0123456789abcdefAbCdEfGhIjK";

    @Test
    @DisplayName("GET /i/{32} 与 /i/{43} 匿名公开")
    void inviteLandingIsPublic() {
        assertTrue(PublicEndpoints.isPublic("GET", "/i/" + TOKEN_32));
        assertTrue(PublicEndpoints.isPublic("GET", "/i/" + TOKEN_43));
    }

    @Test
    @DisplayName("长度/字符不精确的 /i/ 路径不公开")
    void impreciseShapesAreRejected() {
        assertFalse(PublicEndpoints.isPublic("GET", "/i/"), "空 token 不应公开");
        assertFalse(PublicEndpoints.isPublic("GET", "/i/" + TOKEN_32 + "x"), "33 长度不应公开");
        assertFalse(PublicEndpoints.isPublic("GET", "/i/" + TOKEN_32.substring(1)), "31 长度不应公开");
        assertFalse(PublicEndpoints.isPublic("GET", "/i/not-a-token!"), "非法字符不应公开");
        assertFalse(PublicEndpoints.isPublic("GET", "/api/inviteX/" + TOKEN_32));
    }

    @Test
    @DisplayName("仅 GET 公开；POST/DELETE 仍需鉴权")
    void onlyGetIsPublic() {
        assertFalse(PublicEndpoints.isPublic("POST", "/i/" + TOKEN_32));
        assertFalse(PublicEndpoints.isPublic("DELETE", "/i/" + TOKEN_32));
    }

    @Test
    @DisplayName("穿越/编码绕过仍然被拒绝")
    void traversalStillRejected() {
        assertFalse(PublicEndpoints.isPublic("GET", "/i/../" + TOKEN_32));
        assertFalse(PublicEndpoints.isPublic("GET", "/i/%2e%2e/" + TOKEN_32));
        assertFalse(PublicEndpoints.isPublic("GET", "/i//" + TOKEN_32));
    }

    @Test
    @DisplayName("旧 JSON 端点 /api/invite/{token} 仍按前缀公开（客户端在用，不改行为）")
    void legacyJsonEndpointStillPublic() {
        assertTrue(PublicEndpoints.isPublic("GET", "/api/invite/" + TOKEN_32));
        assertTrue(PublicEndpoints.isPublic("GET", "/api/invite/" + TOKEN_32 + "/anything"));
    }
}
