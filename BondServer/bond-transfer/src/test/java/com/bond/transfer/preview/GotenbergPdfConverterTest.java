package com.bond.transfer.preview;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gotenberg 转换客户端（task-59）单测：用 JDK 内置 HttpServer 假扮 Gotenberg，
 * 覆盖 multipart 组装、错误分类（4xx→415 / 5xx、超时、连接失败→503）与文件名清洗。
 */
class GotenbergPdfConverterTest {

    private static final byte[] PDF = "%PDF-1.7 test".getBytes(StandardCharsets.US_ASCII);

    private HttpServer server;
    private GotenbergPdfConverter converter;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private String startServer(int status, byte[] body, long delayMillis,
                               AtomicReference<String> path,
                               AtomicReference<String> contentType,
                               AtomicReference<byte[]> requestBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/forms/libreoffice/convert", exchange -> {
            if (path != null) {
                path.set(exchange.getRequestURI().getPath());
            }
            if (contentType != null) {
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            }
            byte[] req = exchange.getRequestBody().readAllBytes();
            if (requestBody != null) {
                requestBody.set(req);
            }
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.sendResponseHeaders(status, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        converter = new GotenbergPdfConverter();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    @DisplayName("额外表单参数：xlsx 的 singlePageSheets=true 进入 multipart，字段名做注入清洗")
    void sendsExtraFormFields() throws IOException {
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        String endpoint = startServer(200, PDF, 0, null, null, requestBody);

        try (InputStream in = converter.convert(endpoint, Duration.ofSeconds(5), "报表.xlsx",
                new ByteArrayInputStream("xlsx-bytes".getBytes(StandardCharsets.UTF_8)), 10L,
                java.util.Map.of("singlePageSheets", "true", "bad\r\nname", "x\r\ny"))) {
            assertArrayEquals(PDF, in.readAllBytes());
        }

        String body = new String(requestBody.get(), StandardCharsets.UTF_8);
        assertTrue(body.contains("name=\"singlePageSheets\""), "必须带 singlePageSheets 字段: " + body);
        assertTrue(body.contains("\r\n\r\ntrue\r\n"), "参数值 true 应作为字段内容: " + body);
        assertTrue(body.contains("name=\"badname\""), "字段名中的非法字符应被清洗为 badname: " + body);
    }

    @Test
    @DisplayName("成功：POST /forms/libreoffice/convert，multipart 字段 files + filename，返回 PDF 字节")
    void sendsMultipartAndReturnsPdf() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        String endpoint = startServer(200, PDF, 0, path, contentType, requestBody);

        try (InputStream in = converter.convert(endpoint, Duration.ofSeconds(5), "季度报告.docx",
                new ByteArrayInputStream("hello-doc-bytes".getBytes(StandardCharsets.UTF_8)), 15)) {
            assertArrayEquals(PDF, in.readAllBytes());
        }

        assertEquals("/forms/libreoffice/convert", path.get());
        assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
        String body = new String(requestBody.get(), StandardCharsets.UTF_8);
        assertTrue(body.contains("name=\"files\""), "字段名必须是 files: " + body);
        assertTrue(body.contains("filename=\"季度报告.docx\""), "文件名应进入 multipart: " + body);
        assertTrue(body.contains("hello-doc-bytes"), "文件内容应进入 multipart 请求体");
    }

    @Test
    @DisplayName("4xx（坏文档）→ BadDocumentException（对外 415）")
    void fourHundredMapsToBadDocument() throws IOException {
        String endpoint = startServer(400, "bad request".getBytes(StandardCharsets.UTF_8), 0, null, null, null);
        assertThrows(OfficePdfConverter.BadDocumentException.class,
                () -> converter.convert(endpoint, Duration.ofSeconds(5), "broken.docx",
                        new ByteArrayInputStream(new byte[]{1, 2, 3}), 3));
    }

    @Test
    @DisplayName("5xx（转换服务故障）→ ConversionUnavailableException（对外 503）")
    void fiveHundredMapsToUnavailable() throws IOException {
        String endpoint = startServer(500, "boom".getBytes(StandardCharsets.UTF_8), 0, null, null, null);
        assertThrows(OfficePdfConverter.ConversionUnavailableException.class,
                () -> converter.convert(endpoint, Duration.ofSeconds(5), "a.docx",
                        new ByteArrayInputStream(new byte[]{1}), 1));
    }

    @Test
    @DisplayName("5xx + 转换失败语义 → BadDocumentException（对外 415，进负缓存）")
    void fiveHundredWithConversionFailureMapsToBadDocument() throws IOException {
        String body = "LibreOffice failed to convert the document: source file could not be loaded";
        String endpoint = startServer(500, body.getBytes(StandardCharsets.UTF_8), 0, null, null, null);
        OfficePdfConverter.BadDocumentException ex = assertThrows(OfficePdfConverter.BadDocumentException.class,
                () -> converter.convert(endpoint, Duration.ofSeconds(5), "office-test(1).docx",
                        new ByteArrayInputStream(new byte[]{1}), 1));
        assertTrue(ex.getMessage().contains("failed to convert"), "异常信息应保留下游摘要: " + ex.getMessage());
    }

    @Test
    @DisplayName("5xx + uno exception 语义 → BadDocumentException（415）")
    void fiveHundredWithUnoExceptionMapsToBadDocument() throws IOException {
        String endpoint = startServer(503, "{\"message\":\"Uno exception while converting\"}".getBytes(StandardCharsets.UTF_8),
                0, null, null, null);
        assertThrows(OfficePdfConverter.BadDocumentException.class,
                () -> converter.convert(endpoint, Duration.ofSeconds(5), "broken.pptx",
                        new ByteArrayInputStream(new byte[]{1}), 1));
    }

    @Test
    @DisplayName("isConversionFailure：命中关键字 / 不命中其它 5xx 语义")
    void conversionFailureKeywordMatching() {
        assertTrue(GotenbergPdfConverter.isConversionFailure("LibreOffice failed to convert the document"));
        assertTrue(GotenbergPdfConverter.isConversionFailure("UNO exception while converting"));
        assertTrue(GotenbergPdfConverter.isConversionFailure("unsupported document format"));
        assertFalse(GotenbergPdfConverter.isConversionFailure("internal server error"));
        assertFalse(GotenbergPdfConverter.isConversionFailure("service unavailable"));
        assertFalse(GotenbergPdfConverter.isConversionFailure(""));
        assertFalse(GotenbergPdfConverter.isConversionFailure(null));
    }

    @Test
    @DisplayName("超时 → ConversionUnavailableException（不把慢转换当坏文档）")
    void timeoutMapsToUnavailable() throws IOException {
        String endpoint = startServer(200, PDF, 1500, null, null, null);
        long start = System.nanoTime();
        assertThrows(OfficePdfConverter.ConversionUnavailableException.class,
                () -> converter.convert(endpoint, Duration.ofMillis(300), "slow.docx",
                        new ByteArrayInputStream(new byte[]{1}), 1));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < 1400, "应在超时后尽快返回，实际耗时 " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("连接失败（服务不可达）→ ConversionUnavailableException")
    void connectionRefusedMapsToUnavailable() throws IOException {
        String endpoint = startServer(200, PDF, 0, null, null, null);
        server.stop(0);
        server = null;
        assertThrows(OfficePdfConverter.ConversionUnavailableException.class,
                () -> converter.convert(endpoint, Duration.ofSeconds(2), "a.docx",
                        new ByteArrayInputStream(new byte[]{1}), 1));
    }

    @Test
    @DisplayName("文件名清洗：去掉路径分隔符与引号，防 multipart header 注入")
    void sanitizesFileName() throws IOException {
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        String endpoint = startServer(200, PDF, 0, null, null, requestBody);
        try (InputStream ignored = converter.convert(endpoint, Duration.ofSeconds(5),
                "..\\..\\etc/pa\"sswd.docx", new ByteArrayInputStream(new byte[]{9}), 1)) {
            assertFalse(ignored.readAllBytes().length == 0);
        }
        String body = new String(requestBody.get(), StandardCharsets.UTF_8);
        assertFalse(body.contains(".."), "不应保留路径穿越: " + body);
        assertFalse(body.contains("\"sswd"), "不应保留引号注入: " + body);
        assertTrue(body.contains("passwd.docx"), "应保留安全文件名: " + body);
    }

    @Test
    @DisplayName("空文件名回退 document")
    void blankFileNameFallsBack() throws IOException {
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        String endpoint = startServer(200, PDF, 0, null, null, requestBody);
        try (InputStream ignored = converter.convert(endpoint, Duration.ofSeconds(5), "  ",
                new ByteArrayInputStream(new byte[]{1}), 1)) {
            assertTrue(ignored.readAllBytes().length > 0);
        }
        String body = new String(requestBody.get(), StandardCharsets.UTF_8);
        assertTrue(body.contains("filename=\"document\""), "空名应回退 document: " + body);
    }
}
