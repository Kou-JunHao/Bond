package com.bond.transfer.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 白名单 HTML 净化器测试（task-137）。
 *
 * <p>覆盖 lead 亲自验收的用例：`<p onclick="x()">a<script>alert(1)</script><img src="x" onerror="y()"></p>`
 * 必须只留下结构、无脚本、无事件属性、无危险协议。</p>
 */
class ContentSanitizerTest {

    @Test
    @DisplayName("lead 验收用例：script 整段删除、事件属性删除、结构保留")
    void removesScriptAndEventHandlers() {
        String dirty = "<p onclick=\"x()\">a<script>alert(1)</script><img src=\"x\" onerror=\"y()\"></p>";
        String clean = ContentSanitizer.sanitize(dirty);

        assertFalse(clean.contains("<script"), "script 标签必须被删除");
        assertFalse(clean.contains("alert(1)"), "script 内容必须整段删除");
        assertFalse(clean.contains("onclick"), "onclick 必须删除");
        assertFalse(clean.contains("onerror"), "onerror 必须删除");
        assertTrue(clean.contains("<p>a"), "文本与段落结构保留");
        assertTrue(clean.contains("<img src=\"x\">"), "img 及其安全属性保留");
        assertEquals("<p>a<img src=\"x\"></p>", clean);
    }

    @Test
    @DisplayName("javascript/vbscript/data 协议属性被删除")
    void removesUnsafeSchemes() {
        String clean = ContentSanitizer.sanitize(
                "<a href=\"javascript:alert(1)\" title=\"t\">x</a>"
                        + "<img src=\"data:image/png;base64,AAAA\">"
                        + "<a href=\"/api/files/1/content\">ok</a>");
        assertFalse(clean.contains("javascript:"));
        assertFalse(clean.contains("data:"));
        assertFalse(clean.contains("src=\"data"));
        assertTrue(clean.contains("<a title=\"t\">x</a>"));
        assertTrue(clean.contains("<a href=\"/api/files/1/content\">ok</a>"));
    }

    @Test
    @DisplayName("白名单结构完整保留（标题/列表/表格/代码）")
    void keepsAllowedStructure() {
        String html = "<h2>标题</h2><ul><li>a</li><li>b</li></ul>"
                + "<table><thead><tr><th>h</th></tr></thead><tbody><tr><td>c</td></tr></tbody></table>"
                + "<pre><code>x = 1;</code></pre><p><strong>粗</strong><em>斜</em></p>";
        assertEquals(html, ContentSanitizer.sanitize(html));
    }

    @Test
    @DisplayName("非白名单标签丢弃但保留文本")
    void dropsUnknownTagsKeepsText() {
        assertEquals("hi", ContentSanitizer.sanitize("<custom>hi</custom>"));
        assertEquals("hi", ContentSanitizer.sanitize("<font color=\"red\">hi</font>"));
    }

    @Test
    @DisplayName("style 属性与危险块整体删除")
    void removesStyleAttributesAndDangerousBlocks() {
        String clean = ContentSanitizer.sanitize(
                "<p style=\"color:red\">a</p><iframe src=\"https://e\">x</iframe><style>body{}</style><p>b</p>");
        assertEquals("<p>a</p><p>b</p>", clean);
    }

    @Test
    @DisplayName("自闭合 img/br/hr 正常，空值与 null 安全")
    void handlesVoidTagsAndEmptyInput() {
        assertEquals("<img src=\"a\"><br><hr>",
                ContentSanitizer.sanitize("<img src=\"a\" /><br/><hr />"));
        assertEquals("", ContentSanitizer.sanitize(null));
        assertEquals("", ContentSanitizer.sanitize(""));
        assertEquals("plain", ContentSanitizer.sanitize("plain"));
    }

    @Test
    @DisplayName("属性注入（多余引号/控制字符）被清理")
    void stripsAttributeInjection() {
        String clean = ContentSanitizer.sanitize(
                "<a href=\"/x\" onmouseover=\"y\" title=\"a&#10;b\">t</a>");
        assertFalse(clean.contains("onmouseover"));
        assertTrue(clean.contains("<a href=\"/x\" title="));
    }
}
