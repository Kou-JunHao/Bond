package com.bond.transfer.service;

import com.bond.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * 管理端配置写入的类型校验与规范化（task-106）。
 *
 * <p>按 {@code system_config.config_type} 校验并规范化后再落库：
 * boolean / int / long / double / json / string。类型未知时按 string 处理（老数据兜底），
 * 不做静默丢弃；非法值统一抛 {@link BusinessException}(400)，message 带配置键与期望类型。</p>
 */
public final class ConfigValueNormalizer {

    /** 单个配置值的字符上限（DB 为 TEXT，但后台配置不需要那么长）。 */
    public static final int MAX_VALUE_CHARS = 4000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ConfigValueNormalizer() {
    }

    /**
     * 校验并按类型规范化。
     *
     * @param key       配置键（只用于错误 message）
     * @param type      system_config.config_type
     * @param rawValue  原始值（null 视为空串）
     * @return 可直接落库的规范化值
     */
    public static String normalize(String key, String type, String rawValue) {
        String typeName = type == null || type.isBlank() ? "string" : type.trim().toLowerCase(Locale.ROOT);
        String value = rawValue == null ? "" : rawValue;
        if (value.length() > MAX_VALUE_CHARS) {
            throw new BusinessException(400, "配置项 " + key + " 的值超过 " + MAX_VALUE_CHARS + " 字符上限");
        }
        String trimmed = value.trim();
        return switch (typeName) {
            case "boolean" -> normalizeBoolean(key, trimmed);
            case "int" -> normalizeInt(key, trimmed);
            case "long" -> normalizeLong(key, trimmed);
            case "double" -> normalizeDouble(key, trimmed);
            case "json" -> normalizeJson(key, trimmed);
            // string / 未知类型：原样保存（保留前后空格），仅做长度校验
            default -> value;
        };
    }

    private static String normalizeBoolean(String key, String value) {
        String v = value.toLowerCase(Locale.ROOT);
        switch (v) {
            case "true", "1", "on", "yes":
                return "true";
            case "false", "0", "off", "no":
                return "false";
            default:
                throw new BusinessException(400,
                        "配置项 " + key + " 需要 boolean 类型（true/false/1/0/on/off/yes/no），实际值: " + value);
        }
    }

    private static String normalizeInt(String key, String value) {
        try {
            return Integer.toString(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw new BusinessException(400,
                    "配置项 " + key + " 需要 int 类型（整数），实际值: " + value);
        }
    }

    private static String normalizeLong(String key, String value) {
        try {
            return Long.toString(Long.parseLong(value));
        } catch (NumberFormatException e) {
            throw new BusinessException(400,
                    "配置项 " + key + " 需要 long 类型（长整数），实际值: " + value);
        }
    }

    private static String normalizeDouble(String key, String value) {
        try {
            double parsed = Double.parseDouble(value);
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                throw new NumberFormatException("not finite");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BusinessException(400,
                    "配置项 " + key + " 需要 double 类型（十进制数），实际值: " + value);
        }
    }

    private static String normalizeJson(String key, String value) {
        try {
            JsonNode ignored = JSON.readTree(value);
            if (ignored == null || ignored.isMissingNode()) {
                throw new IllegalArgumentException("empty json");
            }
            return value;
        } catch (Exception e) {
            throw new BusinessException(400,
                    "配置项 " + key + " 需要合法 JSON，实际值: " + value);
        }
    }
}
