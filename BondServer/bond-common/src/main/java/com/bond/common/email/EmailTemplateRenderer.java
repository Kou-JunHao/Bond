package com.bond.common.email;

import com.bond.common.entity.EmailTemplate;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邮件模板渲染器（task-63）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>只做 {@code {{name}}} 纯文本替换，变量白名单（{@link #ALLOWED_VARIABLES}）；</li>
 *   <li>HTML 中变量值做 HTML 转义（防模板注入），text 中原文替换；</li>
 *   <li><b>模板引用了但调用方未提供（或值为空白）的变量，渲染为 {@code —}</b>（em dash，task-129：
 *       不再留 {@code {{var}}}，也不留空白，让用户能看出「这里本该有内容」）；</li>
 *   <li>未知变量（不在白名单）置空并 warn，绝不执行表达式/脚本；</li>
 *   <li>{@code actionUrl} 为空时整行省略（HTML 与 text 都处理）；</li>
 *   <li>{@code body_text} 缺失时由 HTML 粗转纯文本。</li>
 * </ul>
 */
@Slf4j
public final class EmailTemplateRenderer {

    public static final Set<String> ALLOWED_VARIABLES = Set.of(
            "code", "username", "email", "expiresMinutes", "appName",
            "supportEmail", "actionUrl", "year", "action", "reason");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");
    private static final Pattern ACTION_URL_LINE =
            Pattern.compile("(?m)^.*\\{\\{\\s*actionUrl\\s*}}.*(?:\\R|$)");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MULTI_BLANK_LINE = Pattern.compile("\\n{3,}");

    private EmailTemplateRenderer() {
    }

    public record RenderedEmail(String subject, String html, String text) {
    }

    public static RenderedEmail render(EmailTemplate template, Map<String, String> variables) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (variables != null) {
            vars.putAll(variables);
        }
        String html = template.getBodyHtml() == null ? "" : template.getBodyHtml();
        String text = template.getBodyText() == null ? "" : template.getBodyText();
        if (isBlank(vars.get("actionUrl"))) {
            html = ACTION_URL_LINE.matcher(html).replaceAll("");
            text = ACTION_URL_LINE.matcher(text).replaceAll("");
        }
        String subject = substitute(template.getSubject(), vars, false);
        String renderedHtml = substitute(html, vars, true);
        String renderedText = text.isBlank()
                ? htmlToText(renderedHtml)
                : substitute(text, vars, false);
        return new RenderedEmail(subject, renderedHtml, renderedText);
    }

    static String substitute(String input, Map<String, String> vars, boolean htmlEscape) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(input);
        StringBuilder out = new StringBuilder(input.length() + 64);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (ALLOWED_VARIABLES.contains(name)) {
                String value = vars.get(name);
                // task-129：缺变量渲染成 “—”（em dash），让收件人看到“这里本该有内容”，
                // 而不是留 {{var}}（像模板没渲染）或整段空白（像内容丢失）。
                String replacement;
                if (value == null || value.isBlank()) {
                    replacement = "—";
                } else {
                    replacement = htmlEscape ? escapeHtml(value) : value;
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            } else {
                log.warn("email template contains unknown variable: {{{}}}", name);
                // task-76：无法识别的占位符置空，避免把 {{xxx}} 原样发给用户
                matcher.appendReplacement(out, "");
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** 极简 HTML → 纯文本（不引第三方库；仅用于 body_text 缺失时的兜底）。 */
    public static String htmlToText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|h[1-6]|tr|li)>", "\n")
                .replaceAll("(?i)<li[^>]*>", "- ")
                .replaceAll("(?i)</td>", " | ");
        text = TAG.matcher(text).replaceAll("");
        text = decodeEntities(text);
        text = MULTI_BLANK_LINE.matcher(text).replaceAll("\n\n");
        return text.replaceAll("[ \\t]+\\n", "\n").trim();
    }

    private static String decodeEntities(String value) {
        return value.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 模板里引用了、但本次渲染没有提供（或值为空白）的变量名（升序、去重）。
     *
     * <p>task-76：后台预览 / 发信前可用它提示「缺哪些变量」；这些占位符渲染时会被置空。</p>
     */
    public static java.util.List<String> missingVariables(EmailTemplate template, Map<String, String> variables) {
        if (template == null) {
            return java.util.List.of();
        }
        java.util.Set<String> missing = new java.util.TreeSet<>();
        collectMissing(template.getSubject(), variables, missing);
        collectMissing(template.getBodyHtml(), variables, missing);
        collectMissing(template.getBodyText(), variables, missing);
        return java.util.List.copyOf(missing);
    }

    private static void collectMissing(String input, Map<String, String> vars, java.util.Set<String> missing) {
        if (input == null || input.isEmpty()) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(input);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!ALLOWED_VARIABLES.contains(name)) {
                continue;
            }
            String value = vars == null ? null : vars.get(name);
            if (isBlank(value)) {
                missing.add(name);
            }
        }
    }

    /**
     * 从若干段文本中收集「引用了的合法变量」（升序去重），用于自定义模板自动生成 variables 提示。
     *
     * <p>未知占位符不会进入结果（渲染时会被置空并 warn）。</p>
     */
    public static java.util.List<String> referencedVariables(String... texts) {
        if (texts == null || texts.length == 0) {
            return java.util.List.of();
        }
        java.util.Set<String> found = new java.util.TreeSet<>();
        for (String text : texts) {
            if (text == null || text.isEmpty()) {
                continue;
            }
            Matcher matcher = PLACEHOLDER.matcher(text);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (ALLOWED_VARIABLES.contains(name)) {
                    found.add(name);
                }
            }
        }
        return java.util.List.copyOf(found);
    }

    /** 变量名集合的 JSON 数组（后台展示用）。 */
    public static String allVariablesAsJson() {
        return ALLOWED_VARIABLES.stream().sorted()
                .map(v -> "\"" + v.toLowerCase(Locale.ROOT) + "\"")
                .reduce((a, b) -> a + "," + b)
                .map(s -> "[" + s + "]")
                .orElse("[]");
    }

    /**
     * 渲染结果中残留的 `{{` 个数（task-80 投递日志的 placeholders_left）。
     *
     * <p>正常为 0；非 0 说明模板里有渲染器不识别的占位符（如拼写错误的
     * {@code {{userNmae}}}），渲染时会被置空，但仍应在投递日志中暴露出来。</p>
     */
    public static int countPlaceholders(RenderedEmail email) {
        if (email == null) {
            return 0;
        }
        return countPlaceholders(email.subject())
                + countPlaceholders(email.html())
                + countPlaceholders(email.text());
    }

    /** 单段文本里 `{{` 的出现次数（不要求成对，残留即计入）。 */
    public static int countPlaceholders(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = text.indexOf("{{");
        while (index >= 0) {
            count++;
            index = text.indexOf("{{", index + 2);
        }
        return count;
    }
}
