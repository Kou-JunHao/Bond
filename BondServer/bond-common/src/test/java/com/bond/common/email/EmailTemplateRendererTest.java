package com.bond.common.email;

import com.bond.common.entity.EmailTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件模板渲染器（task-63）：白名单替换、HTML 转义、未知变量保留、actionUrl 行省略、
 * body_text 缺失时由 HTML 粗转纯文本。
 */
class EmailTemplateRendererTest {

    private static EmailTemplate template(String subject, String html, String text) {
        EmailTemplate t = new EmailTemplate();
        t.setCode("verification_code");
        t.setSubject(subject);
        t.setBodyHtml(html);
        t.setBodyText(text);
        return t;
    }

    private static Map<String, String> vars(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("HTML 中转义变量值，正文中保持原文")
    void escapesHtmlButNotText() {
        EmailTemplate t = template("你好 {{username}}",
                "<p>用户 {{username}} 的验证码是 {{code}}</p>",
                "用户 {{username}} 的验证码是 {{code}}");

        EmailTemplateRenderer.RenderedEmail rendered = EmailTemplateRenderer.render(t,
                vars("username", "<b>张三</b>", "code", "123456"));

        assertEquals("你好 <b>张三</b>", rendered.subject());
        assertTrue(rendered.html().contains("&lt;b&gt;张三&lt;/b&gt;"));
        assertFalse(rendered.html().contains("<b>张三</b>"));
        assertTrue(rendered.text().contains("<b>张三</b>"));
        assertTrue(rendered.text().contains("123456"));
    }

    @Test
    @DisplayName("未知变量置空（task-76：不把 {{xxx}} 原样发出去）")
    void unknownVariableBlanked() {
        EmailTemplate t = template("{{unknownVar}}", "<p>{{unknownVar}}</p>", "x {{unknownVar}}");

        EmailTemplateRenderer.RenderedEmail rendered = EmailTemplateRenderer.render(t, Map.of());

        assertFalse(rendered.subject().contains("{{"));
        assertFalse(rendered.html().contains("{{"));
        assertFalse(rendered.text().contains("{{"));
    }

    @Test
    @DisplayName("actionUrl 为空时整行省略（HTML 与 text）")
    void dropsActionUrlLineWhenBlank() {
        EmailTemplate t = template("欢迎",
                "<p>你好 {{username}}</p>\n<p><a href=\"{{actionUrl}}\">打开 {{appName}}</a></p>\n<p>底部</p>",
                "你好 {{username}}\n打开 {{actionUrl}}\n底部");

        EmailTemplateRenderer.RenderedEmail rendered = EmailTemplateRenderer.render(t,
                vars("username", "alice", "appName", "Bond"));

        assertFalse(rendered.html().contains("actionUrl"));
        assertFalse(rendered.html().contains("打开 Bond"));
        assertFalse(rendered.text().contains("actionUrl"));
        assertTrue(rendered.html().contains("你好 alice"));
    }

    @Test
    @DisplayName("body_text 缺失时由 HTML 粗转纯文本（去标签、保留换行）")
    void fallsBackToHtmlToText() {
        EmailTemplate t = template("主题",
                "<h2>标题</h2><p>第一段 {{code}}</p><p>第二段</p>", null);

        EmailTemplateRenderer.RenderedEmail rendered = EmailTemplateRenderer.render(t, vars("code", "654321"));

        assertTrue(rendered.text().contains("标题"));
        assertTrue(rendered.text().contains("第一段 654321"));
        assertTrue(rendered.text().contains("第二段"));
        assertFalse(rendered.text().contains("<p>"));
    }

    @Test
    @DisplayName("HTML 转义辅助函数覆盖 & < > \" '")
    void escapeHtml() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", EmailTemplateRenderer.escapeHtml("&<>\"'"));
        assertEquals("", EmailTemplateRenderer.escapeHtml(null));
    }

    @Test
    @DisplayName("task-76：action 变量允许且可用，HTML 中转义、标题保留原值")
    void actionVariableIsAllowedAndResolved() {
        EmailTemplate t = template("动作 {{action}}", "<a href=\"{{action}}\">点我</a>", null);

        EmailTemplateRenderer.RenderedEmail rendered = EmailTemplateRenderer.render(t,
                vars("action", "https://example.com/a?x=1&y=2"));

        assertTrue(rendered.subject().contains("https://example.com/a?x=1&y=2"));
        assertTrue(rendered.html().contains("href=\"https://example.com/a?x=1&amp;y=2\""));
    }

    @Test
    @DisplayName("task-76：missingVariables 列出引用但未提供的变量")
    void listsMissingVariables() {
        EmailTemplate t = template("{{code}}", "<p>{{username}}</p>", null);

        assertEquals(java.util.List.of("username"),
                EmailTemplateRenderer.missingVariables(t, vars("code", "123456")));
        assertTrue(EmailTemplateRenderer.missingVariables(t, Map.of())
                .containsAll(java.util.List.of("code", "username")));
    }

    @Test
    @DisplayName("task-76：referencedVariables 解析标题/HTML/文本并去重、忽略未知")
    void referencedVariablesParsed() {
        assertEquals(java.util.List.of("code", "username"),
                EmailTemplateRenderer.referencedVariables(
                        "{{code}} {{unknown}}", "<p>{{username}}</p><p>{{code}}</p>", null));
    }
}
