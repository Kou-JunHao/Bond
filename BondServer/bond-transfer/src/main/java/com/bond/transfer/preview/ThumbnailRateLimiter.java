package com.bond.transfer.preview;

import com.bond.transfer.service.ConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 缩略图接口的按用户软限流（固定 1 分钟窗口）。
 *
 * <p>为什么只限缩略图：缩略图不计下行流量，是唯一"免费且可被刷"的面；客户端滚动列表会
 * 高频拉取缩略图，正常单用户每分钟几十次，默认 600 次/分只拦脚本/并发刷图之类明显异常。</p>
 *
 * <p>触发后由控制器返回 {@code 429 + Retry-After}（见 {@link PreviewService.ThumbnailRateLimitedException}），
 * 客户端应降级为类型图标，不得阻塞列表渲染；本限流不作用于内容流/预览（它们已计入流量）。</p>
 */
@Component
public class ThumbnailRateLimiter {

    static final String CFG_LIMIT_PER_MINUTE = "thumbnail.rate_limit_per_minute";
    static final int DEFAULT_LIMIT_PER_MINUTE = 600;
    /** 防止恶意构造海量 userId 把计数表撑爆；超过即清理上一分钟及更早的窗口。 */
    private static final int MAX_TRACKED_USERS = 100_000;

    private final ConfigService configService;
    private final LongSupplier clock;
    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public ThumbnailRateLimiter(ConfigService configService) {
        this(configService, System::currentTimeMillis);
    }

    /** 测试用：注入可控时钟。 */
    ThumbnailRateLimiter(ConfigService configService, LongSupplier clock) {
        this.configService = configService;
        this.clock = clock;
    }

    /**
     * 尝试为一次缩略图请求计数。
     *
     * @return {@code 0} 表示放行；大于 0 表示已超限，值为建议的 {@code Retry-After} 秒数
     */
    public long acquire(Long userId) {
        int limit = configService.getInt(CFG_LIMIT_PER_MINUTE, DEFAULT_LIMIT_PER_MINUTE);
        if (limit <= 0 || userId == null) {
            return 0L;
        }
        long now = clock.getAsLong();
        long currentMinute = now / 60_000L;
        if (windows.size() > MAX_TRACKED_USERS) {
            evictStale(currentMinute - 1L);
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

    private void evictStale(long cutoffMinute) {
        windows.entrySet().removeIf(entry -> entry.getValue().minute() < cutoffMinute);
    }

    record Window(long minute, AtomicInteger hits) {
    }
}
