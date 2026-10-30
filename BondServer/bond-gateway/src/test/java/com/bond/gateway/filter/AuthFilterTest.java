package com.bond.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bond.common.security.GatewaySignature;
import com.bond.common.utils.JwtUtils;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关 401 形状与鉴权行为（task-126）：统一信封、可区分 message、WWW-Authenticate，
 * 合法请求注入 HMAC 内部头，白名单/DAV/OPTIONS 放行。
 */
class AuthFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private final AuthFilter filter = new AuthFilter(SECRET);

    /** 捕获放行后的 exchange（chain 被调用即表示通过鉴权）。 */
    private static final class CapturingChain implements GatewayFilterChain {
        final AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            passed.set(exchange);
            return Mono.empty();
        }
    }

    private static CapturingChain run(MockServerHttpRequest request) {
        return run(MockServerWebExchange.from(request));
    }

    private static CapturingChain run(MockServerWebExchange exchange) {
        CapturingChain chain = new CapturingChain();
        new AuthFilter(SECRET).filter(exchange, chain).block();
        return chain;
    }

    private static String body(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block();
    }

    @Test
    @DisplayName("缺少 Authorization → 401 信封 + message + WWW-Authenticate")
    void missingTokenRejectedWithEnvelope() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/files").build());

        CapturingChain chain = run(exchange);

        assertNull(chain.passed.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals("Bearer realm=\"Bond\"",
                exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        String json = body(exchange);
        assertTrue(json.contains("\"code\":401"), json);
        assertTrue(json.contains("缺少访问令牌"), json);
        assertTrue(json.contains("\"data\":null"), json);
    }

    @Test
    void nonBearerSchemeRejectedWithDistinctMessage() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/files")
                        .header(HttpHeaders.AUTHORIZATION, "Basic abc")
                        .build());

        run(exchange);

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertTrue(body(exchange).contains("凭证格式错误（应为 Bearer <token>）"), body(exchange));
    }

    @Test
    void expiredTokenGivesExpiredMessageAndInvalidTokenChallenge() {
        String expired = JwtUtils.generateToken(7L, "tester", -1000L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/files")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                        .build());

        run(exchange);

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertTrue(body(exchange).contains("登录已过期，请重新登录"), body(exchange));
        String challenge = exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
        assertNotNull(challenge);
        assertTrue(challenge.contains("invalid_token"), challenge);
    }

    @Test
    void tamperedTokenGivesInvalidCredentialMessage() {
        String token = JwtUtils.generateAccessToken(7L, "tester");
        String tampered = token.substring(0, token.length() - 2) + "xy";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/files")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered)
                        .build());

        run(exchange);

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertTrue(body(exchange).contains("登录凭证无效"), body(exchange));
    }

    @Test
    void validTokenPassesAndInjectsSignedHeaders() {
        String token = JwtUtils.generateAccessToken(7L, "tester");
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/files")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(GatewaySignature.HEADER_USER_ID, "999")
                .header(GatewaySignature.HEADER_SIGNATURE, "forged")
                .build();

        CapturingChain chain = run(request);

        assertNotNull(chain.passed.get());
        HttpHeaders headers = chain.passed.get().getRequest().getHeaders();
        assertEquals("7", headers.getFirst(GatewaySignature.HEADER_USER_ID));
        String timestamp = headers.getFirst(GatewaySignature.HEADER_TIMESTAMP);
        String nonce = headers.getFirst(GatewaySignature.HEADER_NONCE);
        String signature = headers.getFirst(GatewaySignature.HEADER_SIGNATURE);
        assertNotNull(timestamp);
        assertNotNull(nonce);
        assertNotNull(signature);
        assertTrue(GatewaySignature.verify(SECRET, "7", timestamp, nonce, signature));
    }

    @Test
    void publicEndpointAndDavAndOptionsBypassJwtButStillSanitizeHeaders() {
        CapturingChain publicChain = run(MockServerHttpRequest.get("/api/auth/captcha")
                .header(GatewaySignature.HEADER_USER_ID, "999")
                .build());
        assertNotNull(publicChain.passed.get());
        assertNull(publicChain.passed.get().getRequest().getHeaders()
                .getFirst(GatewaySignature.HEADER_USER_ID));

        CapturingChain davChain = run(MockServerHttpRequest
                .method(HttpMethod.valueOf("PROPFIND"), URI.create("/dav/a.txt")).build());
        assertNotNull(davChain.passed.get());
        assertNull(davChain.passed.get().getRequest().getHeaders()
                .getFirst(GatewaySignature.HEADER_USER_ID));

        CapturingChain optionsChain = run(MockServerHttpRequest
                .method(HttpMethod.OPTIONS, URI.create("/api/files")).build());
        assertNotNull(optionsChain.passed.get());
    }
}
