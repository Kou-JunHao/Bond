package com.bond.common.email;

import com.bond.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Resend 直连发信实现（task-63）：{@code POST {endpoint}/emails}，
 * Header {@code Authorization: Bearer <api-key>}，2xx 视为成功。
 *
 * <p>不再作为 Spring bean 自动装配：由 {@link ConfigurableEmailGateway} 按运行时配置构造，
 * 因此 provider 切换无需重启。配置来自 DB（敏感值解密后）或环境变量。</p>
 *
 * <p>安全：日志只记录状态码与收件人掩码；邮件正文（含验证码/临时密码）绝不落日志。</p>
 */
@Slf4j
public class ResendEmailGateway {

    private static final String SEND_PATH = "/emails";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final String apiKey;
    private final String from;
    private final String endpoint;
    private final Duration timeout;

    /** 测试/兼容构造器。 */
    public ResendEmailGateway(boolean enabled, String from, String apiKey, String endpoint, long timeoutSeconds) {
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.from = from == null ? "" : from.trim();
        this.endpoint = stripTrailingSlash(endpoint);
        this.timeout = Duration.ofSeconds(Math.max(1L, timeoutSeconds));
    }

    /** 运行时配置构造器（task-63）。 */
    public ResendEmailGateway(EmailSettings settings) {
        this(settings.enabled(), fromOf(settings),
                settings.resendApiKey(), settings.resendEndpoint(), settings.smtp().timeoutSeconds());
    }

    public void send(String to, String subject, String text) {
        send(to, subject, null, text);
    }

    /**
     * 发送 HTML + 纯文本邮件（task-69）。
     *
     * <p>html 与 text 都为空视为调用方缺陷：直接 400，不发网络请求，
     * 避免下游 SMTP 网关/Resend 返回「Missing html or text field」这类 550。</p>
     */
    public void send(String to, String subject, String html, String text) {
        String plain = text == null ? "" : text;
        String htmlBody = html == null ? "" : html;
        if (plain.isBlank() && htmlBody.isBlank()) {
            throw new BusinessException(400, "邮件正文不能为空");
        }
        if (!enabled) {
            log.info("email gateway disabled, skip sending to {}", mask(to));
            return;
        }
        if (apiKey.isEmpty()) {
            log.warn("resend api key is not configured, cannot send mail to {}", mask(to));
            throw new BusinessException(503, "邮件服务未配置");
        }
        if (from.isEmpty()) {
            throw new BusinessException(503, "邮件服务未配置");
        }
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("from", from);
            payload.put("to", List.of(to));
            payload.put("subject", subject == null ? "" : subject);
            if (!plain.isBlank()) {
                payload.put("text", plain);
            }
            if (!htmlBody.isBlank()) {
                payload.put("html", htmlBody);
            }
            byte[] body = objectMapper.writeValueAsBytes(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + SEND_PATH))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                log.warn("resend send failed: status={} to={}", status, mask(to));
                throw new BusinessException(503, "邮件服务暂时不可用，请稍后再试");
            }
            log.info("email sent via resend: to={}", mask(to));
        } catch (BusinessException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            log.warn("resend send timeout: to={}", mask(to));
            throw new BusinessException(503, "邮件服务响应超时，请稍后再试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("resend send interrupted: to={}", mask(to));
            throw new BusinessException(503, "邮件服务暂时不可用，请稍后再试");
        } catch (Exception e) {
            log.warn("resend send error: to={} err={}", mask(to), e.getClass().getSimpleName());
            throw new BusinessException(503, "邮件服务暂时不可用，请稍后再试");
        }
    }

    /** 发件人：{@code Name <addr>}，无 from-name 时退化为裸地址。 */
    static String fromOf(EmailSettings settings) {
        String address = settings.effectiveFromAddress();
        if (address == null || address.isBlank()) {
            return "";
        }
        String name = settings.fromName();
        return name == null || name.isBlank() ? address : name + " <" + address + ">";
    }

    private static String stripTrailingSlash(String value) {
        String v = value == null ? "" : value.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    /** 日志脱敏：a***@example.com。 */
    static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
