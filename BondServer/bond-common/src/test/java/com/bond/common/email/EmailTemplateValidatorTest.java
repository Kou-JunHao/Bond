package com.bond.common.email;

import com.bond.common.entity.EmailTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-79：模板声明变量 vs 实际提供变量的差集护栏（纯函数，WARN 逻辑由网关承接）。
 */
class EmailTemplateValidatorTest {

    @Test
    @DisplayName("声明了但未提供 → 返回缺失变量（welcome 缺 email 的回归）")
    void reportsDeclaredButNotProvided() {
        List<String> missing = EmailTemplateValidator.missingVariables(
                List.of("username", "email"), Map.of("username", "alice"), null);

        assertEquals(List.of("email"), missing);
    }

    @Test
    @DisplayName("网关默认注入变量（appName/year/supportEmail）不算缺失")
    void gatewayDefaultsAreCovered() {
        List<String> missing = EmailTemplateValidator.missingVariables(
                List.of("appName", "year", "supportEmail"), Map.of(), null);

        assertTrue(missing.isEmpty());
    }

    @Test
    @DisplayName("提供空串/空白视为未提供")
    void blankValueCountsAsMissing() {
        List<String> missing = EmailTemplateValidator.missingVariables(
                List.of("email", "username"), Map.of("email", "  ", "username", "alice"), null);

        assertEquals(List.of("email"), missing);
    }

    @Test
    @DisplayName("多提供未声明的变量不影响结果")
    void extraProvidedVariablesIgnored() {
        List<String> missing = EmailTemplateValidator.missingVariables(
                List.of("username"), Map.of("username", "alice", "email", "a@b.c", "reason", "x"), null);

        assertTrue(missing.isEmpty());
    }

    @Test
    @DisplayName("声明为空/空列表/整表为空 → 无缺失")
    void emptyDeclaredMeansNothingMissing() {
        assertTrue(EmailTemplateValidator.missingVariables((List<String>) null, Map.of(), null).isEmpty());
        assertTrue(EmailTemplateValidator.missingVariables(List.of(), null, null).isEmpty());
    }

    @Test
    @DisplayName("implicit 变量可由调用方声明为已覆盖")
    void implicitVariablesCovered() {
        List<String> missing = EmailTemplateValidator.missingVariables(
                List.of("code", "actionUrl"), Map.of("code", "123456"), List.of("actionUrl"));

        assertTrue(missing.isEmpty());
    }

    @Test
    @DisplayName("模板实体重载：解析 variables JSON 并去重、按声明顺序返回")
    void templateOverloadParsesVariables() {
        EmailTemplate template = new EmailTemplate();
        template.setVariables("[\"username\",\"email\",\"email\"]");

        List<String> missing = EmailTemplateValidator.missingVariables(
                template, Map.of("username", "alice"));

        assertEquals(List.of("email"), missing);
    }

    @Test
    @DisplayName("variables JSON 解析：正常 / 空 / 坏数据")
    void parseVariablesHandlesBadInput() {
        assertEquals(List.of("a", "b"), EmailTemplateValidator.parseVariables("[\"a\",\"b\"]"));
        assertTrue(EmailTemplateValidator.parseVariables(null).isEmpty());
        assertTrue(EmailTemplateValidator.parseVariables("  ").isEmpty());
        assertTrue(EmailTemplateValidator.parseVariables("not-json").isEmpty());
    }
}
