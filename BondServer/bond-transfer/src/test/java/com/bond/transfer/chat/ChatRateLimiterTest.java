package com.bond.transfer.chat;

import com.bond.transfer.service.ConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 聊天固定窗口限流单测（注入可控时钟，不依赖 ConfigService 的 DB 实现）。 */
class ChatRateLimiterTest {

    @Test
    @DisplayName("限流：前 N 次放行，第 N+1 次返回 1..60 秒 Retry-After")
    void allowsUpToLimitThenBlocks() {
        ConfigService config = mock(ConfigService.class);
        when(config.getInt(anyString(), anyInt())).thenReturn(3);
        ChatRateLimiter limiter = new ChatRateLimiter(config, () -> 1_000_000L);

        assertEquals(0L, limiter.acquire(1L));
        assertEquals(0L, limiter.acquire(1L));
        assertEquals(0L, limiter.acquire(1L));
        long retryAfter = limiter.acquire(1L);
        assertTrue(retryAfter >= 1L && retryAfter <= 60L, "retryAfter=" + retryAfter);
    }

    @Test
    @DisplayName("限流：配置为 0 时关闭")
    void zeroLimitDisables() {
        ConfigService config = mock(ConfigService.class);
        when(config.getInt(anyString(), anyInt())).thenReturn(0);
        ChatRateLimiter limiter = new ChatRateLimiter(config, () -> 1_000_000L);

        for (int i = 0; i < 1000; i++) {
            assertEquals(0L, limiter.acquire(1L));
        }
    }

    @Test
    @DisplayName("限流：跨分钟后窗口重置")
    void resetsOnNextMinute() {
        ConfigService config = mock(ConfigService.class);
        when(config.getInt(anyString(), anyInt())).thenReturn(2);
        AtomicLong clock = new AtomicLong(1_000_000L);
        ChatRateLimiter limiter = new ChatRateLimiter(config, clock::get);

        assertEquals(0L, limiter.acquire(1L));
        assertEquals(0L, limiter.acquire(1L));
        assertTrue(limiter.acquire(1L) > 0L);

        clock.addAndGet(60_000L);
        assertEquals(0L, limiter.acquire(1L));
    }
}
