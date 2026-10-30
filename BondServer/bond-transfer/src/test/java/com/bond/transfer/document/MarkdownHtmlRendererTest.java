package com.bond.transfer.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 导出用 Markdown→HTML：结构解析 + 文本节点转义 + 链接协议白名单。 */
class MarkdownHtmlRendererTest {

    @Test
    @DisplayName("标题与段落：文本节点转义，内嵌 HTML 按纯文本显示")
    void headingsAndInlineHtmlAreEscaped() {
        String html = MarkdownHtmlRenderer.render("<t>", "# <b>标题</b>\n\n段落 a < b");
        assertTrue(html.contains("<title>&lt;t&gt;</title>"));
        assertTrue(html.contains("<h1>&lt;b&gt;标题&lt;/b&gt;</h1>"));
        assertTrue(html.contains("<p>段落 a &lt; b</p>"));
        assertFalse(html.contains("<b>标题</b>"));
    }

    @Test
    @DisplayName("引用：> 是 Markdown 语法，行内 > 按字面转义")
    void blockquoteUsesSyntaxButEscapesContent() {
        String html = MarkdownHtmlRenderer.render("t", "> 引用 a > b");
        assertTrue(html.contains("<blockquote>"));
        assertTrue(html.contains("<p>引用 a &gt; b</p>"));
    }

    @Test
    @DisplayName("代码围栏：内容整体转义（<tags> 按字面显示）")
    void codeFenceEscapesTags() {
        String html = MarkdownHtmlRenderer.render("t", "```java\nif (a < b && c > d) {}\n```");
        assertTrue(html.contains("<pre><code>"));
        assertTrue(html.contains("&lt;"));
        assertTrue(html.contains("&amp;&amp;"));
        assertTrue(html.contains("&gt;"));
        assertFalse(html.contains("if (a < b"));
    }

    @Test
    @DisplayName("列表：无序与有序各自成组")
    void listsRender() {
        String ul = MarkdownHtmlRenderer.render("t", "- a\n- b");
        assertTrue(ul.contains("<ul>"));
        assertTrue(ul.contains("<li>a</li>"));
        String ol = MarkdownHtmlRenderer.render("t", "1. a\n2. b");
        assertTrue(ol.contains("<ol>"));
        assertTrue(ol.contains("<li>b</li>"));
    }

    @Test
    @DisplayName("行内：粗体/斜体/行内代码按字面转义后渲染")
    void inlineStyles() {
        String html = MarkdownHtmlRenderer.render("t", "**粗** *斜* `a<b`");
        assertTrue(html.contains("<strong>粗</strong>"));
        assertTrue(html.contains("<em>斜</em>"));
        assertTrue(html.contains("<code>a&lt;b</code>"));
    }

    @Test
    @DisplayName("链接：http/https/mailto 保留，javascript: 丢弃只留文字")
    void linksWhitelist() {
        String ok = MarkdownHtmlRenderer.render("t", "[官网](https://bond.example.com)");
        assertTrue(ok.contains("<a href=\"https://bond.example.com\" rel=\"noreferrer noopener\">官网</a>"));
        String bad = MarkdownHtmlRenderer.render("t", "[点我](javascript:alert(1))");
        assertFalse(bad.contains("<a href"));
        assertTrue(bad.contains("点我"));
    }

    @Test
    @DisplayName("图片：https 外链保留为 <img src>，且不会被链接规则吃掉")
    void imageExternalLinkRenders() {
        String html = MarkdownHtmlRenderer.render("t",
                "![架构图](https://cdn.example.com/a.png \"标题\")");
        assertTrue(html.contains("<img src=\"https://cdn.example.com/a.png\" alt=\"架构图\" title=\"标题\""));
        assertFalse(html.contains("<a href=\"https://cdn.example.com/a.png\">"));
    }

    @Test
    @DisplayName("图片：本地内嵌 data:image base64 保留")
    void imageDataUriRenders() {
        String data = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==";
        String html = MarkdownHtmlRenderer.render("t", "![截图](" + data + ")");
        assertTrue(html.contains("<img src=\"" + data + "\" alt=\"截图\""));
    }

    @Test
    @DisplayName("图片：javascript:/file:/相对路径不渲染成 img，只保留 alt 文字")
    void imageUnsafeSchemesRejected() {
        assertFalse(MarkdownHtmlRenderer.render("t", "![x](javascript:alert(1))").contains("<img"));
        assertFalse(MarkdownHtmlRenderer.render("t", "![x](file:///c:/a.png)").contains("<img"));
        assertFalse(MarkdownHtmlRenderer.render("t", "![x](/api/files/1/content?token=t)").contains("<img"));
    }

    @Test
    @DisplayName("水平线与空文档")
    void hrAndEmpty() {
        assertTrue(MarkdownHtmlRenderer.render("t", "---").contains("<hr/>"));
        String empty = MarkdownHtmlRenderer.render("t", "");
        assertTrue(empty.contains("<!DOCTYPE html>"));
        assertTrue(empty.contains("<title>t</title>"));
    }
}
