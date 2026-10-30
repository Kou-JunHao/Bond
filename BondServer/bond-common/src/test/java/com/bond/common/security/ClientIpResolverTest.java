package com.bond.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实客户端 IP 解析（task-68）：只信任来自已知代理的转发头，直连来源伪造的头必须被忽略。
 */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(ClientIpResolver.DEFAULT_TRUSTED_PROXIES);

    @Test
    @DisplayName("非信任来源：客户端自写 X-Real-IP/X-Forwarded-For 一律忽略，用直连地址")
    void directClientCannotSpoofHeaders() {
        assertEquals("203.0.113.7",
                resolver.resolve("203.0.113.7", "1.1.1.1, 2.2.2.2", "8.8.8.8"));
    }

    @Test
    @DisplayName("信任代理：X-Real-IP 优先")
    void trustedProxyPrefersRealIp() {
        assertEquals("198.51.100.9",
                resolver.resolve("127.0.0.1", "9.9.9.9", "198.51.100.9"));
    }

    @Test
    @DisplayName("信任代理：无 X-Real-IP 时取 X-Forwarded-For 最右侧非可信跳（忽略客户端伪造的最左值）")
    void trustedProxyFallsBackToRightmostNonTrusted() {
        // 客户端伪造 "8.8.8.8"，nginx 在右侧追加真实客户端 203.0.113.7
        assertEquals("203.0.113.7",
                resolver.resolve("172.18.0.5", "8.8.8.8, 203.0.113.7", null));
        // 右侧是已知代理跳时继续向左找第一个非可信地址
        assertEquals("203.0.113.7",
                resolver.resolve("172.18.0.5", "1.1.1.1, 203.0.113.7, 172.18.0.9", null));
    }

    @Test
    @DisplayName("XFF 全是可信代理时回退到最右有效值")
    void allTrustedXffFallsBackToRightmost() {
        assertEquals("127.0.0.1",
                resolver.resolve("172.18.0.5", "172.18.0.9, 127.0.0.1", null));
    }

    @Test
    @DisplayName("内部地址识别：回环/私有/链路本地/ULA 均为内部，公网为外部")
    void internalAddressDetection() {
        assertTrue(ClientIpResolver.isInternalIp("127.0.0.1"));
        assertTrue(ClientIpResolver.isInternalIp("172.18.0.1"));
        assertTrue(ClientIpResolver.isInternalIp("10.1.2.3"));
        assertTrue(ClientIpResolver.isInternalIp("192.168.1.10"));
        assertTrue(ClientIpResolver.isInternalIp("169.254.169.254"));
        assertTrue(ClientIpResolver.isInternalIp("::1"));
        assertTrue(ClientIpResolver.isInternalIp("fd00::1"));
        assertFalse(ClientIpResolver.isInternalIp("203.0.113.7"));
        assertFalse(ClientIpResolver.isInternalIp("8.8.8.8"));
        assertFalse(ClientIpResolver.isInternalIp(""));
    }

    @Test
    @DisplayName("IPv4-mapped IPv6 直连地址（::ffff:127.0.0.1）按信任代理处理，并回退到直连地址")
    void ipv4MappedAddressIsNormalized() {
        assertEquals("203.0.113.7",
                resolver.resolve("::ffff:203.0.113.7", null, null));
        assertEquals("203.0.113.7",
                resolver.resolve("::ffff:127.0.0.1", "203.0.113.7", null));
    }

    @Test
    @DisplayName("非法转发头不采用，回退直连地址")
    void garbageHeaderFallsBackToRemote() {
        assertEquals("127.0.0.1",
                resolver.resolve("127.0.0.1", "not-an-ip, ???", "not-an-ip"));
    }

    @Test
    @DisplayName("自定义网段：10.0.0.0/8 内才信任")
    void customTrustedCidr() {
        ClientIpResolver internal = new ClientIpResolver("10.0.0.0/8");
        assertTrue(internal.isTrusted("10.1.2.3"));
        assertFalse(internal.isTrusted("172.18.0.5"));
        assertEquals("198.51.100.9", internal.resolve("10.1.2.3", "198.51.100.9", null));
        assertEquals("172.18.0.5", internal.resolve("172.18.0.5", "198.51.100.9", null));
    }

    @Test
    @DisplayName("默认信任 127.0.0.1/::1/172.18.0.0/16，不信任公网地址")
    void defaultTrustedProxies() {
        assertTrue(resolver.isTrusted("127.0.0.1"));
        assertTrue(resolver.isTrusted("172.18.9.9"));
        assertFalse(resolver.isTrusted("8.8.8.8"));
        assertFalse(resolver.isTrusted(""));
    }

    @Test
    @DisplayName("头顺序：X-Real-IP 优先于 X-Forwarded-For；空 remote 返回空")
    void precedenceAndEmptyRemote() {
        assertEquals("198.51.100.9",
                resolver.resolve("127.0.0.1", "9.9.9.9", "198.51.100.9"));
        assertEquals("", resolver.resolve(null, "9.9.9.9", "9.9.9.9"));
    }
}
