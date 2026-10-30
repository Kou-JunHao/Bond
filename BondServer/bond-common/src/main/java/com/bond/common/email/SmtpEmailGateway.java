package com.bond.common.email;

import com.bond.common.exception.BusinessException;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * SMTP 发信实现（task-63）：支持 465 隐式 TLS（{@code ssl=true}）与 587 STARTTLS（{@code starttls=true}）。
 *
 * <p>只做发送原语，配置由 {@link ConfigurableEmailGateway} 从 DB 解析后以 {@link EmailSettings} 传入；
 * 因此本类无 Spring 依赖、可单测。超时取 {@code smtp.timeoutSeconds}（默认 5 秒）。</p>
 *
 * <p>安全：日志只记录 host/port/掩码收件人/异常类别；异常 detail 会脱敏（不含口令）。</p>
 */
@Slf4j
public class SmtpEmailGateway {

    private static final Pattern SECRET_LIKE =
            Pattern.compile("(?i)(password|passwd|passphrase|secret|token|api[_-]?key|authorization)\\s*[=:]\\s*\\S+");

    /**
     * 发送 HTML+text 多部分邮件。
     *
     * @throws EmailSendException 连接/认证/发送失败（detail 为脱敏后的下游摘要，供后台自检展示）
     */
    public void send(EmailSettings settings, String to, EmailTemplateRenderer.RenderedEmail email) {
        boolean hasText = email.text() != null && !email.text().isBlank();
        boolean hasHtml = email.html() != null && !email.html().isBlank();
        if (!hasText && !hasHtml) {
            // task-69：空正文直接 400，不要发到下游再吃 SMTP 550「Missing html or text field」
            throw new BusinessException(400, "邮件正文不能为空");
        }
        EmailSettings.Smtp smtp = settings.smtp();
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        if (!isBlank(smtp.username())) {
            sender.setUsername(smtp.username());
            sender.setPassword(smtp.password());
        }
        sender.setDefaultEncoding("UTF-8");
        sender.setJavaMailProperties(mailProperties(smtp));
        try {
            MimeMessage message = buildMessage(sender, settings, to, email);
            sender.send(message);
        } catch (Exception e) {
            String detail = sanitize(settings, e);
            log.warn("smtp send failed: host={} port={} ssl={} starttls={} to={} err={}",
                    smtp.host(), smtp.port(), smtp.ssl(), smtp.starttls(), mask(to),
                    rootClassName(e));
            throw new EmailSendException(detail, null, isRetryable(e), e);
        }
    }

    /**
     * 组装 MIME 消息（包级可见，便于单测断言正文结构而不真的发信）。
     *
     * <p>task-69 修复：{@link MimeMessageHelper#setText(String, boolean)} 被连续调用两次会**互相覆盖**，
     * 导致最终只剩一个 part；Resend 的 SMTP 网关因此报 {@code 550 Missing `html` or `text` field}。
     * 这里在 text 与 html 同时存在时显式构造 {@code multipart/alternative}，保证两个 part 都在。</p>
     */
    MimeMessage buildMessage(JavaMailSenderImpl sender, EmailSettings settings, String to,
                             EmailTemplateRenderer.RenderedEmail email) throws MessagingException, java.io.UnsupportedEncodingException {
        boolean hasText = email.text() != null && !email.text().isBlank();
        boolean hasHtml = email.html() != null && !email.html().isBlank();
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromAddress(settings));
        helper.setTo(to);
        helper.setSubject(email.subject() == null ? "" : email.subject());
        if (hasText && hasHtml) {
            MimeMultipart alternative = new MimeMultipart("alternative");
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(email.text(), "UTF-8");
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(email.html(), "text/html; charset=UTF-8");
            alternative.addBodyPart(textPart);
            alternative.addBodyPart(htmlPart);
            message.setContent(alternative);
        } else if (hasText) {
            helper.setText(email.text(), false);
        } else {
            helper.setText(email.html(), true);
        }
        return message;
    }

    /** JavaMail 属性（包级可见，便于单测断言 465/587 两种模式）。 */
    static Properties mailProperties(EmailSettings.Smtp smtp) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", String.valueOf(smtp.auth()));
        props.put("mail.smtp.starttls.enable", String.valueOf(smtp.starttls()));
        props.put("mail.smtp.ssl.enable", String.valueOf(smtp.ssl()));
        if (smtp.ssl() && !isBlank(smtp.host())) {
            // 证书校验仍由 JVM 默认 TrustManager 完成；只信任目标主机名，不放开全部
            props.put("mail.smtp.ssl.trust", smtp.host());
        }
        int timeoutMillis = Math.max(1, smtp.timeoutSeconds()) * 1000;
        props.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMillis));
        props.put("mail.smtp.timeout", String.valueOf(timeoutMillis));
        props.put("mail.smtp.writetimeout", String.valueOf(timeoutMillis));
        return props;
    }

    private static InternetAddress fromAddress(EmailSettings settings) {
        String address = settings.effectiveFromAddress();
        if (address == null || address.isBlank()) {
            throw new EmailSendException("发件地址未配置（email.from.address / email.smtp.username）", null);
        }
        try {
            return new InternetAddress(address, settings.fromName(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            try {
                return new InternetAddress(address);
            } catch (AddressException ex) {
                throw new EmailSendException("发件地址格式不正确", ex);
            }
        }
    }

    /** 下游错误摘要：类名 + 根因消息，截断并抹掉疑似口令/Key。 */
    static String sanitize(EmailSettings settings, Exception e) {
        String rootMessage = rootMessage(e);
        String plain = (rootMessage == null || rootMessage.isBlank())
                ? rootClassName(e) : rootClassName(e) + ": " + rootMessage;
        plain = SECRET_LIKE.matcher(plain).replaceAll("$1=<redacted>");
        if (!isBlank(settings.smtp().password())) {
            plain = plain.replace(settings.smtp().password(), "<redacted>");
        }
        if (!isBlank(settings.resendApiKey())) {
            plain = plain.replace(settings.resendApiKey(), "<redacted>");
        }
        return plain.length() > 300 ? plain.substring(0, 300) + "..." : plain;
    }

    /**
     * task-129：区分瞬时/永久失败。
     *
     * <p>瞬时（可重试）：SMTP 4xx（421/450/451/452 等，含连接/读取超时、临时限流）。
     * 永久：认证失败、地址/报文格式错误、SMTP 5xx（收件人不存在/被拒、权限不足等）。
     * 未识别的一律按永久处理——宁可少重试，也不要把"发不出去"变成多次打扰下游。</p>
     */
    static boolean isRetryable(Exception e) {
        Throwable cursor = e;
        Integer code = null;
        for (int depth = 0; cursor != null && depth < 8; depth++) {
            if (cursor instanceof MailAuthenticationException
                    || cursor instanceof AuthenticationFailedException
                    || cursor instanceof MailParseException
                    || cursor instanceof AddressException) {
                return false;
            }
            if (cursor instanceof SocketTimeoutException) {
                return true;
            }
            if (code == null) {
                code = smtpReplyCode(cursor.getMessage());
            }
            Throwable next = cursor.getCause();
            if (next == cursor) {
                break;
            }
            cursor = next;
        }
        if (code != null) {
            return code >= 421 && code <= 499;
        }
        // 无明确 SMTP 码时，连接类 IO 异常视为瞬时，其余（含 5xx 文本）按永久
        if (e instanceof java.net.ConnectException
                || e instanceof java.io.IOException && !(e instanceof java.io.CharConversionException)) {
            return true;
        }
        String message = e.getMessage();
        return message != null && (message.contains("timed out") || message.contains("Timeout")
                || message.contains("temporarily"));
    }

    /** 从邮件异常消息里抽取 SMTP 三段码（如 "550 5.1.1 user unknown" → 550）。 */
    static Integer smtpReplyCode(String message) {
        if (message == null) {
            return null;
        }
        java.util.regex.Matcher matcher = SMTP_CODE.matcher(message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static final Pattern SMTP_CODE = Pattern.compile("(?<!\\d)([45]\\d{2})(?!\\d)");

    private static String rootMessage(Throwable e) {
        Throwable cursor = e;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage();
    }

    private static String rootClassName(Throwable e) {
        Throwable cursor = e;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getClass().getSimpleName();
    }

    static String mask(String email) {
        if (isBlank(email)) {
            return "***";
        }
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
