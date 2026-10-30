package com.bond.transfer.document;

/**
 * 极简 Markdown → HTML 渲染（导出用，无第三方依赖）。
 *
 * <p>安全约定：<b>先按 Markdown 解析出结构，再在生成 HTML 的文本节点处逐段转义</b>，
 * 而不是先整体转义（否则引用 {@code >}、代码里的 {@code <tags>}、行内比较符会解析失败）。</p>
 *
 * <p>支持：标题、代码围栏、无序/有序列表、引用、水平线、段落，以及行内粗体/斜体/行内代码/链接。
 * 内嵌 HTML 不支持（按纯文本显示）；链接只允许 http/https/mailto，其余按纯文本输出。</p>
 */
public final class MarkdownHtmlRenderer {

    private MarkdownHtmlRenderer() {
    }

    private static final java.util.regex.Pattern HEADING =
            java.util.regex.Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final java.util.regex.Pattern UL = java.util.regex.Pattern.compile("^[-*+]\\s+(.*)$");
    private static final java.util.regex.Pattern OL = java.util.regex.Pattern.compile("^\\d+\\.\\s+(.*)$");
    private static final java.util.regex.Pattern HR =
            java.util.regex.Pattern.compile("^([-*_])\\1{2,}\\s*$");
    private static final java.util.regex.Pattern CODE_SPAN =
            java.util.regex.Pattern.compile("`([^`]*)`");
    private static final java.util.regex.Pattern BOLD =
            java.util.regex.Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final java.util.regex.Pattern ITALIC =
            java.util.regex.Pattern.compile("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)");
    private static final java.util.regex.Pattern LINK =
            java.util.regex.Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)\\)");

    /**
     * Markdown 图片：{@code ![alt](url "title")}。
     * <p>2026-10-30：用户反馈「预览与导出的 PDF/HTML 看不到 md 图片」——旧渲染器没有 image 分支，
     * 图片语法退化成文字。这里只放行**公网 http(s) 外链**与**本地内嵌 data:image base64**
     * （用户口径：md 支持这两种即可），其它协议按纯文本输出，避免 XSS。</p>
     * <p>必须在 {@link #LINK} 之前处理，否则 {@code [alt](url)} 会先被链接规则替换。</p>
     */
    private static final java.util.regex.Pattern IMAGE =
            java.util.regex.Pattern.compile("!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+&quot;([^&]*)&quot;)?\\)");

    private static final java.util.regex.Pattern DATA_IMAGE =
            java.util.regex.Pattern.compile("^data:image/(png|jpe?g|gif|webp|bmp);base64,[A-Za-z0-9+/=]+$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    public static String render(String title, String markdown) {
        String md = markdown == null ? "" : markdown;
        StringBuilder out = new StringBuilder(512 + md.length());
        StringBuilder paragraph = new StringBuilder();
        StringBuilder code = new StringBuilder();
        String listType = null;
        boolean inCode = false;
        boolean inQuote = false;

        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String raw : lines) {
            String trimmed = raw.strip();
            if (inCode) {
                if (trimmed.startsWith("```")) {
                    flushCode(out, code);
                    inCode = false;
                } else {
                    code.append(raw).append('\n');
                }
                continue;
            }
            if (trimmed.startsWith("```")) {
                flushParagraph(out, paragraph);
                listType = closeList(out, listType);
                inQuote = closeQuote(out, inQuote);
                inCode = true;
                code.setLength(0);
                continue;
            }
            if (trimmed.isEmpty()) {
                flushParagraph(out, paragraph);
                listType = closeList(out, listType);
                inQuote = closeQuote(out, inQuote);
                continue;
            }
            java.util.regex.Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                flushParagraph(out, paragraph);
                listType = closeList(out, listType);
                inQuote = closeQuote(out, inQuote);
                int level = heading.group(1).length();
                out.append("<h").append(level).append('>')
                        .append(inline(heading.group(2))).append("</h").append(level).append(">\n");
                continue;
            }
            if (HR.matcher(trimmed).matches()) {
                flushParagraph(out, paragraph);
                listType = closeList(out, listType);
                inQuote = closeQuote(out, inQuote);
                out.append("<hr/>\n");
                continue;
            }
            if (trimmed.startsWith(">")) {
                flushParagraph(out, paragraph);
                listType = closeList(out, listType);
                if (!inQuote) {
                    out.append("<blockquote>\n");
                    inQuote = true;
                }
                out.append("<p>").append(inline(trimmed.substring(1).strip())).append("</p>\n");
                continue;
            }
            inQuote = closeQuote(out, inQuote);
            java.util.regex.Matcher ul = UL.matcher(trimmed);
            if (ul.matches()) {
                flushParagraph(out, paragraph);
                if (!"ul".equals(listType)) {
                    listType = closeList(out, listType);
                    out.append("<ul>\n");
                    listType = "ul";
                }
                out.append("<li>").append(inline(ul.group(1))).append("</li>\n");
                continue;
            }
            java.util.regex.Matcher ol = OL.matcher(trimmed);
            if (ol.matches()) {
                flushParagraph(out, paragraph);
                if (!"ol".equals(listType)) {
                    listType = closeList(out, listType);
                    out.append("<ol>\n");
                    listType = "ol";
                }
                out.append("<li>").append(inline(ol.group(1))).append("</li>\n");
                continue;
            }
            listType = closeList(out, listType);
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(trimmed);
        }
        if (inCode) {
            flushCode(out, code);
        }
        flushParagraph(out, paragraph);
        closeList(out, listType);
        closeQuote(out, inQuote);

        return "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<title>"
                + escape(title) + "</title>\n</head>\n<body>\n" + out + "</body>\n</html>\n";
    }

    private static void flushParagraph(StringBuilder out, StringBuilder paragraph) {
        if (paragraph.length() == 0) {
            return;
        }
        out.append("<p>").append(inline(paragraph.toString())).append("</p>\n");
        paragraph.setLength(0);
    }

    private static void flushCode(StringBuilder out, StringBuilder code) {
        out.append("<pre><code>").append(escape(code.toString())).append("</code></pre>\n");
        code.setLength(0);
    }

    private static String closeList(StringBuilder out, String listType) {
        if (listType != null) {
            out.append("</").append(listType).append(">\n");
        }
        return null;
    }

    private static boolean closeQuote(StringBuilder out, boolean inQuote) {
        if (inQuote) {
            out.append("</blockquote>\n");
        }
        return false;
    }

    /** 行内语法：文本先转义，再解析代码/粗体/斜体/图片/链接（图片与链接 URL 做协议白名单）。 */
    static String inline(String text) {
        String escaped = escape(text);
        escaped = CODE_SPAN.matcher(escaped).replaceAll("<code>$1</code>");
        escaped = BOLD.matcher(escaped).replaceAll("<strong>$1</strong>");
        escaped = ITALIC.matcher(escaped).replaceAll("<em>$1</em>");

        // 图片要在链接之前处理：![alt](url) 的 [alt](url) 部分会命中 LINK。
        java.util.regex.Matcher img = IMAGE.matcher(escaped);
        StringBuilder images = new StringBuilder(escaped.length());
        while (img.find()) {
            String alt = img.group(1);
            String url = img.group(2);
            String title = img.group(3);
            String replacement = isSafeImageUrl(url)
                    ? "<img src=\"" + url + "\" alt=\"" + alt + "\""
                      + (title == null || title.isEmpty() ? "" : " title=\"" + title + "\"")
                      + " loading=\"lazy\" />"
                    : alt;
            img.appendReplacement(images, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        img.appendTail(images);
        escaped = images.toString();

        java.util.regex.Matcher m = LINK.matcher(escaped);
        StringBuilder sb = new StringBuilder(escaped.length());
        while (m.find()) {
            String label = m.group(1);
            String url = m.group(2);
            String replacement = isSafeUrl(url)
                    ? "<a href=\"" + url + "\" rel=\"noreferrer noopener\">" + label + "</a>"
                    : label;
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 图片 URL 白名单：公网 http/https 外链，或本地内嵌 {@code data:image/<type>;base64,...}。
     * 其它协议（javascript:、file:、相对路径等）一律不渲染成 img。
     */
    static boolean isSafeImageUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return true;
        }
        return DATA_IMAGE.matcher(url).matches();
    }

    /** 只允许 http/https/mailto（大小写不敏感）；javascript:/data: 等一律丢弃。 */
    static boolean isSafeUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:");
    }

    static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
