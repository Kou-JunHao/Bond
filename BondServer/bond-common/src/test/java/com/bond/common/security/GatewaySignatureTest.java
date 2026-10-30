package com.bond.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务间 HMAC 签名单测（task-9 P0）：正常验签、篡改、过期、错误密钥、缺失密钥 fail-fast。
 */
class GatewaySignatureTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef"; // >=32 chars

    @Test
    @DisplayName("签名可被同一密钥验证通过")
    void signThenVerify() {
        long now = System.currentTimeMillis();
        String signature = GatewaySignature.sign(SECRET, 42L, now, "nonce-1");
        assertTrue(GatewaySignature.verify(SECRET, "42", String.valueOf(now), "nonce-1", signature));
    }

    @Test
    @DisplayName("篡改 userId / nonce / 签名、错误密钥均验签失败")
    void tamperingRejected() {
        long now = System.currentTimeMillis();
        String signature = GatewaySignature.sign(SECRET, 42L, now, "nonce-1");
        assertFalse(GatewaySignature.verify(SECRET, "43", String.valueOf(now), "nonce-1", signature));
        assertFalse(GatewaySignature.verify(SECRET, "42", String.valueOf(now), "nonce-2", signature));
        assertFalse(GatewaySignature.verify(SECRET, "42", String.valueOf(now), "nonce-1", "deadbeef"));
        assertFalse(GatewaySignature.verify("another-secret-0123456789abcdef", "42",
                String.valueOf(now), "nonce-1", signature));
    }

    @Test
    @DisplayName("时间窗 ±60s：过期/未来时间戳拒绝")
    void expiredTimestampRejected() {
        long old = System.currentTimeMillis() - GatewaySignature.MAX_CLOCK_SKEW_MS - 5_000L;
        String oldSignature = GatewaySignature.sign(SECRET, 42L, old, "n1");
        assertFalse(GatewaySignature.verify(SECRET, "42", String.valueOf(old), "n1", oldSignature));

        long future = System.currentTimeMillis() + GatewaySignature.MAX_CLOCK_SKEW_MS + 5_000L;
        String futureSignature = GatewaySignature.sign(SECRET, 42L, future, "n2");
        assertFalse(GatewaySignature.verify(SECRET, "42", String.valueOf(future), "n2", futureSignature));
    }

    @Test
    @DisplayName("畸形头（非数字 userId/timestamp、空值）验签失败而不是抛异常")
    void malformedHeadersRejected() {
        assertFalse(GatewaySignature.verify(SECRET, "abc", "123", "n", "sig"));
        assertFalse(GatewaySignature.verify(SECRET, "42", "abc", "n", "sig"));
        assertFalse(GatewaySignature.verify(SECRET, null, null, null, null));
        assertFalse(GatewaySignature.verify(SECRET, "42", String.valueOf(System.currentTimeMillis()), "", "sig"));
    }

    @Test
    @DisplayName("INTERNAL_GATEWAY_SECRET 缺失/过短：启动 fail-fast，绝不回退默认值")
    void missingOrWeakSecretFailsFast() {
        assertThrows(IllegalStateException.class, () -> GatewaySignature.requireSecret(() -> null));
        assertThrows(IllegalStateException.class, () -> GatewaySignature.requireSecret(() -> "   "));
        assertThrows(IllegalStateException.class, () -> GatewaySignature.requireSecret(() -> "short-secret"));
        assertEquals(SECRET, GatewaySignature.requireSecret(() -> SECRET));
    }

    @Test
    @DisplayName("白名单精确匹配：方法+路径，不做宽松 startsWith")
    void publicEndpointMatchingIsExact() {
        assertTrue(PublicEndpoints.isPublic("POST", "/api/auth/login"));
        assertTrue(PublicEndpoints.isPublic("GET", "/api/auth/captcha"));
        assertTrue(PublicEndpoints.isPublic("GET", "/api/invite/abc-token"));
        assertTrue(PublicEndpoints.isPublic("GET", "/api/transfer/avatar/42"));
        // 方法不匹配
        assertFalse(PublicEndpoints.isPublic("GET", "/api/auth/login"));
        assertFalse(PublicEndpoints.isPublic("POST", "/api/transfer/avatar/42"));
        // 前缀绕过
        assertFalse(PublicEndpoints.isPublic("GET", "/api/inviteX"));
        assertFalse(PublicEndpoints.isPublic("GET", "/api/invite/../admin/users"));
        // 普通受保护接口
        assertFalse(PublicEndpoints.isPublic("GET", "/api/files"));
        assertFalse(PublicEndpoints.isPublic("POST", "/api/admin/grant"));
        assertFalse(PublicEndpoints.isPublic("POST", "/api/redeem"));
        // task-29：权益/用量接口必须「登录 + 网关签名」双重校验，绝不能进公开白名单
        assertFalse(PublicEndpoints.isPublic("GET", "/api/entitlements/me"));
        assertFalse(PublicEndpoints.isPublic("GET", "/api/entitlements/me/"));
        assertFalse(PublicEndpoints.isPublic("POST", "/api/entitlements/me"));
    }
}
