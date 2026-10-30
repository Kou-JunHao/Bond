package com.bond.common.email;

import com.bond.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SMTP 发信网关（task-63）：465 隐式 TLS / 587 STARTTLS 属性、发件人缺失、连接失败封装、
 * 错误摘要脱敏。
 */
class SmtpEmailGatewayTest {

    private final SmtpEmailGateway gateway = new SmtpEmailGateway();

    private static EmailSettings settings(String fromAddress, String username, String password,
                                          String host, int port, boolean starttls, boolean ssl, int timeout) {
        return new EmailSettings("smtp", fromAddress, "Bond",
                new EmailSettings.Smtp(host, port, username, password, true, starttls, ssl, timeout),
                "", "https://api.resend.com", "");
    }

    @Test
    @DisplayName("465 隐式 TLS：ssl.enable=true、仅信任目标主机、超时按秒换算")
    void implicitTlsProperties() {
        EmailSettings s = settings("verify@example.com", "verify@example.com", "p", "smtp.example.com", 465, false, true, 5);
        Properties props = SmtpEmailGateway.mailProperties(s.smtp());

        assertEquals("true", props.getProperty("mail.smtp.ssl.enable"));
        assertEquals("false", props.getProperty("mail.smtp.starttls.enable"));
        assertEquals("smtp.example.com", props.getProperty("mail.smtp.ssl.trust"));
        assertEquals("5000", props.getProperty("mail.smtp.connectiontimeout"));
        assertEquals("true", props.getProperty("mail.smtp.auth"));
    }

    @Test
    @DisplayName("587 STARTTLS：starttls.enable=true、不设 ssl.trust")
    void starttlsProperties() {
        EmailSettings s = settings("verify@example.com", "verify@example.com", "p", "smtp.example.com", 587, true, false, 3);
        Properties props = SmtpEmailGateway.mailProperties(s.smtp());

        assertEquals("true", props.getProperty("mail.smtp.starttls.enable"));
        assertEquals("false", props.getProperty("mail.smtp.ssl.enable"));
        assertNull(props.getProperty("mail.smtp.ssl.trust"));
        assertEquals("3000", props.getProperty("mail.smtp.timeout"));
    }

    @Test
    @DisplayName("无发件地址（from 与 smtp.username 都空）→ EmailSendException，消息可读")
    void missingFromAddressThrows() {
        EmailSettings s = settings("", "", "", "smtp.example.com", 465, false, true, 5);

        EmailSendException ex = assertThrows(EmailSendException.class,
                () -> gateway.send(s, "to@example.com",
                        new EmailTemplateRenderer.RenderedEmail("subject", null, "text")));
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("发件地址"));
    }

    @Test
    @DisplayName("连接失败统一包成 EmailSendException，不泄漏底层账号")
    void connectionFailureWrapped() {
        EmailSettings s = settings("verify@example.com", "verify@example.com", "topsecret",
                "127.0.0.1", 1, false, false, 1);

        EmailSendException ex = assertThrows(EmailSendException.class,
                () -> gateway.send(s, "to@example.com",
                        new EmailTemplateRenderer.RenderedEmail("subject", null, "text")));
        assertNotNull(ex.getMessage());
        assertFalse(ex.getMessage().contains("topsecret"));
    }

    @Test
    @DisplayName("错误摘要脱敏：口令与 key=value 形态的密钥都被替换，超长截断")
    void sanitizeRedactsSecrets() {
        EmailSettings s = settings("verify@example.com", "verify@example.com", "supersecret", "h", 465, false, true, 5);
        String raw = "550 5.1.1 auth failed: password=supersecret token=abcdef012345 " + "x".repeat(400);

        String sanitized = SmtpEmailGateway.sanitize(s, new RuntimeException(raw));

        assertFalse(sanitized.contains("supersecret"));
        assertFalse(sanitized.contains("abcdef012345"));
        assertTrue(sanitized.contains("<redacted>"));
        assertTrue(sanitized.length() <= 320);
    }

    @Test
    @DisplayName("空正文（text/html 都空）→ BusinessException 400，不尝试连接（task-69）")
    void emptyBodyRejectedBeforeConnecting() {
        EmailSettings s = settings("verify@example.com", "verify@example.com", "p",
                "127.0.0.1", 1, false, false, 1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> gateway.send(s, "to@example.com",
                        new EmailTemplateRenderer.RenderedEmail("subject", "  ", "   ")));

        assertEquals(400, ex.getCode());
        assertEquals("邮件正文不能为空", ex.getMessage());
    }

    @Test
    @DisplayName("仅 HTML：multipart 中出现 text/html，且没有空的 text/plain 部分（task-69）")
    void htmlOnlySendsHtmlPart() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            EmailSettings s = new EmailSettings("smtp", "verify@example.com", "Bond",
                    new EmailSettings.Smtp("127.0.0.1", server.port(), "", "", false, false, false, 5),
                    "", "https://api.resend.com", "");

            gateway.send(s, "to@example.com",
                    new EmailTemplateRenderer.RenderedEmail("Bond subject", "<b>你好</b>", null));

            assertTrue(server.awaitData(), "假 SMTP 应收到 DATA 内容");
            String payload = server.payload().toLowerCase(Locale.ROOT);
            assertTrue(payload.contains("text/html"), "应包含 HTML 部分: " + payload);
            assertFalse(payload.contains("text/plain"), "纯 HTML 时不应出现空的 text/plain 部分");
        }
    }

    @Test
    @DisplayName("text+html 同时存在：multipart/alternative 两个 part 都在（task-69）")
    void textAndHtmlBothParts() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            EmailSettings s = new EmailSettings("smtp", "verify@example.com", "Bond",
                    new EmailSettings.Smtp("127.0.0.1", server.port(), "", "", false, false, false, 5),
                    "", "https://api.resend.com", "");

            gateway.send(s, "to@example.com",
                    new EmailTemplateRenderer.RenderedEmail("Bond 自检", "<b>你好</b>", "纯文本正文"));

            assertTrue(server.awaitData(), "假 SMTP 应收到 DATA 内容");
            String payload = server.payload().toLowerCase(Locale.ROOT);
            assertTrue(payload.contains("multipart/alternative"), "应为 multipart/alternative: " + payload);
            assertTrue(payload.contains("text/plain"), "应包含 text/plain part");
            assertTrue(payload.contains("text/html"), "应包含 text/html part");
            // 中文主题会被 JavaMail 做 MIME 编码（=?UTF-8?B?...?= 或 =?UTF-8?Q?...?=），断言编码头存在
            assertTrue(payload.contains("subject: =?utf-8?"), "主题应以 MIME 编码形式存在: " + payload);
        }
    }

    @Test
    @DisplayName("SMTP 成功路径：内嵌假 SMTP 服务器收到 DATA 正文（不依赖外网）")
    void sendsThroughFakeSmtpServer() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            EmailSettings s = new EmailSettings("smtp", "verify@example.com", "Bond",
                    new EmailSettings.Smtp("127.0.0.1", server.port(), "", "", false, false, false, 5),
                    "", "https://api.resend.com", "");

            gateway.send(s, "to@example.com",
                    new EmailTemplateRenderer.RenderedEmail("Bond subject", null, "verification code 123456"));

            assertTrue(server.awaitData(), "假 SMTP 应收到 DATA 内容");
            assertTrue(server.payload().toLowerCase(Locale.ROOT).contains("subject:"),
                    "DATA 应包含邮件头（subject）");
        }
    }

    @Test
    @DisplayName("SMTP 下游拒绝路径：MAIL FROM 收到 550 → EmailSendException，摘要不含口令")
    void authenticationFailureWrapped() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer(true)) {
            EmailSettings s = new EmailSettings("smtp", "verify@example.com", "Bond",
                    new EmailSettings.Smtp("127.0.0.1", server.port(), "user@example.com", "supersecret",
                            true, false, false, 5),
                    "", "https://api.resend.com", "");

            EmailSendException ex = assertThrows(EmailSendException.class,
                    () -> gateway.send(s, "to@example.com",
                            new EmailTemplateRenderer.RenderedEmail("s", null, "t")));
            assertNotNull(ex.getMessage());
            assertTrue(ex.getMessage().contains("550"), "应带下游真实错误摘要");
            assertFalse(ex.getMessage().contains("supersecret"));
        }
    }

    /** 最小假 SMTP 服务器：只说 EHLO 250（不声明 AUTH），其余命令 250，DATA 收全文。 */
    private static final class FakeSmtpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final Thread thread;
        private final StringBuilder data = new StringBuilder();
        private final boolean denyMailFrom;
        private volatile boolean dataReceived;

        FakeSmtpServer() throws IOException {
            this(false);
        }

        FakeSmtpServer(boolean denyMailFrom) throws IOException {
            this.denyMailFrom = denyMailFrom;
            this.serverSocket = new ServerSocket(0);
            this.thread = new Thread(this::serve, "fake-smtp-" + serverSocket.getLocalPort());
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        boolean awaitData() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5_000;
            while (!dataReceived && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            return dataReceived;
        }

        String payload() {
            return data.toString();
        }

        private void serve() {
            try (Socket socket = serverSocket.accept();
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                 Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII)) {
                reply(out, "220 fake smtp ready");
                String line;
                while ((line = in.readLine()) != null) {
                    String upper = line.toUpperCase(Locale.ROOT);
                    if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                        reply(out, "250 fake");
                    } else if (upper.startsWith("MAIL FROM")) {
                        reply(out, denyMailFrom ? "550 5.7.1 sender rejected" : "250 OK");
                    } else if (upper.startsWith("RCPT TO")) {
                        reply(out, "250 OK");
                    } else if (upper.startsWith("DATA")) {
                        reply(out, "354 end with <CRLF>.<CRLF>");
                        while ((line = in.readLine()) != null && !".".equals(line.trim())) {
                            data.append(line).append('\n');
                        }
                        dataReceived = true;
                        reply(out, "250 queued");
                    } else if (upper.startsWith("QUIT")) {
                        reply(out, "221 bye");
                        return;
                    } else if (upper.startsWith("RSET") || upper.startsWith("NOOP")) {
                        reply(out, "250 OK");
                    } else {
                        reply(out, "250 OK");
                    }
                }
            } catch (IOException ignored) {
                // 连接被客户端提前关闭时忽略（测试只关心服务端是否收到 DATA）
            }
        }

        private static void reply(Writer out, String line) throws IOException {
            out.write(line + "\r\n");
            out.flush();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            thread.interrupt();
        }
    }
}
