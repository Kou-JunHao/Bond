package com.bond.common.email;

import com.bond.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感配置加解密（task-63）：AES-256-GCM，密文格式 {@code enc:v1:<base64(iv||ciphertext+tag)>}。
 *
 * <p>密钥来源：{@code BOND_CONFIG_SECRET}，缺省回退 {@code INTERNAL_GATEWAY_SECRET}（生产已由网关
 * HMAC 共用同一环境变量注入）；两者都缺失时使用固定开发密钥并打 warn（仅本地/测试）。</p>
 *
 * <p>安全口径：</p>
 * <ul>
 *   <li>明文不落数据库；GET 接口只返回掩码（{@link #mask(String)}）。</li>
 *   <li>解密失败（密钥变更）抛 500 明确错误，绝不静默回退明文。</li>
 *   <li>本类不打日志输出明文。</li>
 * </ul>
 */
@Slf4j
public final class ConfigCipher {

    public static final String PREFIX = "enc:v1:";
    private static final String DEV_SECRET = "bond-dev-only-config-secret";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ConfigCipher(String secret) {
        this.key = new SecretKeySpec(sha256(secret == null ? DEV_SECRET : secret), "AES");
    }

    /** 按环境变量解析密钥（生产用）。 */
    public static ConfigCipher fromEnvironment() {
        String secret = System.getenv("BOND_CONFIG_SECRET");
        if (secret == null || secret.isBlank()) {
            secret = System.getenv("INTERNAL_GATEWAY_SECRET");
        }
        if (secret == null || secret.isBlank()) {
            log.warn("BOND_CONFIG_SECRET/INTERNAL_GATEWAY_SECRET 均未设置，敏感配置将使用开发密钥加密（仅限本地）");
        }
        return new ConfigCipher(secret);
    }

    /** 加密；null/空白原样返回（空 = 未配置）。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new BizCryptoException("配置加密失败", e);
        }
    }

    /** 解密；非 enc:v1: 前缀视为历史明文原样返回。 */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank() || !isEncrypted(stored)) {
            return stored;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (raw.length <= IV_LENGTH) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[raw.length - IV_LENGTH];
            System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
            System.arraycopy(raw, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException(500, "配置密钥无法解密（BOND_CONFIG_SECRET 是否已变更？）");
        }
    }

    /** 解密失败时返回 null（只读展示场景用）。 */
    public String tryDecrypt(String stored) {
        try {
            return decrypt(stored);
        } catch (RuntimeException e) {
            log.warn("敏感配置解密失败，按未配置处理: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** 脱敏展示：****abcd（末尾 4 位）；不足 5 位显示 ****；空返回空串。 */
    public String mask(String stored) {
        String plain = tryDecrypt(stored);
        if (plain == null || plain.isBlank()) {
            return "";
        }
        String trimmed = plain.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }

    /** 加密失败（系统级异常，不暴露密钥）。 */
    public static class BizCryptoException extends RuntimeException {
        public BizCryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
