package com.bond.transfer.config;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.SecurityBuilder;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.RequestRejectedException;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WebDAV 自定义方法被 StrictHttpFirewall 拒成 400 空体的回归测试。
 *
 * <p>MockMvc 默认不过 Spring Security 过滤器链，测不到这个问题，所以这里直接
 * 测 {@link SecurityConfig#httpFirewall()} 产出的防火墙 bean，并验证
 * {@link SecurityConfig#webDavHttpFirewallCustomizer(HttpFirewall)} 会把它装到
 * {@link WebSecurity} 上（只声明 HttpFirewall bean 在注解配置下不会自动生效）。</p>
 */
class WebDavHttpFirewallTest {

    private static final String[] WEBDAV_METHODS = {
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS",
            "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK"
    };

    private final SecurityConfig config = new SecurityConfig();
    private final HttpFirewall firewall = config.httpFirewall();

    @Test
    void webDavMethodsAreAcceptedByFirewall() {
        for (String method : WEBDAV_METHODS) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, "/dav/");
            assertNotNull(assertDoesNotThrow(() -> firewall.getFirewalledRequest(request)),
                    method + " 应被防火墙放行");
        }
    }

    @Test
    void nonWebDavMethodsRemainRejected() {
        for (String method : new String[]{"TRACE", "FOOBAR", "CONNECT", "SEARCH"}) {
            assertThrows(RequestRejectedException.class,
                    () -> firewall.getFirewalledRequest(new MockHttpServletRequest(method, "/dav/")),
                    method + " 不应被放行");
        }
    }

    @Test
    void firewallStillRejectsDangerousUri() {
        // 反斜杠 URI 仍然被 StrictHttpFirewall 拒绝（没有整体关掉 firewall）
        assertThrows(RequestRejectedException.class,
                () -> firewall.getFirewalledRequest(new MockHttpServletRequest("GET", "/dav/a\\b")));
    }

    @Test
    void customizerInstallsFirewallOnWebSecurity() throws Exception {
        WebSecurity web = new WebSecurity(identityPostProcessor());
        WebSecurityCustomizer customizer = config.webDavHttpFirewallCustomizer(firewall);

        customizer.customize(web);

        Field field = WebSecurity.class.getDeclaredField("httpFirewall");
        field.setAccessible(true);
        assertSame(firewall, field.get(web));
    }

    /**
     * 更强的一条：真正 build 出 {@link org.springframework.security.web.FilterChainProxy}
     * （也就是 FilterChainProxy.class 的防火墙字段），证明生产请求会走我们配置的放行集。
     */
    @Test
    void builtFilterChainProxyUsesConfiguredFirewall() throws Exception {
        WebSecurity web = new WebSecurity(identityPostProcessor());
        web.addSecurityFilterChainBuilder(new SecurityBuilder<SecurityFilterChain>() {
            @Override
            public SecurityFilterChain build() {
                return new DefaultSecurityFilterChain(request -> true);
            }
        });
        config.webDavHttpFirewallCustomizer(firewall).customize(web);

        Filter filter = web.build();
        assertNotNull(filter);
        Field field = FilterChainProxy.class.getDeclaredField("firewall");
        field.setAccessible(true);
        assertSame(firewall, field.get(filter));
    }

    private static ObjectPostProcessor<Object> identityPostProcessor() {
        return new ObjectPostProcessor<Object>() {
            @Override
            public <O> O postProcess(O object) {
                return object;
            }
        };
    }
}
