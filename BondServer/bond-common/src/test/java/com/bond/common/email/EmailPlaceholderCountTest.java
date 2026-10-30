package com.bond.common.email;

import com.bond.common.entity.EmailTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * task-80：placeholders_left 计数（渲染结果中残留的 `{{`）。
 */
class EmailPlaceholderCountTest {

    @Test
    @DisplayName("正常渲染结果无残留 → 0")
    void normalRenderHasZeroPlaceholders() {
        EmailTemplate template = new EmailTemplate();
        template.setCode("verification_code");
        template.setSubject("验证码 {{code}}");
        template.setBodyHtml("<p>{{code}}</p>");
        template.setBodyText("{{code}}");
        template.setEnabled(true);

        EmailTemplateRenderer.RenderedEmail email =
                EmailTemplateRenderer.render(template, java.util.Map.of("code", "123456"));

        assertEquals(0, EmailTemplateRenderer.countPlaceholders(email));
    }

    @Test
    @DisplayName("正文里字面量 {{ 会计入；未知变量渲染时置空但字面量保留")
    void literalPlaceholderIsCounted() {
        EmailTemplate template = new EmailTemplate();
        template.setCode("x");
        template.setSubject("subject");
        template.setBodyHtml("<p>literal {{ text</p>");
        template.setBodyText("a {{ b {{ c");
        template.setEnabled(true);

        EmailTemplateRenderer.RenderedEmail email =
                EmailTemplateRenderer.render(template, java.util.Map.of());

        // html 1 个 + text 2 个
        assertEquals(3, EmailTemplateRenderer.countPlaceholders(email));
    }

    @Test
    @DisplayName("单段文本计数：不要求成对")
    void countPerText() {
        assertEquals(0, EmailTemplateRenderer.countPlaceholders(""));
        assertEquals(0, EmailTemplateRenderer.countPlaceholders("plain"));
        assertEquals(1, EmailTemplateRenderer.countPlaceholders("{{"));
        assertEquals(2, EmailTemplateRenderer.countPlaceholders("{{a}} {{b}}"));
    }
}
