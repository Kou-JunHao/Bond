package com.bond.transfer.share;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * token 不可枚举 + 提取码摘要：纯算法单测（不依赖 DB）。
 */
class ShareCodeTest {

    private static final String PEPPER = "test-pepper";

    @Test
    @DisplayName("token = 32 字节 secure random 的 base64url，43 字符且 URL 安全")
    void tokenShapeIsUrlSafe43Chars() {
        String token = ShareCode.newToken();
        assertEquals(43, token.length());
        assertTrue(token.matches("^[A-Za-z0-9_-]{43}$"));
        assertTrue(ShareCode.isWellFormedToken(token));
        assertFalse(ShareCode.isWellFormedToken("short"));
        assertFalse(ShareCode.isWellFormedToken(token + "!"));
        assertFalse(ShareCode.isWellFormedToken(null));
    }

    @Test
    @DisplayName("大量生成不重复（不可枚举性的最小回归）")
    void tokensAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            assertTrue(seen.add(ShareCode.newToken()));
        }
    }

    @Test
    @DisplayName("提取码摘要：确定性 hex(64)，绑定 token，不泄露明文")
    void hashIsDeterministicAndBoundToToken() {
        String tokenA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String tokenB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String hashA = ShareCode.hashCode(PEPPER, tokenA, "1234");
        assertEquals(64, hashA.length());
        assertTrue(hashA.matches("^[0-9a-f]{64}$"));
        assertEquals(hashA, ShareCode.hashCode(PEPPER, tokenA, "1234"));
        assertNotEquals(hashA, ShareCode.hashCode(PEPPER, tokenA, "1235"));
        // 同一提取码在不同链接上摘要不同（防彩虹表跨链接复用）
        assertNotEquals(hashA, ShareCode.hashCode(PEPPER, tokenB, "1234"));
        assertNotEquals(hashA, ShareCode.hashCode("other-pepper", tokenA, "1234"));
        assertFalse(hashA.contains("1234"));
    }

    @Test
    @DisplayName("常量时间校验：正确通过、错误/缺失拒绝")
    void verifyCode() {
        String token = ShareCode.newToken();
        String hash = ShareCode.hashCode(PEPPER, token, "abcd");
        assertTrue(ShareCode.verifyCode(PEPPER, token, "abcd", hash));
        assertFalse(ShareCode.verifyCode(PEPPER, token, "abce", hash));
        assertFalse(ShareCode.verifyCode(PEPPER, token, null, hash));
        assertFalse(ShareCode.verifyCode(PEPPER, token, "abcd", null));
        assertFalse(ShareCode.verifyCode(null, token, "abcd", hash));
    }
}
