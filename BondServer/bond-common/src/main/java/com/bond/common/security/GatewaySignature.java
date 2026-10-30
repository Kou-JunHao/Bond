package com.bond.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 网关 ↔ 下游服务之间的 HMAC 签名工具（P0 安全：根除裸 X-User-Id 伪造）。
 *
 * <p>签名载荷：{@code userId:timestampMillis:nonce}，算法 HmacSHA256，hex 小写。
 * 网关在转发前用 {@code INTERNAL_GATEWAY_SECRET} 签名并注入 4 个头；
 * 下游 {@link InternalAuthFilter} 用同一密钥校验并限制 ±{@link #MAX_CLOCK_SKEW_MS} 时间窗。</p>
 *
 * <p>密钥**只从环境变量读取**，缺失或过短启动即失败（fail-fast），不允许回退默认值。</p>
 */
public final class GatewaySignature {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_TIMESTAMP = "X-Gateway-Timestamp";
    public static final String HEADER_NONCE = "X-Gateway-Nonce";
    public static final String HEADER_SIGNATURE = "X-Gateway-Signature";

    /** 下游 request attribute：签名校验通过的 userId（过滤器写入）。 */
    public static final String ATTR_VERIFIED_USER_ID = "bond.gateway.verifiedUserId";

    public static final String ENV_SECRET = "INTERNAL_GATEWAY_SECRET";

    /** 允许的时钟偏差（毫秒）：签名时间戳超出窗口即拒绝。 */
    public static final long MAX_CLOCK_SKEW_MS = 60_000L;

    /** 最小密钥长度（字符）：太短的 HMAC 密钥可被暴力破解。 */
    public static final int MIN_SECRET_LENGTH = 32;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private GatewaySignature() {
    }

    /** 从环境变量读取内部网关密钥；缺失/过短抛 IllegalStateException（启动 fail-fast）。 */
    public static String requireSecretFromEnv() {
        return requireSecret(() -> System.getenv(ENV_SECRET));
    }

    /** 校验并返回密钥（测试可注入读取函数）。 */
    public static String requireSecret(Supplier<String> reader) {
        String secret = reader == null ? null : reader.get();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(ENV_SECRET + " is required: gateway/downstream HMAC signing cannot start "
                    + "without a shared internal secret (no default is allowed)");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(ENV_SECRET + " must be at least " + MIN_SECRET_LENGTH + " characters");
        }
        return secret;
    }

    public static String sign(String secret, long userId, long timestampMillis, String nonce) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("secret must not be blank");
        }
        if (nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("nonce must not be blank");
        }
        String payload = payload(userId, timestampMillis, nonce);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute gateway signature", e);
        }
    }

    /**
     * 校验签名与时间窗。
     *
     * @return true 表示可信（来自持有共享密钥的网关）
     */
    public static boolean verify(String secret, String userIdHeader, String timestampHeader,
                                 String nonce, String signature) {
        if (secret == null || secret.isBlank() || signature == null || signature.isBlank()
                || userIdHeader == null || timestampHeader == null || nonce == null || nonce.isBlank()) {
            return false;
        }
        final long userId;
        final long timestamp;
        try {
            userId = Long.parseLong(userIdHeader.trim());
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - timestamp) > MAX_CLOCK_SKEW_MS) {
            return false;
        }
        String expected = sign(secret, userId, timestamp, nonce);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    private static String payload(long userId, long timestampMillis, String nonce) {
        return userId + ":" + timestampMillis + ":" + nonce;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
