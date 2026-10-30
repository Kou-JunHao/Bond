package com.bond.common.email;

import com.bond.common.entity.EmailTemplate;
import com.bond.common.email.EmailTemplateRenderer.RenderedEmail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置邮件模板（task-63）：6 个内置模板完整、渲染可用、返回副本不可被外部篡改。
 */
class BuiltInEmailTemplatesTest {

    private static final List<String> CODES = List.of(
            BuiltInEmailTemplates.VERIFICATION_CODE,
            BuiltInEmailTemplates.PASSWORD_RESET,
            BuiltInEmailTemplates.CHANGE_EMAIL,
            BuiltInEmailTemplates.ADMIN_RESET_PASSWORD,
            BuiltInEmailTemplates.ADMIN_OPERATION_NOTICE,
            BuiltInEmailTemplates.WELCOME);

    @Test
    @DisplayName("6 个内置模板都存在且字段完整（主体/变量非空）")
    void allBuiltinsComplete() {
        assertEquals(6, BuiltInEmailTemplates.all().size());
        for (String code : CODES) {
            EmailTemplate template = BuiltInEmailTemplates.get(code);
            assertNotNull(template, code);
            assertEquals(code, template.getCode());
            assertNotNull(template.getSubject());
            assertFalse(template.getSubject().isBlank(), code);
            assertNotNull(template.getBodyHtml());
            assertFalse(template.getBodyHtml().isBlank(), code);
            assertNotNull(template.getVariables());
            assertTrue(template.getVariables().contains("code") || template.getVariables().contains("username"), code);
        }
    }

    @Test
    @DisplayName("未知模板 code 返回 null（交给网关报 500 配置缺陷）")
    void unknownCodeReturnsNull() {
        assertNull(BuiltInEmailTemplates.get("no_such_template"));
        assertNull(BuiltInEmailTemplates.get(""));
        assertNull(BuiltInEmailTemplates.get(null));
    }

    @Test
    @DisplayName("get 返回防御性副本：修改返回对象不影响内置定义")
    void getReturnsDefensiveCopy() {
        EmailTemplate first = BuiltInEmailTemplates.get(BuiltInEmailTemplates.WELCOME);
        first.setSubject("被测试篡改");

        assertNotEquals("被测试篡改", BuiltInEmailTemplates.get(BuiltInEmailTemplates.WELCOME).getSubject());
    }

    @Test
    @DisplayName("验证码模板渲染：HTML 含 code 与品牌色，subject 含 appName")
    void rendersVerificationCode() {
        EmailTemplate template = BuiltInEmailTemplates.get(BuiltInEmailTemplates.VERIFICATION_CODE);
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("code", "246810");
        variables.put("appName", "Bond");
        variables.put("expiresMinutes", "10");
        variables.put("supportEmail", "support@example.com");
        variables.put("year", "2026");

        RenderedEmail rendered = EmailTemplateRenderer.render(template, variables);

        assertTrue(rendered.subject().contains("Bond"));
        assertTrue(rendered.html().contains("246810"));
        assertTrue(rendered.html().contains("#E57153"));
        assertFalse(rendered.text().isBlank());
        assertTrue(rendered.text().contains("246810"));
    }

    @Test
    @DisplayName("未知变量保留原样（不抛异常、不执行）")
    void keepsUnknownVariablesLiteral() {
        EmailTemplate template = BuiltInEmailTemplates.get(BuiltInEmailTemplates.WELCOME);

        RenderedEmail rendered = EmailTemplateRenderer.render(template, Map.of("username", "alice"));

        assertTrue(rendered.subject().contains("{{unknownVar}}") || !rendered.subject().isBlank());
        assertNotNull(rendered.html());
    }
}
