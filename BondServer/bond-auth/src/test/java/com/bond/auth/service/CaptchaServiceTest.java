package com.bond.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证码失败计数（task-68）：按「IP + 用户名」复合键计数，避免同出口 IP 下 A 用户输错
 * 把同 IP 的其他用户（含匿名）一起拖到必须验证码。
 */
class CaptchaServiceTest {

    private final CaptchaService service = new CaptchaService();

    @Test
    @DisplayName("同 IP 下 alice 输错两次 → alice 需要验证码；bob 与匿名不受影响")
    void perUserCounterDoesNotLockOtherUsers() {
        service.recordFail("203.0.113.7", "alice");
        service.recordFail("203.0.113.7", "alice");

        assertTrue(service.needsCaptcha("203.0.113.7", "alice"));
        assertFalse(service.needsCaptcha("203.0.113.7", "bob"), "bob 不应被 alice 的失败牵连");
        assertTrue(service.needsCaptcha("203.0.113.7", null),
                "匿名请求没有账号维度，按 IP 聚合键计数，仍会要求验证码（设计如此）");
    }

    @Test
    @DisplayName("匿名（无用户名）仍按 IP 计数，防止无差别撞库")
    void anonymousStillCountsPerIp() {
        service.recordFail("203.0.113.8", null);
        service.recordFail("203.0.113.8", "  ");

        assertTrue(service.needsCaptcha("203.0.113.8", null));
        assertFalse(service.needsCaptcha("203.0.113.9", null));
    }

    @Test
    @DisplayName("区分 IP：同一用户从不同 IP 失败互不叠加")
    void countersAreScopedByIp() {
        service.recordFail("203.0.113.10", "alice");
        service.recordFail("203.0.113.11", "alice");

        assertFalse(service.needsCaptcha("203.0.113.10", "alice"));
        assertFalse(service.needsCaptcha("203.0.113.11", "alice"));

        service.recordFail("203.0.113.10", "alice");
        assertTrue(service.needsCaptcha("203.0.113.10", "alice"));
    }

    @Test
    @DisplayName("登录成功只清自己的计数，别人的历史失败保留")
    void clearPerUserKeepsOtherUsers() {
        service.recordFail("203.0.113.12", "alice");
        service.recordFail("203.0.113.12", "alice");
        service.recordFail("203.0.113.12", "bob");
        service.recordFail("203.0.113.12", "bob");

        service.clearFails("203.0.113.12", "alice");

        assertFalse(service.needsCaptcha("203.0.113.12", "alice"));
        assertTrue(service.needsCaptcha("203.0.113.12", "bob"), "bob 的失败计数不应被清除");
    }

    @Test
    @DisplayName("按 IP 清理（兼容旧行为）：只清匿名聚合键，按账号的计数保留")
    void clearByIpOnlyClearsAnonymousAggregate() {
        service.recordFail("203.0.113.13", "alice");
        service.recordFail("203.0.113.13", "alice");
        service.recordFail("203.0.113.13", "bob");
        service.recordFail("203.0.113.13", "bob");

        service.clearFails("203.0.113.13");

        assertFalse(service.needsCaptcha("203.0.113.13", null), "匿名 IP 聚合键应被清除");
        assertTrue(service.needsCaptcha("203.0.113.13", "alice"), "按账号的失败计数不应被清除");
        assertTrue(service.needsCaptcha("203.0.113.13", "bob"));
    }
}
