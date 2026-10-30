package com.bond.common.email;

import com.bond.common.entity.EmailTemplate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 邮件模板变量护栏（task-79）。
 *
 * <p>背景：welcome 模板在 DB（V16 seed）里声明了 {@code email}，但发送方只传了 {@code username}，
 * 渲染器按既定语义把缺失占位符置空，用户看到的就是「邮箱  已绑定」。这类问题只有真机才暴露，
 * 因此在发送链路上做一次「模板声明 vs 实际提供」的差集检查：<b>只 WARN，绝不阻断发信</b>。</p>
 *
 * <p>本类只做纯函数计算（无日志、无 IO），便于单测；WARN 由调用方
 * {@link ConfigurableEmailGateway} 输出。</p>
 */
public final class EmailTemplateValidator {

    /** 网关发送时自动注入的变量，模板声明了这些不视为「未提供」。 */
    public static final Set<String> GATEWAY_DEFAULTS = Set.of("appName", "year", "supportEmail");

    private static final ObjectMapper JSON = new ObjectMapper();

    private EmailTemplateValidator() {
    }

    /**
     * 计算模板声明了、但既未提供也非网关默认的变量名（按声明顺序，去重）。
     *
     * @param declared 模板声明的变量（DB {@code variables} 列，或内置模板定义）
     * @param provided 发送方实际提供的变量；值为 {@code null} 或空白视为未提供
     * @param implicit 除网关默认外，调用方额外保证会补齐的变量（可为 null）
     */
    public static List<String> missingVariables(Collection<String> declared,
                                                Map<String, ?> provided,
                                                Collection<String> implicit) {
        if (declared == null || declared.isEmpty()) {
            return List.of();
        }
        Set<String> covered = new LinkedHashSet<>(GATEWAY_DEFAULTS);
        if (implicit != null) {
            covered.addAll(implicit);
        }
        if (provided != null) {
            for (Map.Entry<String, ?> entry : provided.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null
                        && !String.valueOf(entry.getValue()).isBlank()) {
                    covered.add(entry.getKey());
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (String name : declared) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String key = name.trim();
            if (!covered.contains(key) && !missing.contains(key)) {
                missing.add(key);
            }
        }
        return missing;
    }

    /** 便捷重载：从模板实体取声明变量。 */
    public static List<String> missingVariables(EmailTemplate template, Map<String, ?> provided) {
        if (template == null) {
            return List.of();
        }
        return missingVariables(parseVariables(template.getVariables()), provided, null);
    }

    /** 解析 {@code variables} JSON 数组（如 {@code ["username","email"]}）；空/坏数据返回空列表。 */
    public static List<String> parseVariables(String variablesJson) {
        if (variablesJson == null || variablesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = JSON.readValue(variablesJson, new TypeReference<List<String>>() {
            });
            return parsed == null ? List.of() : parsed;
        } catch (RuntimeException | java.io.IOException e) {
            return List.of();
        }
    }
}
