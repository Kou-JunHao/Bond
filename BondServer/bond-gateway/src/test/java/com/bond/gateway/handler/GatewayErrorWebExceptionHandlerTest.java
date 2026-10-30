package com.bond.gateway.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * task-46 回归测试：把"下游不可用/超时"统一映射为 503 + 中文 JSON 信封，
 * 且不破坏框架自身的 404/405 语义、不泄漏堆栈。
 */
class GatewayErrorWebExceptionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GatewayErrorWebExceptionHandler handler = new GatewayErrorWebExceptionHandler(objectMapper);

    @Test
    void connectExceptionMapsTo503Envelope() throws Exception {
        MockServerWebExchange exchange = exchange("/api/files", null);

        handler.handle(exchange, new RuntimeException("reactor", new ConnectException("Connection refused: bond-transfer/172.18.0.2:8082")))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        JsonNode body = body(exchange);
        assertThat(body.get("code").asInt()).isEqualTo(503);
        assertThat(body.get("message").asText()).isEqualTo("服务暂时不可用，请稍后重试");
    }

    @Test
    void nettyStyleConnectTimeoutOnCauseChainMapsTo503() {
        // netty 的 ConnectTimeoutException 是 java.net.ConnectException 的子类语义，
        // 这里用"类名包含 ConnectTimeoutException"的包装链路覆盖
        MockServerWebExchange exchange = exchange("/api/files", null);

        handler.handle(exchange, new IllegalStateException("wrapped", new FakeConnectTimeoutException())).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void readTimeoutAndJdkTimeoutMapTo503() {
        MockServerWebExchange readTimeout = exchange("/api/files", null);
        handler.handle(readTimeout, new FakeReadTimeoutException()).block();
        assertThat(readTimeout.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        MockServerWebExchange jdkTimeout = exchange("/api/files", null);
        handler.handle(jdkTimeout, new TimeoutException("timeout")).block();
        assertThat(jdkTimeout.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        MockServerWebExchange socketTimeout = exchange("/api/files", null);
        handler.handle(socketTimeout, new SocketTimeoutException("read timed out")).block();
        assertThat(socketTimeout.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void noRouteToHostMapsTo503() {
        // 生产实测：容器停止（非重启窗口）时 netty 抛 AnnotatedNoRouteToHostException
        MockServerWebExchange exchange = exchange("/api/files", null);

        handler.handle(exchange, new RuntimeException("reactor",
                new NoRouteToHostException("No route to host: bond-transfer/172.18.0.2:8082"))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void unknownHostAndSocketExceptionMapTo503() {
        MockServerWebExchange unknownHost = exchange("/api/files", null);
        handler.handle(unknownHost, new RuntimeException("resolve",
                new UnknownHostException("bond-transfer.lan"))).block();
        assertThat(unknownHost.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        MockServerWebExchange socket = exchange("/api/files", null);
        handler.handle(socket, new SocketException("Connection reset")).block();
        assertThat(socket.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void prematureCloseMapsTo503EvenDeepInCauseChain() {
        // reactor-netty 的 PrematureCloseException 在类名匹配层命中（不受 4 层 IO 兜底限制）
        Throwable deep = new FakePrematureCloseException();
        for (int i = 0; i < 5; i++) {
            deep = new RuntimeException("wrap-" + i, deep);
        }
        MockServerWebExchange exchange = exchange("/api/files", null);

        handler.handle(exchange, deep).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void ioFailureBeyondFourLevelsStays500() {
        Throwable deep = new IOException("io");
        for (int i = 0; i < 5; i++) {
            deep = new RuntimeException("wrap-" + i, deep);
        }
        MockServerWebExchange exchange = exchange("/api/files", null);

        handler.handle(exchange, deep).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void business4xxWithIoCauseIsStillPreserved() throws Exception {
        // 业务语义优先：4xx 不因为 cause 里带连接异常就被吞成 503
        MockServerWebExchange exchange = exchange("/api/not-exists", null);

        handler.handle(exchange, new ResponseStatusException(HttpStatus.NOT_FOUND, "no route",
                new ConnectException("refused"))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = body(exchange);
        assertThat(body.get("code").asInt()).isEqualTo(404);
    }

    @Test
    void framework404IsPreservedNotTurnedInto500() throws Exception {
        MockServerWebExchange exchange = exchange("/api/not-exists", null);

        handler.handle(exchange, new ResponseStatusException(HttpStatus.NOT_FOUND)).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = body(exchange);
        assertThat(body.get("code").asInt()).isEqualTo(404);
        assertThat(body.get("message").asText()).isEqualTo("请求无效");
    }

    @Test
    void unexpectedExceptionMapsTo500WithEnvelopeAndNoStackTrace() throws Exception {
        MockServerWebExchange exchange = exchange("/api/files", null);

        handler.handle(exchange, new IllegalStateException("boom secret internal detail")).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        JsonNode body = body(exchange);
        assertThat(body.get("code").asInt()).isEqualTo(500);
        assertThat(body.get("message").asText()).isEqualTo("服务器内部错误");
        assertThat(body.toString()).doesNotContain("secret internal detail");
    }

    @Test
    void requestIdIsEchoedWhenPresent() {
        MockServerWebExchange exchange = exchange("/api/files", "trace-abc-123");

        handler.handle(exchange, new ConnectException("refused")).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id")).isEqualTo("trace-abc-123");
    }

    @Test
    void requestIdIsGeneratedWhenAbsent() {
        MockServerWebExchange exchange = exchange("/api/files", null);

        handler.handle(exchange, new ConnectException("refused")).block();

        String requestId = exchange.getResponse().getHeaders().getFirst("X-Request-Id");
        assertThat(requestId).isNotNull().hasSize(8);
    }

    private MockServerWebExchange exchange(String path, String requestId) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
        if (requestId != null) {
            builder.header("X-Request-Id", requestId);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private JsonNode body(MockServerWebExchange exchange) throws Exception {
        DataBuffer buffer = DataBufferUtils.join(exchange.getResponse().getBody()).block();
        assertThat(buffer).isNotNull();
        String json = buffer.toString(StandardCharsets.UTF_8);
        DataBufferUtils.release(buffer);
        return objectMapper.readTree(json);
    }

    /** 类名含 ConnectTimeoutException，模拟 netty ConnectTimeoutException。 */
    private static final class FakeConnectTimeoutException extends RuntimeException {
    }

    /** 类名含 ReadTimeoutException，模拟 netty ReadTimeoutException。 */
    private static final class FakeReadTimeoutException extends RuntimeException {
    }

    /** 类名含 PrematureCloseException，模拟 reactor-netty PrematureCloseException（IOException 子类）。 */
    private static final class FakePrematureCloseException extends IOException {
    }
}
