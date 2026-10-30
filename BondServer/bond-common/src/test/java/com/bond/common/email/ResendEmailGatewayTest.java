package com.bond.common.email;

import com.bond.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResendEmailGateway} 单测（task-58）：用本地 JDK HttpServer 模拟 Resend，
 * 覆盖 2xx 成功、非 2xx→503、超时→503、未配置 key→503、禁用时不发请求。
 *
 * <p>另一条安全线：异常 message 固定为对外文案，不包含邮件正文/验证码/API key。</p>
 */
class ResendEmailGatewayTest {

    private static final String FROM = "Bond <verify@example.com>";
    private static final String TO = "bond-probe@example.com";
    private static final String SUBJECT = "Bond 注册验证码";
    private static final String TEXT = "你的 Bond 注册验证码是：123456\n\n有效期 10 分钟。";

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> bodyText = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile long responseDelayMillis = 0;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            requestCount.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            bodyText.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (responseDelayMillis > 0) {
                try {
                    Thread.sleep(responseDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] resp = "{\"id\":\"mock\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ResendEmailGateway gateway(boolean enabled, String apiKey, long timeoutSeconds) {
        return new ResendEmailGateway(enabled, FROM, apiKey, baseUrl(), timeoutSeconds);
    }

    @Test
    @DisplayName("空正文（text/html 都空）→ 400，且不发起网络请求（task-69）")
    void blankBodyRejectedWithoutRequest() {
        ResendEmailGateway gw = gateway(true, "re_test_key", 3);

        BusinessException nullBody = assertThrows(BusinessException.class, () -> gw.send(TO, SUBJECT, null, null));
        assertEquals(400, nullBody.getCode());
        assertEquals("邮件正文不能为空", nullBody.getMessage());
        assertThrows(BusinessException.class, () -> gw.send(TO, SUBJECT, "   "));

        assertEquals(0, requestCount.get(), "空正文不应打网络");
    }

    @Test
    @DisplayName("HTML 正文：请求体同时带 html 与 text 字段（task-69）")
    void sendsHtmlInPayload() throws Exception {
        responseStatus = 201;
        gateway(true, "re_test_key", 3).send(TO, SUBJECT, "<b>hi</b>", "hi");

        assertEquals(1, requestCount.get());
        JsonNode json = new ObjectMapper().readTree(bodyText.get());
        assertEquals("<b>hi</b>", json.path("html").asText());
        assertEquals("hi", json.path("text").asText());
    }

    @Test
    @DisplayName("2xx：发送正确请求（Bearer key / from / to / subject / text）")
    void sendsExpectedPayloadOnSuccess() throws Exception {
        responseStatus = 201;
        gateway(true, "re_test_key", 3).send(TO, SUBJECT, TEXT);

        assertEquals(1, requestCount.get());
        assertEquals("Bearer re_test_key", authorization.get());

        JsonNode json = new ObjectMapper().readTree(bodyText.get());
        assertEquals(FROM, json.path("from").asText());
        assertEquals(TO, json.path("to").get(0).asText());
        assertEquals(SUBJECT, json.path("subject").asText());
        assertEquals(TEXT, json.path("text").asText());
    }

    @Test
    @DisplayName("非 2xx（422）：抛 503，且 message 不泄漏正文/码")
    void non2xxThrows503() {
        responseStatus = 422;
        ResendEmailGateway gw = gateway(true, "re_test_key", 3);

        BusinessException ex = assertThrows(BusinessException.class, () -> gw.send(TO, SUBJECT, TEXT));
        assertEquals(503, ex.getCode());
        assertTrue(!ex.getMessage().contains("123456"), "异常 message 不得包含验证码");
        assertTrue(!ex.getMessage().contains("re_test_key"), "异常 message 不得包含 API key");
        assertEquals(1, requestCount.get());
    }

    @Test
    @DisplayName("超时：抛 503（不重试、不阻塞过久）")
    void timeoutThrows503() {
        responseDelayMillis = 1500;
        ResendEmailGateway gw = gateway(true, "re_test_key", 1);

        BusinessException ex = assertThrows(BusinessException.class, () -> gw.send(TO, SUBJECT, TEXT));
        assertEquals(503, ex.getCode());
    }

    @Test
    @DisplayName("未配置 api-key：直接 503，且不发起网络请求")
    void missingApiKeyThrows503WithoutRequest() {
        ResendEmailGateway gw = gateway(true, "  ", 3);

        BusinessException ex = assertThrows(BusinessException.class, () -> gw.send(TO, SUBJECT, TEXT));
        assertEquals(503, ex.getCode());
        assertEquals(0, requestCount.get(), "缺少 key 时不应打网络");
    }

    @Test
    @DisplayName("enabled=false：跳过发送、不发请求")
    void disabledSkipsRequest() {
        ResendEmailGateway gw = gateway(false, "re_test_key", 3);

        gw.send(TO, SUBJECT, TEXT);

        assertEquals(0, requestCount.get(), "禁用时不应打网络");
    }
}

