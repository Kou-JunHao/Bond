package com.bond.transfer.service;

import com.bond.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-106：管理端配置写入的类型校验/规范化。
 */
class ConfigValueNormalizerTest {

    private static BusinessException expect400(String key, String type, String value) {
        return assertThrows(BusinessException.class, () -> ConfigValueNormalizer.normalize(key, type, value));
    }

    @Test
    @DisplayName("boolean：on/yes/1 → true；off/no/0 → false；其他 400")
    void booleanNormalization() {
        assertEquals("true", ConfigValueNormalizer.normalize("k", "boolean", "on"));
        assertEquals("true", ConfigValueNormalizer.normalize("k", "BOOLEAN", " YES "));
        assertEquals("true", ConfigValueNormalizer.normalize("k", "boolean", "1"));
        assertEquals("false", ConfigValueNormalizer.normalize("k", "boolean", "off"));
        assertEquals("false", ConfigValueNormalizer.normalize("k", "boolean", "0"));

        BusinessException e = expect400("feature.flag", "boolean", "whatever");
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("feature.flag"));
        assertTrue(e.getMessage().contains("boolean"));
    }

    @Test
    @DisplayName("int/long：trim 后必须是合法整数")
    void integerNormalization() {
        assertEquals("42", ConfigValueNormalizer.normalize("k", "int", " 42 "));
        assertEquals("-7", ConfigValueNormalizer.normalize("k", "int", "-7"));
        assertEquals("9223372036854775807",
                ConfigValueNormalizer.normalize("k", "long", "9223372036854775807"));

        assertEquals(400, expect400("upload.k", "int", "abc").getCode());
        assertEquals(400, expect400("upload.k", "long", "12x").getCode());
        // 小数不是 int
        assertEquals(400, expect400("upload.k", "int", "1.5").getCode());
    }

    @Test
    @DisplayName("double：合法十进制；NaN/Infinity 拒绝")
    void doubleNormalization() {
        assertEquals("1.5", ConfigValueNormalizer.normalize("k", "double", "1.5"));
        assertEquals(400, expect400("k", "double", "NaN").getCode());
        assertEquals(400, expect400("k", "double", "1..2").getCode());
    }

    @Test
    @DisplayName("json：必须能被 Jackson 解析")
    void jsonNormalization() {
        assertEquals("{\"a\":1}", ConfigValueNormalizer.normalize("k", "json", "{\"a\":1}"));
        assertEquals("[1,2]", ConfigValueNormalizer.normalize("k", "json", " [1,2] ").trim());
        assertEquals(400, expect400("k", "json", "{bad").getCode());
        assertEquals(400, expect400("k", "json", "").getCode());
    }

    @Test
    @DisplayName("string/未知类型：原样保留（不 trim）；null 视为空串")
    void stringPassthrough() {
        assertEquals("  keep  ", ConfigValueNormalizer.normalize("k", "string", "  keep  "));
        assertEquals("  keep  ", ConfigValueNormalizer.normalize("k", "weird", "  keep  "));
        assertEquals("", ConfigValueNormalizer.normalize("k", "string", null));
        assertEquals("", ConfigValueNormalizer.normalize("k", null, null));
    }

    @Test
    @DisplayName("超过 4000 字符 → 400（不论类型）")
    void tooLongRejected() {
        String huge = "x".repeat(ConfigValueNormalizer.MAX_VALUE_CHARS + 1);
        BusinessException e = expect400("some.key", "string", huge);
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("4000"));
    }
}
