package com.bond.common.email;

import com.bond.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感配置加解密（task-63）：AES-GCM 往返、enc:v1: 前缀、掩码、旧明文兼容、错误密钥。
 */
class ConfigCipherTest {

    private final ConfigCipher cipher = new ConfigCipher("unit-test-config-secret");

    @Test
    @DisplayName("加密→解密往返；密文带 enc:v1: 前缀且不含明文")
    void encryptDecryptRoundTrip() {
        String encrypted = cipher.encrypt("smtp-password-123456");

        assertTrue(encrypted.startsWith(ConfigCipher.PREFIX));
        assertFalse(encrypted.contains("smtp-password-123456"));
        assertNotEquals("smtp-password-123456", encrypted);
        assertEquals("smtp-password-123456", cipher.decrypt(encrypted));
    }

    @Test
    @DisplayName("同一明文两次加密密文不同（随机 IV）")
    void encryptionUsesRandomIv() {
        String a = cipher.encrypt("same-secret");
        String b = cipher.encrypt("same-secret");

        assertNotEquals(a, b);
        assertEquals("same-secret", cipher.decrypt(a));
        assertEquals("same-secret", cipher.decrypt(b));
    }

    @Test
    @DisplayName("掩码只露末 4 位；短值显示 ****；空值返回空串")
    void mask() {
        assertEquals("****3456", cipher.mask(cipher.encrypt("smtp-password-123456")));
        assertEquals("****", cipher.mask(cipher.encrypt("abc")));
        assertEquals("", cipher.mask(null));
        assertEquals("", cipher.mask(""));
    }

    @Test
    @DisplayName("历史明文（非 enc:v1:）原样通过；null/空值原样返回")
    void plaintextPassThrough() {
        assertEquals("legacy-plain", cipher.decrypt("legacy-plain"));
        assertFalse(cipher.isEncrypted("legacy-plain"));
        assertNull(cipher.encrypt(null));
        assertEquals("", cipher.encrypt(""));
    }

    @Test
    @DisplayName("错误密钥解密抛 500，不静默回退明文")
    void wrongKeyThrows500() {
        String encrypted = cipher.encrypt("secret-value");
        ConfigCipher other = new ConfigCipher("another-secret");

        BusinessException ex = assertThrows(BusinessException.class, () -> other.decrypt(encrypted));
        assertEquals(500, ex.getCode());
        assertNull(other.tryDecrypt(encrypted));
    }
}
