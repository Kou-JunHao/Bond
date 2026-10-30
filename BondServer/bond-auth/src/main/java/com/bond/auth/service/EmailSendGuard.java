package com.bond.auth.service;

import com.bond.common.exception.RateLimitedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 取码接口的发信防洪（task-63 硬化）。
 *
 * <p>三层限制，全部可用 {@code system_configs} 调整；计数采用单 JVM 内存滑动窗口：</p>
 * <ol>
 *   <li>按来源：匿名（register / reset_password）按 IP，登录态（change_email）按用户；
 *       默认每分钟 2 次、每小时 10 次；</li>
 *   <li>全局熔断：每分钟 20 次、每天 100 次（Resend 免费档 100 封/天，默认值刻意压低）；</li>
 *   <li>同一收件人：24h 内所有 purpose 合计 20 封。</li>
 * </ol>
 *
 * <p><b>多实例前提</b>：窗口在进程内存里，多实例部署时各实例独立计数、上限会被放大；
 * 若将来横向扩容需换成 PG/Redis 计数（本期单实例部署，暂不引入）。</p>
 *
 * <p>安全：日志只打 purpose / 来源 / 掩码收件人，绝不打完整邮箱或验证码。</p>
 */
@Slf4j
@Service
public class EmailSendGuard {

    private static final long MINUTE_MILLIS = 60_000L;
    private static final long HOUR_MILLIS = 3_600_000L;
    private static final long DAY_MILLIS = 86_400_000L;
    /** 惰性清理阈值：窗口 key 超过该值才做一次全量清理，避免每次请求都 O(n)。 */
    private static final int MAX_WINDOW_KEYS = 20_000;

    private final ConfigService configService;
    private final Clock clock;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /** Spring 注入用（另一个构造器是测试注入可控时钟的，两个构造器必须显式指定一个给 Spring，见 pitfalls #20）。 */
    @Autowired
    public EmailSendGuard(ConfigService configService) {
        this(configService, Clock.systemUTC());
    }

    /** 测试注入可控时钟（窗口过期验证）。 */
    EmailSendGuard(ConfigService configService, Clock clock) {
        this.configService = configService;
        this.clock = clock;
    }

    /**
     * 校验并登记一次取码请求。超限抛 {@link RateLimitedException}（HTTP 429 + Retry-After），
     * 调用方在异常时不得继续发信。
     */
    public synchronized void check(String normalizedEmail, String purpose, Long userId, String ip) {
        long now = clock.millis();
        String actor = userId == null ? "ip:" + normalizeIp(ip) : "user:" + userId;

        int minuteLimit = positive(configService.getInt("email.send.minute_limit", 20));
        int dailyLimit = positive(configService.getInt("email.send.daily_limit", 100));

        List<Limit> limits = new ArrayList<>(5);
        if (userId == null) {
            limits.add(new Limit(actor + ":m", positive(configService.getInt("email.send.ip_minute_limit", 2)), MINUTE_MILLIS));
            limits.add(new Limit(actor + ":h", positive(configService.getInt("email.send.ip_hourly_limit", 10)), HOUR_MILLIS));
        } else {
            limits.add(new Limit(actor + ":m", positive(configService.getInt("email.send.user_minute_limit", 2)), MINUTE_MILLIS));
            limits.add(new Limit(actor + ":h", positive(configService.getInt("email.send.user_hourly_limit", 10)), HOUR_MILLIS));
        }
        limits.add(new Limit("global:m", minuteLimit, MINUTE_MILLIS));
        limits.add(new Limit("global:d", dailyLimit, DAY_MILLIS));
        limits.add(new Limit("rcpt:" + normalizedEmail,
                positive(configService.getInt("email.send.recipient_daily_limit", 20)), DAY_MILLIS));

        // 先全部校验再登记，避免「先扣了 A 额度、B 超限」造成多扣。
        for (Limit limit : limits) {
            Deque<Long> window = windows.computeIfAbsent(limit.key, k -> new ArrayDeque<>());
            long floor = now - limit.windowMillis;
            int count = 0;
            long oldest = now;
            for (Long timestamp : window) {
                if (timestamp >= floor) {
                    count++;
                    oldest = Math.min(oldest, timestamp);
                }
            }
            if (count >= limit.limit) {
                long retryAfterSeconds = Math.max(1L, (oldest + limit.windowMillis - now + 999L) / 1000L);
                log.warn("email send throttled: purpose={} actor={} recipient={} limit={} retryAfter={}s",
                        purpose, actor, mask(normalizedEmail), limit.key, retryAfterSeconds);
                throw new RateLimitedException("发信过于频繁，请稍后再试", retryAfterSeconds);
            }
        }

        long pruneFloor = now - DAY_MILLIS;
        for (Limit limit : limits) {
            Deque<Long> window = windows.computeIfAbsent(limit.key, k -> new ArrayDeque<>());
            window.addLast(now);
            while (!window.isEmpty() && window.peekFirst() < pruneFloor) {
                window.removeFirst();
            }
        }
        if (windows.size() > MAX_WINDOW_KEYS) {
            windows.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }
    }

    private static int positive(int value) {
        return Math.max(1, value);
    }

    static String normalizeIp(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip.trim();
    }

    /** 日志脱敏：a***@example.com。 */
    static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    private record Limit(String key, int limit, long windowMillis) {
    }
}
