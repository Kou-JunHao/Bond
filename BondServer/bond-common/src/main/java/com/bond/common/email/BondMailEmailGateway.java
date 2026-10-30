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
 * BondMail 发信实现（兼容保留，task-63）：{@code POST {endpoint}/v1/mail/send}。
 *
 * <p>注意：该接口在生产实测要求用户态 Basic 凭据，系统通知通常不可用；仅 provider=bondmail
 * 且后台显式配置了可用 endpoint 时才应选择。由 {@link ConfigurableEmailGateway} 运行时构造。</p>
 */
@Slf4j
public class BondMailEmailGateway {

    private static final String SEND_PATH = "/v1/mail/send";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String endpoint;
    private final boolean enabled;
    private final Duration timeout;

    public BondMailEmailGateway(EmailSettings settings) {
        this(settings.enabled(), settings.bondmailEndpoint(), settings.smtp().timeoutSeconds());
    }

    public BondMailEmailGateway(boolean enabled, String endpoint, long timeoutSeconds) {
        this.enabled = enabled;
        this.endpoint = stripTrailingSlash(endpoint);
        this.timeout = Duration.ofSeconds(Math.max(1L, timeoutSeconds));
    }

    public void send(String to, String subject, String text) {
        if (!enabled) {
            log.info("email gateway disabled, skip sending to {}", mask(to));
            return;
        }
        if (endpoint.isEmpty()) {
            throw new BusinessException(503, "邮件服务未配置");
        }
        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "to", List.of(to),
                    "subject", subject == null ? "" : subject,
                    "text", text == null ? "" : text));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + SEND_PATH))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
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
                log.warn("email send failed: status={} to={}", status, mask(to));
                throw new BusinessException(503, "邮件服务暂时不可用，请稍后再试");
            }
            log.info("email sent via bondmail: to={}", mask(to));
        } catch (BusinessException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            log.warn("email send timeout: to={}", mask(to));
            throw new BusinessException(503, "邮件服务响应超时，请稍后再试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("email send interrupted: to={}", mask(to));
            throw new BusinessException(503, "邮件服务暂时不可用，请稍后再试");
        } catch (Exception e) {
            log.warn("email send error: to={} err={}", mask(to), e.getClass().getSimpleName());
            throw new BusinessException(503, "邮件服务暂时不可用，请稍后再试");
        }
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
