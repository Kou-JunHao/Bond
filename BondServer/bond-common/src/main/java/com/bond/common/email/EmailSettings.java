package com.bond.common.email;

/**
 * 发信配置快照（task-63 v3）：由 DB（system_configs）→环境变量（BOND_EMAIL_*）→内置默认逐项解析。
 *
 * <p>默认（未配置任何 DB/环境变量时）{@code provider=smtp}，但 SMTP 未配全且 Resend Key 可用时
 * 服务端会自动回退 resend（见 {@link EmailSettingsResolver}）；两者都不可用则发信接口返回
 * 503「发信服务未配置」。本类不内置任何真实服务商/域名/主机默认值（SMTP 参数只能来自后台配置或环境变量）。</p>
 *
 * <p>注意：{@code password} / {@code resendApiKey} 是<b>解密后的明文</b>，只在内存中使用，
 * 绝不序列化返回给前端（GET 统一走掩码）。</p>
 */
public record EmailSettings(
        String provider,
        String fromAddress,
        String fromName,
        Smtp smtp,
        String resendApiKey,
        String resendEndpoint,
        String bondmailEndpoint) {

    public static final String PROVIDER_SMTP = "smtp";
    public static final String PROVIDER_RESEND = "resend";
    public static final String PROVIDER_BONDMAIL = "bondmail";
    public static final String PROVIDER_OFF = "off";

    /** SMTP 连接参数。ssl=隐式 TLS(465)，starttls=587 显式升级；二者互斥。 */
    public record Smtp(String host, int port, String username, String password,
                       boolean auth, boolean starttls, boolean ssl, int timeoutSeconds) {
    }

    public EmailSettings {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase();
        provider = switch (normalizedProvider) {
            case PROVIDER_SMTP, PROVIDER_RESEND, PROVIDER_BONDMAIL -> normalizedProvider;
            default -> PROVIDER_OFF;
        };
        fromAddress = fromAddress == null ? "" : fromAddress.trim();
        fromName = fromName == null || fromName.isBlank() ? "Bond" : fromName.trim();
        if (smtp == null) {
            smtp = new Smtp("", 587, "", "", true, false, false, 5);
        }
        if (resendEndpoint == null || resendEndpoint.isBlank()) {
            resendEndpoint = "https://api.resend.com";
        }
        bondmailEndpoint = bondmailEndpoint == null ? "" : bondmailEndpoint.trim();
    }

    /** 是否启用发信（provider=off 时整体静默）。 */
    public boolean enabled() {
        return !PROVIDER_OFF.equals(provider);
    }

    /** 是否已具备最小可用配置（用于后台展示 configured 与提前报错）。 */
    public boolean configured() {
        if (!enabled() || !hasFromAddress()) {
            return false;
        }
        return switch (provider) {
            case PROVIDER_SMTP -> smtpReady();
            case PROVIDER_RESEND -> !isBlank(resendApiKey);
            case PROVIDER_BONDMAIL -> !isBlank(bondmailEndpoint);
            default -> false;
        };
    }

    /** 发件地址：显式配置优先，其次回退 SMTP 用户名（常见部署即用账号本身发信）。 */
    public String effectiveFromAddress() {
        if (!isBlank(fromAddress)) {
            return fromAddress;
        }
        return isBlank(smtp.username()) ? "" : smtp.username().trim();
    }

    public boolean hasFromAddress() {
        return !isBlank(effectiveFromAddress());
    }

    /** SMTP 必填校验：auth=true 时用户名/口令必填；host/port 必填。 */
    public boolean smtpReady() {
        return !isBlank(smtp.host()) && smtp.port() > 0 && smtp.port() <= 65535
                && (!smtp.auth() || (!isBlank(smtp.username()) && !isBlank(smtp.password())));
    }

    /** 读取配置失败时的安全兜底快照：provider=off，静默不发信，不带任何真实服务商信息。 */
    public static EmailSettings defaults() {
        return new EmailSettings(PROVIDER_OFF, "", "Bond",
                new Smtp("", 587, "", "", true, false, false, 5),
                "", "https://api.resend.com", "");
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
