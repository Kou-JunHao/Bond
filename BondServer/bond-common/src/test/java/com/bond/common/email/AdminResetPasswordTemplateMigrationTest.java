package com.bond.common.email;

import com.bond.common.entity.EmailTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：管理端重置密码邮件必须包含临时密码。
 *
 * <p>V16 的 admin_reset_password 模板只写「临时密码由管理员另行告知」，且 variables 没有 code；
 * DB 模板优先于内置模板，导致线上邮件没有临时密码。V35 负责修正 DB 行。</p>
 */
class AdminResetPasswordTemplateMigrationTest {

    private static final String V35 =
            "/db/migration/V35__fix_admin_reset_password_template.sql";

    @Test
    @DisplayName("V35 模板正文包含 {{code}}，且 variables 声明 code")
    void migrationTemplateContainsCode() throws IOException {
        String sql = readResource(V35);
        assertTrue(sql.contains("{{code}}"), "V35 正文必须包含临时密码占位符");
        assertTrue(sql.contains("\"code\""), "V35 variables 必须声明 code");
        assertTrue(sql.contains("admin_reset_password"), "V35 必须只更新管理员重置模板");
    }

    @Test
    @DisplayName("V35 的 HTML/纯文本模板渲染后包含临时密码")
    void renderedTemplateContainsTempPassword() throws IOException {
        String sql = readResource(V35);
        String html = extractDollarBlock(sql, "$html$");
        String text = extractDollarBlock(sql, "$text$");

        EmailTemplate template = new EmailTemplate();
        template.setCode(BuiltInEmailTemplates.ADMIN_RESET_PASSWORD);
        template.setSubject("{{appName}} 账号密码已被重置");
        template.setBodyHtml(html);
        template.setBodyText(text);

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("code", "TmpPass-2026");
        vars.put("username", "alice");
        vars.put("appName", "Bond");
        vars.put("year", "2026");
        vars.put("supportEmail", "support@example.com");

        EmailTemplateRenderer.RenderedEmail rendered = EmailTemplateRenderer.render(template, vars);

        assertTrue(rendered.html().contains("TmpPass-2026"), "HTML 必须渲染出临时密码");
        assertTrue(rendered.text().contains("TmpPass-2026"), "纯文本必须渲染出临时密码");
        assertFalse(rendered.html().contains("{{code}}"), "HTML 不应残留占位符");
        assertFalse(rendered.text().contains("{{code}}"), "纯文本不应残留占位符");
    }

    private static String extractDollarBlock(String sql, String delimiter) {
        int start = sql.indexOf(delimiter);
        assertTrue(start >= 0, "V35 缺少 " + delimiter);
        int bodyStart = start + delimiter.length();
        int end = sql.indexOf(delimiter, bodyStart);
        assertTrue(end > bodyStart, "V35 的 " + delimiter + " 未闭合");
        return sql.substring(bodyStart, end);
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = AdminResetPasswordTemplateMigrationTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "classpath 缺少 " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
