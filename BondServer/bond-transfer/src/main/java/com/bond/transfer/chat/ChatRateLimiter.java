package com.bond.transfer.chat;

import com.bond.transfer.service.ConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 聊天发送的每用户固定一分钟窗口限流（与缩略图限流同款实现）。
 * <p>配置键 {@code chat.rate_limit_per_minute}，0=关闭；超限由
 * {@link com.bond.common.exception.RateLimitedException} → 429 + Retry-After。</p>
 */
@Component
public class ChatRateLimiter {

    static final String CFG_LIMIT_PER_MINUTE = "chat.rate_limit_per_minute";
    static final int DEFAULT_LIMIT_PER_MINUTE = 30;
    private static final int MAX_TRACKED_USERS = 100_000;

    private final ConfigService configService;
    private final LongSupplier clock;
    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public ChatRateLimiter(ConfigService configService) {
        this(configService, System::currentTimeMillis);
    }

    /** 测试用：注入可控时钟。 */
    ChatRateLimiter(ConfigService configService, LongSupplier clock) {
        this.configService = configService;
        this.clock = clock;
    }

    /**
     * @return 0 表示放行；大于 0 表示已超限，值为建议的 Retry-After 秒数。
     */
    public long acquire(Long userId) {
        int limit = configService.getInt(CFG_LIMIT_PER_MINUTE, DEFAULT_LIMIT_PER_MINUTE);
        if (limit <= 0 || userId == null) {
            return 0L;
        }
        long now = clock.getAsLong();
        long currentMinute = now / 60_000L;
        if (windows.size() > MAX_TRACKED_USERS) {
            windows.entrySet().removeIf(entry -> entry.getValue().minute() < currentMinute - 1L);
        }
        Window window = windows.compute(userId, (id, existing) ->
                existing == null || existing.minute() != currentMinute
                        ? new Window(currentMinute, new AtomicInteger())
                        : existing);
        if (window.hits().incrementAndGet() <= limit) {
            return 0L;
        }
        long secondsLeft = 60L - (now % 60_000L) / 1000L;
        return Math.max(1L, secondsLeft);
    }

    record Window(long minute, AtomicInteger hits) {
    }
}
