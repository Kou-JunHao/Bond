package com.bond.transfer.preview;

/**
 * 条件请求（RFC 9110 §13.1.2）中 {@code If-None-Match} 与实体标签的比对。
 *
 * <p>为什么需要它：服务层产出的是<b>不带引号</b>的裸标签（如 {@code thumb-4e89cca7}），
 * 而 HTTP 报文里的实体标签按规范必须用双引号包裹，{@code ResponseEntity#eTag} 会自动加引号。
 * 于是客户端回传的是 {@code "thumb-4e89cca7"}，直接与裸标签 {@code equals} 永远不相等——
 * 条件请求形同虚设（每次都回 200 全量图片，白白浪费带宽）。
 * 这里按规范做归一化：去掉可选的弱校验前缀 {@code W/}、去掉首尾双引号，并支持逗号分隔的标签列表与 {@code *}。</p>
 */
public final class EtagMatch {

    private EtagMatch() {
    }

    /**
     * @param etag        服务端产出的裸实体标签（不含引号），不允许为 null
     * @param ifNoneMatch 请求头原始值，可为 null（无 If-None-Match 头）
     * @return 命中则 true（调用方回 304）
     */
    public static boolean matches(String etag, String ifNoneMatch) {
        if (etag == null || ifNoneMatch == null) {
            return false;
        }
        String header = ifNoneMatch.trim();
        if (header.isEmpty()) {
            return false;
        }
        if ("*".equals(header)) {
            return true;
        }
        for (String candidate : header.split(",")) {
            if (normalize(candidate).equals(etag)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String raw) {
        String tag = raw.trim();
        if (tag.startsWith("W/") || tag.startsWith("w/")) {
            tag = tag.substring(2).trim();
        }
        if (tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"")) {
            tag = tag.substring(1, tag.length() - 1);
        }
        return tag;
    }
}
