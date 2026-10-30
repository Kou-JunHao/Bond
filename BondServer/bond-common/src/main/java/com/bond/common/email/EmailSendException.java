package com.bond.common.email;

/**
 * 发信失败（task-63 引入，task-129 扩展分类）。
 *
 * <p>{@link #detail()} 是给后台「发送测试」展示的下游错误摘要（已脱敏，不含密码/API Key）。
 * {@link #retryable()} 标记是否为瞬时错误：true 时 {@link ConfigurableEmailGateway}
 * 会按指数退避重试；false 时立即失败，错误原文进 {@code email_delivery_logs}。
 * {@link #httpStatus()} 仅 HTTP 通道（Resend / BondMail）有值，SMTP 通道为 null。</p>
 */
public class EmailSendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String detail;
    private final Integer httpStatus;
    private final boolean retryable;

    /** 兼容构造器：未分类视为永久失败（配置错误等）。 */
    public EmailSendException(String detail, Throwable cause) {
        this(detail, null, false, cause);
    }

    public EmailSendException(String detail, Integer httpStatus, boolean retryable, Throwable cause) {
        super(detail == null ? "邮件发送失败" : detail, cause);
        this.detail = detail == null ? "邮件发送失败" : detail;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    /** 下游错误摘要（已脱敏，供后台展示/日志）。 */
    public String detail() {
        return detail;
    }

    /** HTTP 状态码（仅 HTTP 通道）。 */
    public Integer httpStatus() {
        return httpStatus;
    }

    /** 是否瞬时错误（可重试）。 */
    public boolean retryable() {
        return retryable;
    }
}
