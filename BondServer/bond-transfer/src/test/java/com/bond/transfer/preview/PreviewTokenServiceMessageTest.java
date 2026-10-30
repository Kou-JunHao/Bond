package com.bond.transfer.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聊天文件令牌（task-84 方案 C）：payload 根绑 messageId，与文件令牌互不通用。
 */
class PreviewTokenServiceMessageTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final PreviewTokenService TOKENS = new PreviewTokenService(SECRET, 600L);

    @Test
    @DisplayName("签发/解析：messageId 与 userId 正确回读")
    void roundTrip() {
        String token = TOKENS.issueForMessage(7L, 9L, PreviewTokenService.SCOPE_CHAT_META);
        Optional<PreviewTokenService.MessageTicket> ticket = TOKENS.resolveForMessage(
                token, PreviewTokenService.SCOPE_CHAT_META);
        assertTrue(ticket.isPresent());
        assertEquals(7L, ticket.get().messageId());
        assertEquals(9L, ticket.get().userId());
        assertEquals(PreviewTokenService.SCOPE_CHAT_META, ticket.get().scope());
    }

    @Test
    @DisplayName("scope 不符：chat-meta 令牌不能当 chat-content 用")
    void wrongScopeRejected() {
        String token = TOKENS.issueForMessage(7L, 9L, PreviewTokenService.SCOPE_CHAT_META);
        assertTrue(TOKENS.resolveForMessage(token, PreviewTokenService.SCOPE_CHAT_CONTENT).isEmpty());
        assertTrue(TOKENS.resolveForMessage(token, PreviewTokenService.SCOPE_CHAT_THUMB).isEmpty());
    }

    @Test
    @DisplayName("文件令牌与聊天令牌不能串用（文件端点只接受 content/thumb 等文件 scope）")
    void fileAndMessageTokensAreIsolated() {
        String fileToken = TOKENS.issue(7L, 9L, PreviewTokenService.SCOPE_CONTENT);
        assertTrue(TOKENS.resolveForMessage(fileToken, PreviewTokenService.SCOPE_CHAT_CONTENT).isEmpty());

        String chatToken = TOKENS.issueForMessage(7L, 9L, PreviewTokenService.SCOPE_CHAT_CONTENT);
        assertTrue(TOKENS.resolve(chatToken, 7L, PreviewTokenService.SCOPE_CONTENT).isEmpty());
        assertTrue(TOKENS.resolve(chatToken, 7L, PreviewTokenService.SCOPE_THUMB).isEmpty());
    }

    @Test
    @DisplayName("过期令牌被拒绝")
    void expiredRejected() {
        PreviewTokenService shortLived = new PreviewTokenService(SECRET, -1L);
        String token = shortLived.issueForMessage(7L, 9L, PreviewTokenService.SCOPE_CHAT_CONTENT);
        assertTrue(shortLived.resolveForMessage(token, PreviewTokenService.SCOPE_CHAT_CONTENT).isEmpty());
    }

    @Test
    @DisplayName("篡改/垃圾令牌被拒绝且不抛异常")
    void tamperedRejected() {
        String token = TOKENS.issueForMessage(7L, 9L, PreviewTokenService.SCOPE_CHAT_META);
        assertTrue(TOKENS.resolveForMessage(token + "x", PreviewTokenService.SCOPE_CHAT_META).isEmpty());
        assertTrue(TOKENS.resolveForMessage("not-a-token", PreviewTokenService.SCOPE_CHAT_META).isEmpty());
        assertTrue(TOKENS.resolveForMessage("", PreviewTokenService.SCOPE_CHAT_META).isEmpty());
        assertTrue(TOKENS.resolveForMessage(null, PreviewTokenService.SCOPE_CHAT_META).isEmpty());
        // 篡改 payload（前半段）后签名不再匹配
        int dot = token.indexOf('.');
        String flipped = (token.charAt(0) == 'A' ? "B" : "A") + token.substring(1);
        assertFalse(TOKENS.resolveForMessage(flipped.substring(0, dot) + token.substring(dot),
                PreviewTokenService.SCOPE_CHAT_META).isPresent());
    }

    @Test
    @DisplayName("默认 TTL 暴露为 600 秒")
    void ttlExposed() {
        assertEquals(600L, TOKENS.getTtlSeconds());
    }
}
