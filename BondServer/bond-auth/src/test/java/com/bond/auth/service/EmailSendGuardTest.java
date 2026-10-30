package com.bond.auth.service;

import com.bond.common.exception.RateLimitedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 取码发信防洪（task-63）：匿名按 IP、登录态按用户、全局分钟/天、同收件人 24h，
 * 超限 429（RateLimitedException）且 Retry-After 为正。
 */
class EmailSendGuardTest {

    /** 可拨动的时钟（窗口过期测试）。 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-23T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static ConfigService config(Map<String, Integer> overrides) {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getInt(anyString(), anyInt())).thenAnswer(invocation ->
                overrides.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
        return configService;
    }

    @Test
    @DisplayName("匿名取码默认每分钟 2 次：第 3 次 429，其他 IP 不受影响")
    void anonymousIpMinuteLimit() {
        EmailSendGuard guard = new EmailSendGuard(config(Map.of()), new MutableClock());

        guard.check("a@example.com", "register", null, "1.1.1.1");
        guard.check("a@example.com", "register", null, "1.1.1.1");
        RateLimitedException ex = assertThrows(RateLimitedException.class,
                () -> guard.check("a@example.com", "register", null, "1.1.1.1"));
        assertTrue(ex.getRetryAfterSeconds() >= 1);

        // 不同 IP 独立计数
        guard.check("b@example.com", "register", null, "2.2.2.2");
    }

    @Test
    @DisplayName("登录态按用户计数（换 IP 不能绕过）")
    void loggedInUserUsesUserWindow() {
        EmailSendGuard guard = new EmailSendGuard(config(Map.of()), new MutableClock());

        guard.check("u@example.com", "change_email", 42L, "1.1.1.1");
        guard.check("u@example.com", "change_email", 42L, "2.2.2.2");
        assertThrows(RateLimitedException.class,
                () -> guard.check("u@example.com", "change_email", 42L, "3.3.3.3"));
    }

    @Test
    @DisplayName("全局每分钟上限（跨 IP/用户）")
    void globalMinuteLimit() {
        EmailSendGuard guard = new EmailSendGuard(
                config(Map.of("email.send.minute_limit", 2,
                        "email.send.ip_minute_limit", 100,
                        "email.send.ip_hourly_limit", 100)), new MutableClock());

        guard.check("a@example.com", "register", null, "1.1.1.1");
        guard.check("b@example.com", "register", null, "2.2.2.2");
        assertThrows(RateLimitedException.class,
                () -> guard.check("c@example.com", "register", null, "3.3.3.3"));
    }

    @Test
    @DisplayName("同一收件人 24h 上限（换 IP/用户也不能绕过）")
    void recipientDailyLimit() {
        EmailSendGuard guard = new EmailSendGuard(
                config(Map.of("email.send.recipient_daily_limit", 2,
                        "email.send.ip_minute_limit", 100,
                        "email.send.ip_hourly_limit", 100,
                        "email.send.minute_limit", 100,
                        "email.send.daily_limit", 100)), new MutableClock());

        guard.check("victim@example.com", "register", null, "1.1.1.1");
        guard.check("victim@example.com", "reset_password", null, "2.2.2.2");
        assertThrows(RateLimitedException.class,
                () -> guard.check("victim@example.com", "register", null, "3.3.3.3"));
    }

    @Test
    @DisplayName("窗口过期后重新放行")
    void windowExpires() {
        MutableClock clock = new MutableClock();
        EmailSendGuard guard = new EmailSendGuard(config(Map.of()), clock);

        guard.check("a@example.com", "register", null, "1.1.1.1");
        guard.check("a@example.com", "register", null, "1.1.1.1");
        assertThrows(RateLimitedException.class,
                () -> guard.check("a@example.com", "register", null, "1.1.1.1"));

        clock.advance(Duration.ofSeconds(61));
        guard.check("a@example.com", "register", null, "1.1.1.1");
    }

    @Test
    @DisplayName("日志脱敏 helper：a***@example.com")
    void maskHelper() {
        assertEquals("a***@example.com", EmailSendGuard.mask("alice@example.com"));
        assertEquals("***", EmailSendGuard.mask(null));
        assertEquals("unknown", EmailSendGuard.normalizeIp("  "));
    }
}
