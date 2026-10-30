package com.bond.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

/**
 * CORS 白名单（task-126）：默认白名单 + 显式 {@code *} 时强制关闭 credentials。
 */
class CorsConfigTest {

    private static final String TOKEN = "A".repeat(43);

    private static CorsWebFilter filterFor(String origins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", CorsConfig.buildConfiguration(origins));
        return new CorsWebFilter(source);
    }

    private static MockServerWebExchange preflight(String origin) {
        MockServerHttpRequest request = MockServerHttpRequest
                .options("http://localhost:8080/api/files")
                .header(HttpHeaders.HOST, "localhost:8080")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type")
                .build();
        return MockServerWebExchange.from(request);
    }

    /**
     * 真实 POST（非预检）：模拟生产形态——nginx TLS 终止后回源是 http，
     * 浏览器却带 https Origin（分享落地页 {@code POST /s/{token}/verify} 就是这样）。
     */
    private static MockServerWebExchange actualPost(String origin) {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("http://bond-api.sg.skkk.uno/s/" + TOKEN + "/verify")
                .header(HttpHeaders.HOST, "bond-api.sg.skkk.uno")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"code\":\"1234\"}");
        return MockServerWebExchange.from(request);
    }

    private static void run(CorsWebFilter filter, MockServerWebExchange exchange) {
        filter.filter(exchange, e -> Mono.empty()).block();
    }

    @Test
    @DisplayName("默认不配置时使用安全白名单，且允许 credentials")
    void blankFallsBackToSafeDefaultOrigins() {
        CorsConfiguration config = CorsConfig.buildConfiguration("");

        assertTrue(config.getAllowCredentials());
        assertEquals("http://localhost:5173",
                config.checkOrigin("http://localhost:5173"));
        assertNull(config.checkOrigin("https://evil.example"));
    }

    @Test
    void whitelistedOriginPreflightAddsCorsHeaders() {
        CorsWebFilter filter = filterFor("http://localhost:5173, https://admin.sg.skkk.uno");
        MockServerWebExchange exchange = preflight("http://localhost:5173");

        run(filter, exchange);

        // CorsWebFilter 对合法 preflight 只写响应头，不主动设置 2xx 状态码
        assertTrue(exchange.getResponse().getStatusCode() == null
                || exchange.getResponse().getStatusCode().is2xxSuccessful(),
                String.valueOf(exchange.getResponse().getStatusCode()));
        assertEquals("http://localhost:5173",
                exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("true",
                exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        String exposed = exchange.getResponse().getHeaders()
                .getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
        assertNotNull(exposed);
        assertTrue(exposed.contains("Content-Disposition"), exposed);
    }

    @Test
    @DisplayName("默认白名单必须含自身站点（分享落地页同源 POST 被误判为跨域时不能被 403）")
    void defaultOriginsContainOwnSites() {
        assertTrue(CorsConfig.DEFAULT_ORIGINS.contains("https://bond-api.sg.skkk.uno"));
        assertTrue(CorsConfig.DEFAULT_ORIGINS.contains("https://api.sg.skkk.uno"));
    }

    @Test
    @DisplayName("Origin=https://bond-api.sg.skkk.uno 的实际 POST：通过校验并回写 ACAO（线上 403 回归用例）")
    void sameSiteActualPostPassesCors() {
        // 生产：nginx TLS 终止 → 回源 http://127.0.0.1:8080，网关看到 scheme=http，
        // 浏览器带 Origin=https://bond-api.sg.skkk.uno → 被 Spring 判定为跨域；白名单必须放行。
        CorsWebFilter filter = filterFor("");
        for (String origin : java.util.List.of("https://bond-api.sg.skkk.uno", "https://api.sg.skkk.uno")) {
            MockServerWebExchange exchange = actualPost(origin);

            run(filter, exchange);

            assertTrue(exchange.getResponse().getStatusCode() == null
                            || exchange.getResponse().getStatusCode().is2xxSuccessful(),
                    origin + " -> " + exchange.getResponse().getStatusCode());
            assertEquals(origin, exchange.getResponse().getHeaders()
                    .getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
            assertEquals("true", exchange.getResponse().getHeaders()
                    .getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }
    }

    @Test
    @DisplayName("非白名单 Origin 的实际 POST：仍 403 且无 CORS 头（补自身站点没有放宽白名单）")
    void unknownOriginActualPostStillRejected() {
        CorsWebFilter filter = filterFor("");
        MockServerWebExchange exchange = actualPost("https://evil.example");

        run(filter, exchange);

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertNull(exchange.getResponse().getHeaders()
                .getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertNull(exchange.getResponse().getHeaders()
                .getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void unknownOriginPreflightRejectedWithoutCorsHeaders() {
        CorsWebFilter filter = filterFor("https://admin.sg.skkk.uno");
        MockServerWebExchange exchange = preflight("https://evil.example");

        run(filter, exchange);

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertNull(exchange.getResponse().getHeaders()
                .getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertNull(exchange.getResponse().getHeaders()
                .getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    @DisplayName("配置为 * 时允许任意来源，但强制关闭 credentials 并打 WARN")
    void wildcardAllowsAllButDisablesCredentials() {
        CorsConfiguration config = CorsConfig.buildConfiguration("*");
        assertFalse(config.getAllowCredentials());
        assertNotNull(config.checkOrigin("https://anything.example"));

        CorsWebFilter filter = filterFor("*");
        MockServerWebExchange exchange = preflight("https://anything.example");

        run(filter, exchange);

        assertTrue(exchange.getResponse().getStatusCode() == null
                || exchange.getResponse().getStatusCode().is2xxSuccessful(),
                String.valueOf(exchange.getResponse().getStatusCode()));
        assertEquals("https://anything.example",
                exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertNull(exchange.getResponse().getHeaders()
                .getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void configurationExposesDownloadAndRangeHeaders() {
        CorsConfiguration config = CorsConfig.buildConfiguration("http://localhost:5173");

        assertTrue(config.getAllowedMethods().contains("PUT"));
        assertTrue(config.getAllowedMethods().contains("DELETE"));
        assertTrue(config.getAllowedHeaders().contains("Authorization"));
        assertTrue(config.getAllowedHeaders().contains("Range"));
        assertTrue(config.getExposedHeaders().contains("Content-Range"));
        assertEquals(3600L, config.getMaxAge());
    }
}
