package com.bond.transfer.document;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 富文本 HTML 白名单净化器（task-137）。
 *
 * <p>策略（纯 JDK 手写，不引入新依赖）：</p>
 * <ol>
 *   <li>整段删除危险元素：{@code script/style/iframe/object/embed/link/meta/base/form/input/button/textarea/select/svg/math/template/noscript}；</li>
 *   <li>删除 HTML 注释；</li>
 *   <li>逐个标签重建：非白名单标签直接丢弃（保留其文本内容），白名单标签只保留属性白名单；</li>
 *   <li>属性白名单：{@code href/src/alt/title/width/height/colspan/rowspan/start}；{@code on*} 事件属性与 {@code style} 一律删除；</li>
 *   <li>{@code href/src} 只允许 {@code http/https/mailto/tel/相对路径/锚点}，出现 {@code javascript:/vbscript:/data:} 直接删除该属性。</li>
 * </ol>
 *
 * <p>净化为**非严格 XML/HTML 解析**：不保证文档结构完全合法（例如未闭合标签按原样保留），
 * 但保证输出中不出现可执行脚本、事件属性与危险协议。<b>输出不转义文本节点</b>，
 * 因此上游必须只把净化后的内容落库/回显。</p>
 */
public final class ContentSanitizer {

    private ContentSanitizer() {
    }

    private static final Set<String> ALLOWED_TAGS = Set.of(
            "p", "br", "div", "span", "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "blockquote", "pre", "code",
            "strong", "b", "em", "i", "u", "s", "del", "ins", "mark", "sub", "sup",
            "a", "img", "table", "thead", "tbody", "tr", "th", "td", "hr");

    private static final Set<String> VOID_TAGS = Set.of("br", "img", "hr");

    private static final Set<String> ALLOWED_ATTRS = Set.of(
            "href", "src", "alt", "title", "width", "height", "colspan", "rowspan", "start");

    /** 危险元素（连同内容整段删除，大小写不敏感、dotall）。 */
    private static final Pattern DANGEROUS_BLOCK = Pattern.compile(
            "(?is)<(script|style|iframe|object|embed|link|meta|base|form|textarea|select|svg|math|template|noscript)\\b[^>]*>.*?</\\1\\s*>");

    /** 仍残留的危险标签（自闭合或未成对）：直接删除标签本身。 */
    private static final Pattern DANGEROUS_TAG = Pattern.compile(
            "(?is)</?(script|style|iframe|object|embed|link|meta|base|form|input|button|textarea|select|svg|math|template|noscript)\\b[^>]*>");

    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");

    /** 任意标签。 */
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private static final Pattern TAG_NAME = Pattern.compile("^<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9:-]*)(.*)$", Pattern.DOTALL);

    private static final Pattern ATTR = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'<>`]+)))?");

    private static final Pattern UNSAFE_SCHEME = Pattern.compile("(?i)(javascript|vbscript|data)\\s*:");

    private static final Pattern STYLE_OR_EVENT_ATTR = Pattern.compile(
            "(?i)\\s+(?:on[a-z0-9_-]+|style)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");

    /** 白名单净化入口；{@code null} 返回空串。 */
    public static String sanitize(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String value = COMMENT.matcher(html).replaceAll("");
        value = DANGEROUS_BLOCK.matcher(value).replaceAll("");
        value = DANGEROUS_TAG.matcher(value).replaceAll("");
        value = STYLE_OR_EVENT_ATTR.matcher(value).replaceAll("");

        Matcher matcher = TAG.matcher(value);
        StringBuilder out = new StringBuilder(value.length());
        int last = 0;
        while (matcher.find()) {
            out.append(value, last, matcher.start());
            out.append(sanitizeTag(matcher.group()));
            last = matcher.end();
        }
        out.append(value.substring(last));
        return out.toString();
    }

    private static String sanitizeTag(String rawTag) {
        Matcher nameMatcher = TAG_NAME.matcher(rawTag);
        if (!nameMatcher.matches()) {
            return "";
        }
        boolean closing = !nameMatcher.group(1).isEmpty();
        String name = nameMatcher.group(2).toLowerCase(Locale.ROOT);
        if (!ALLOWED_TAGS.contains(name)) {
            return "";
        }
        if (closing) {
            return VOID_TAGS.contains(name) ? "" : "</" + name + ">";
        }
        StringBuilder tag = new StringBuilder("<").append(name);
        Matcher attrMatcher = ATTR.matcher(nameMatcher.group(3));
        while (attrMatcher.find()) {
            String attrName = attrMatcher.group(1).toLowerCase(Locale.ROOT);
            if (!ALLOWED_ATTRS.contains(attrName)) {
                continue;
            }
            String attrValue = attrMatcher.group(3) != null ? attrMatcher.group(3)
                    : attrMatcher.group(4) != null ? attrMatcher.group(4) : attrMatcher.group(5);
            if (attrValue == null) {
                continue;
            }
            String cleaned = sanitizeAttrValue(attrValue.trim());
            if (cleaned == null) {
                continue;
            }
            tag.append(' ').append(attrName).append("=\"").append(cleaned).append('"');
        }
        tag.append('>');
        return tag.toString();
    }

    /** 返回 null 表示该属性应被丢弃。 */
    private static String sanitizeAttrValue(String value) {
        if (value.isEmpty()) {
            return "";
        }
        if (UNSAFE_SCHEME.matcher(value).find()) {
            return null;
        }
        // 去掉控制字符与换行（属性注入风险）
        String cleaned = value.replaceAll("[\\x00-\\x1f\\x7f]", "");
        return cleaned.replace("\"", "&quot;");
    }
}
