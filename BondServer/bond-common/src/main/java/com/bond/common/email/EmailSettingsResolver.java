package com.bond.common.email;

import java.util.function.Function;

/**
 * 发信配置解析（task-63 v3）：DB（system_configs）优先 → 环境变量（BOND_EMAIL_*）→ 默认。
 *
 * <p>放在 bond-common，bond-auth / bond-transfer 各自注入一个 {@code Function<String,String>}
 * 读取本服务的 system_configs 缓存即可复用同一套解析逻辑。</p>
 *
 * <p>安全：SMTP 口令 / Resend Key 在 DB 里是 {@code enc:v1:} 密文，这里解密后再放进内存快照；
 * 环境变量形式视为明文（不回写 DB）。</p>
 */
public final class EmailSettingsResolver {

    private static final ConfigCipher CIPHER = ConfigCipher.fromEnvironment();

    private EmailSettingsResolver() {
    }

    public static EmailSettings resolve(Function<String, String> dbConfig) {
        Function<String, String> db = key -> {
            try {
                return dbConfig.apply(key);
            } catch (RuntimeException e) {
                return null;
            }
        };

        String provider = firstNonBlank(
                db.apply("email.provider"),
                env("BOND_EMAIL_PROVIDER"),
                EmailSettings.PROVIDER_SMTP);
        String fromAddress = firstNonBlank(
                db.apply("email.from.address"),
                env("BOND_EMAIL_FROM"),
                "");
        String fromName = firstNonBlank(
                db.apply("email.from.name"),
                env("BOND_EMAIL_FROM_NAME"),
                "Bond");

        String smtpHost = firstNonBlank(db.apply("email.smtp.host"), env("BOND_EMAIL_SMTP_HOST"), "");
        int smtpPort = intOr(firstNonBlank(db.apply("email.smtp.port"),
                env("BOND_EMAIL_SMTP_PORT")), 587);
        String smtpUsername = firstNonBlank(db.apply("email.smtp.username"),
                env("BOND_EMAIL_SMTP_USERNAME"), "");
        String smtpPassword = secret(firstNonBlank(db.apply("email.smtp.password"),
                env("BOND_EMAIL_SMTP_PASSWORD")));
        boolean auth = boolOr(firstNonBlank(db.apply("email.smtp.auth"),
                env("BOND_EMAIL_SMTP_AUTH")), !smtpUsername.isBlank());
        boolean starttls = boolOr(firstNonBlank(db.apply("email.smtp.starttls"),
                env("BOND_EMAIL_SMTP_STARTTLS")), false);
        boolean ssl = boolOr(firstNonBlank(db.apply("email.smtp.ssl"),
                env("BOND_EMAIL_SMTP_SSL")), false);
        int timeoutSeconds = intOr(firstNonBlank(db.apply("email.smtp.timeout_seconds"),
                env("BOND_EMAIL_SMTP_TIMEOUT_SECONDS")), 5);

        String resendKey = secret(firstNonBlank(db.apply("email.resend.api_key"),
                env("BOND_EMAIL_RESEND_KEY")));
        String resendEndpoint = firstNonBlank(db.apply("email.resend.endpoint"),
                env("BOND_EMAIL_RESEND_ENDPOINT"),
                "https://api.resend.com");
        String bondmailEndpoint = firstNonBlank(db.apply("email.bondmail.endpoint"),
                env("BOND_EMAIL_BONDMAIL_ENDPOINT"),
                "");

        // 开箱即发兜底（lead m08626）：默认 provider=smtp，但 SMTP 未配全时若 Resend Key 可用，
        // 实际生效通道回退 resend。注意只在「SMTP 未就绪」时回退，管理员显式选 smtp 且已配好则尊重之。
        boolean smtpReady = !smtpHost.isBlank()
                && (!auth || (!smtpUsername.isBlank() && !smtpPassword.isBlank()));
        if (EmailSettings.PROVIDER_SMTP.equals(provider) && !smtpReady && !resendKey.isBlank()) {
            provider = EmailSettings.PROVIDER_RESEND;
        }

        return new EmailSettings(
                provider, fromAddress, fromName,
                new EmailSettings.Smtp(smtpHost, smtpPort, smtpUsername, smtpPassword,
                        auth, starttls, ssl, Math.max(1, timeoutSeconds)),
                resendKey, resendEndpoint, bondmailEndpoint);
    }

    /** DB 值若是 enc:v1: 密文则解密；解密失败按空处理（等价未配置，避免启动炸）。 */
    private static String secret(String stored) {
        if (stored == null || stored.isBlank()) {
            return "";
        }
        String decrypted = CIPHER.tryDecrypt(stored);
        if (decrypted == null) {
            return "";
        }
        return decrypted.trim();
    }

    private static String env(String name) {
        return System.getenv(name);
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static int intOr(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    private static boolean boolOr(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim());
    }
}
