package com.bond.transfer.share;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 分享链接的 token 生成与提取码摘要。
 *
 * <p>安全要点：</p>
 * <ul>
 *   <li>token 为 32 字节 {@link SecureRandom} 的 base64url（无填充，43 字符），不可枚举；</li>
 *   <li>提取码只存 HMAC-SHA256 摘要（hex），数据库泄露也无法还原明文；摘要输入绑定 token，
 *       不同链接的同一提取码摘要不同（防彩虹表/跨链接复用）；</li>
 *   <li>pepper 由环境变量提供（生产必须），未配置时由 {@link ShareLinkService} 降级并告警。</li>
 * </ul>
 */
public final class ShareCode {

    /** token 字节数：32 字节 → base64url 43 字符。 */
    public static final int TOKEN_BYTES = 32;

    /** 合法 token 形状（base64url 无填充）。 */
    private static final java.util.regex.Pattern TOKEN_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{" + 43 + "}$");

    /** 提取码形状：4-8 位字母或数字。 */
    private static final java.util.regex.Pattern CODE_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9]{4,8}$");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private ShareCode() {
    }

    /** 生成新 token（不可枚举）。 */
    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** 校验 token 形状（避免对明显非法的 token 做数据库查询/风控计数）。 */
    public static boolean isWellFormedToken(String token) {
        return token != null && TOKEN_PATTERN.matcher(token).matches();
    }

    /** 提取码形状：4-8 位字母或数字。 */
    public static boolean isWellFormedCode(String code) {
        return code != null && CODE_PATTERN.matcher(code).matches();
    }

    /** 计算提取码摘要：hex(HMAC-SHA256(pepper, token + ":" + code))。 */
    public static String hashCode(String pepper, String token, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((token + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** 常量时间比较摘要，避免时序侧信道。 */
    public static boolean verifyCode(String pepper, String token, String code, String expectedHash) {
        if (code == null || expectedHash == null || pepper == null || pepper.isBlank()) {
            return false;
        }
        String actual = hashCode(pepper, token, code);
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
