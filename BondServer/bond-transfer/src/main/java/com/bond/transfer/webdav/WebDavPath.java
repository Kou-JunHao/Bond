package com.bond.transfer.webdav;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WebDAV 请求路径解析与 URL 编解码。
 *
 * <p>挂载根约定：</p>
 * <ul>
 *     <li>{@code /dav/} —— 当前用户的个人空间根（WebDAV 友好路径，客户端无需知道 workspaceId）</li>
 *     <li>{@code /dav/workspaces/{workspaceId}/} —— 团队工作区挂载点（只读挂载或独立账号）</li>
 * </ul>
 *
 * <p>安全：拒绝 {@code ..}、编码后的 {@code %2e%2e}、反斜杠、NUL 与空段，
 * 防止路径穿越到个人空间/其它工作区。</p>
 */
public final class WebDavPath {

    public static final String PREFIX = "/dav";

    private WebDavPath() {
    }

    /**
     * 解析后的请求路径。
     *
     * @param workspaceId 挂载到的团队工作区 id；null 表示个人空间
     * @param segments    挂载点之后的路径段（已解码，不含空段）
     */
    public record Parsed(Long workspaceId, List<String> segments) {

        public boolean isRoot() {
            return segments.isEmpty();
        }

        public String name() {
            return segments.isEmpty() ? "" : segments.get(segments.size() - 1);
        }

        /** 所在目录（"/" 或 "/a/b"）。 */
        public String parentPath() {
            if (segments.size() <= 1) {
                return "/";
            }
            return "/" + String.join("/", segments.subList(0, segments.size() - 1));
        }

        public String relativePath() {
            return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
        }
    }

    /**
     * 解析带 {@code /dav} 前缀的请求路径。
     *
     * @throws IllegalArgumentException 路径非法（穿越/空地址/编码错误）
     */
    public static Parsed parse(String rawPath) {
        String path = rawPath == null ? "" : rawPath;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        if (!path.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not a /dav path");
        }
        String rest = path.substring(PREFIX.length());
        if (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        List<String> parts = new ArrayList<>();
        if (!rest.isEmpty()) {
            String[] rawSegments = rest.split("/");
            for (String rawSegment : rawSegments) {
                if (rawSegment.isEmpty()) {
                    continue;
                }
                String segment = decode(rawSegment);
                validateSegment(segment);
                parts.add(segment);
            }
        }

        Long workspaceId = null;
        List<String> segments;
        if (parts.size() >= 2 && "workspaces".equalsIgnoreCase(parts.get(0)) && isNumber(parts.get(1))) {
            workspaceId = Long.parseLong(parts.get(1));
            segments = List.copyOf(parts.subList(2, parts.size()));
        } else {
            segments = List.copyOf(parts);
        }
        return new Parsed(workspaceId, segments);
    }

    /**
     * 解析 MOVE/COPY 的 {@code Destination} 头（可以是绝对 URL 或 {@code /dav/...} 路径）。
     *
     * @return null 表示目标不在 {@code /dav} 下（跨挂载/非法目标）
     */
    public static Parsed parseDestination(String destinationHeader) {
        if (destinationHeader == null || destinationHeader.isBlank()) {
            return null;
        }
        String destination = destinationHeader.trim();
        String path = destination;
        if (destination.regionMatches(true, 0, "http://", 0, 7)
                || destination.regionMatches(true, 0, "https://", 0, 8)) {
            try {
                URI uri = URI.create(destination);
                path = uri.getRawPath();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (path == null || !path.startsWith(PREFIX)) {
            return null;
        }
        try {
            return parse(path);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 单个路径段的 URL 编码（空格编码为 %20）。 */
    public static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 拼接相对路径。 */
    public static String joinPath(String parentPath, String name) {
        String parent = parentPath == null || parentPath.isBlank() ? "/" : parentPath;
        if (!parent.startsWith("/")) {
            parent = "/" + parent;
        }
        if (parent.endsWith("/")) {
            parent = parent.substring(0, parent.length() - 1);
        }
        return parent + "/" + name;
    }

    /** 工作区挂载根 href，例如 {@code /dav/workspaces/12/}；个人空间为 {@code /dav/}。 */
    public static String mountHref(Long workspaceId) {
        return workspaceId == null ? PREFIX + "/" : PREFIX + "/workspaces/" + workspaceId + "/";
    }

    private static String decode(String segment) {
        try {
            // 先把 '+' 保护起来：URLDecoder 会把 '+' 解成空格，而路径里的 '+' 是字面量
            return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid path encoding", e);
        }
    }

    private static void validateSegment(String segment) {
        if (segment.isEmpty()) {
            throw new IllegalArgumentException("empty path segment");
        }
        if (".".equals(segment) || "..".equals(segment)) {
            throw new IllegalArgumentException("path traversal");
        }
        if (segment.indexOf('/') >= 0
                || segment.indexOf('\\') >= 0
                || segment.indexOf('\0') >= 0
                || segment.codePoints().anyMatch(ch -> ch < 0x20)) {
            throw new IllegalArgumentException("illegal character in path");
        }
    }

    private static boolean isNumber(String value) {
        if (value == null || value.isEmpty() || value.length() > 18) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 供测试/日志使用的大小写无关字符串。 */
    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
