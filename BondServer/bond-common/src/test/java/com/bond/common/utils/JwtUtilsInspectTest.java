package com.bond.common.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JwtUtils 的 token 状态分类（task-126）：网关据此返回可区分的 401 message，
 * 客户端才能决定「跳登录」还是「提示无权限」。
 */
class JwtUtilsInspectTest {

    @Test
    void validAccessTokenIsValid() {
        String token = JwtUtils.generateAccessToken(42L, "tester");

        assertEquals(JwtUtils.TokenState.VALID, JwtUtils.inspect(token));
        assertEquals(42L, JwtUtils.getUserId(token));
    }

    @Test
    void expiredTokenIsExpired() {
        String token = JwtUtils.generateToken(42L, "tester", -1000L);

        assertEquals(JwtUtils.TokenState.EXPIRED, JwtUtils.inspect(token));
    }

    @Test
    void garbageIsMalformed() {
        assertEquals(JwtUtils.TokenState.MALFORMED, JwtUtils.inspect("not-a-jwt"));
        assertEquals(JwtUtils.TokenState.MALFORMED, JwtUtils.inspect(""));
        // 结构像 JWT 但内容非法：不是 VALID 即可（具体归为 MALFORMED 或 INVALID 都合理）
        assertNotEquals(JwtUtils.TokenState.VALID, JwtUtils.inspect("a.b.c"));
    }

    @Test
    @DisplayName("篡改签名必须判为 SIGNATURE_INVALID（不是 VALID）")
    void tamperedSignatureIsInvalid() {
        String token = JwtUtils.generateAccessToken(42L, "tester");
        char last = token.charAt(token.length() - 1);
        char replacement = last == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replacement;

        assertEquals(JwtUtils.TokenState.SIGNATURE_INVALID, JwtUtils.inspect(tampered));
    }

    @Test
    void generateTokenWithTtlControlsExpiry() {
        String soon = JwtUtils.generateToken(7L, "tester", 60_000L);
        assertTrue(JwtUtils.parseToken(soon).getExpiration().getTime() > System.currentTimeMillis());
        assertEquals(JwtUtils.TokenState.VALID, JwtUtils.inspect(soon));
    }

    @Test
    void payloadContainsSubjectAndUsername() {
        String token = JwtUtils.generateAccessToken(99L, "Alice");
        var claims = JwtUtils.parseToken(token);
        assertEquals("Alice", claims.get("username", String.class));

        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"sub\":\"99\""), payload);
    }
}
