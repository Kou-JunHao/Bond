package com.bond.transfer.preview;

import com.bond.transfer.service.ConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缩略图软限流：每用户每分钟 N 次（默认 600），超限返回 Retry-After 秒数；0 表示关闭。
 *
 * <p>限流只作用于缩略图（不计流量的免费面），内容流/预览/下载走计量，不在这里限。</p>
 */
class ThumbnailRateLimiterTest {

    private static final long USER_ID = 42L;

    private final ConfigService configService = mock(ConfigService.class);
    private final AtomicLong now = new AtomicLong(0L);

    @Test
    @DisplayName("达到上限前放行，超过后返回 Retry-After 秒数")
    void allowsUpToLimitThenRejects() {
        ThumbnailRateLimiter limiter = limiter(3);

        assertEquals(0L, limiter.acquire(USER_ID));
        assertEquals(0L, limiter.acquire(USER_ID));
        assertEquals(0L, limiter.acquire(USER_ID));

        now.set(30_000L); // 窗口内第 30 秒
        long retryAfter = limiter.acquire(USER_ID);
        assertEquals(30L, retryAfter, "Retry-After 应指向窗口结束");
    }

    @Test
    @DisplayName("进入下一分钟窗口后重新放行")
    void resetsInNextWindow() {
        ThumbnailRateLimiter limiter = limiter(1);
        assertEquals(0L, limiter.acquire(USER_ID));
        assertTrue(limiter.acquire(USER_ID) > 0L);

        now.set(60_000L);
        assertEquals(0L, limiter.acquire(USER_ID));
    }

    @Test
    @DisplayName("不同用户各自计数，互不影响")
    void usersAreIsolated() {
        ThumbnailRateLimiter limiter = limiter(1);

        assertEquals(0L, limiter.acquire(USER_ID));
        assertEquals(0L, limiter.acquire(7L));
        assertTrue(limiter.acquire(USER_ID) > 0L);
        assertTrue(limiter.acquire(7L) > 0L);
    }

    @Test
    @DisplayName("限额配置为 0 时关闭限流")
    void zeroDisablesLimiter() {
        ThumbnailRateLimiter limiter = limiter(0);
        for (int i = 0; i < 100; i++) {
            assertEquals(0L, limiter.acquire(USER_ID));
        }
    }

    private ThumbnailRateLimiter limiter(int limitPerMinute) {
        when(configService.getInt(ThumbnailRateLimiter.CFG_LIMIT_PER_MINUTE,
                ThumbnailRateLimiter.DEFAULT_LIMIT_PER_MINUTE)).thenReturn(limitPerMinute);
        return new ThumbnailRateLimiter(configService, now::get);
    }
}
