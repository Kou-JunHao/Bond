package com.bond.common.security;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 网关白名单（精确匹配）。网关 AuthFilter 与下游 {@link InternalAuthFilter} 共用同一份规则，
 * 避免两边白名单漂移，也避免 {@code startsWith} 被 {@code /api/inviteX} 之类的路径绕过。
 *
 * <p>规则：HTTP 方法 + 精确路径，或带结尾 {@code /} 的精确前缀（仅用于子资源 ID 路径）。</p>
 */
public final class PublicEndpoints {

    /** GET 精确路径。 */
    private static final Set<String> PUBLIC_GET_EXACT = Set.of(
            "/api/auth/captcha",
            // 客户端登录前会先探测是否需要验证码：GET /api/auth/captcha/check
            // 遗漏它会导致登录页拿不到验证码（公网 401，无法登录）
            "/api/auth/captcha/check",
            // Web 面 favicon（task-87）：静态资源、匿名可访问；网关不白名单会 401
            "/favicon.svg"
    );

    /** GET 精确前缀（必须以 / 结尾；用于带路径参数的公开资源）。 */
    private static final Set<String> PUBLIC_GET_PREFIXES = Set.of(
            "/api/invite/",
            "/api/transfer/avatar/"
    );

    /** POST 精确路径。 */
    private static final Set<String> PUBLIC_POST_EXACT = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            // task-57：邮箱验证码（注册/找回密码）与密码重置必须匿名可达；其余改邮箱/改密码仍走登录鉴权
            "/api/auth/email/code",
            "/api/auth/password/reset"
    );

    /**
     * 预览/缩略图：令牌在 query 中（浏览器 &lt;img&gt;/媒体播放器无法带 Authorization），
     * 应用层用 HMAC 短期令牌校验；这里只放行「精确形状」的 GET。
     *
     * <p>分享链接 {@code /s/{token}...}：token 为 43 字符 base64url（32 字节 SecureRandom），
     * 匿名访问、不可枚举；应用层完成状态/有效期/提取码/次数校验。</p>
     */
    private static final List<Pattern> PUBLIC_GET_PATTERNS = List.of(
            // office-pdf：办公文档转换后的 PDF 预览（task-59）。与 content/thumbnail 同口径——
            // 令牌在 query 里由应用层 HMAC 校验，漏加会导致客户端拿到 401 而不是 PDF。
            Pattern.compile("^/api/files/[0-9]+/(content|thumbnail|preview|office-pdf)$"),
            Pattern.compile("^/api/workspaces/[0-9]+/files/[0-9]+/(content|thumbnail|preview|office-pdf)$"),
            // 聊天文件跨账号访问（task-84 方案 C）：令牌在 query 中、根绑 messageId；
            // 应用层每次取流都复校验「消息未删 / 请求者在会话内 / 文件仍存在」，失败统一 404。
            // 注意：签发令牌的 POST /api/chat/files/{messageId}/tokens 不在白名单内，仍需登录。
            Pattern.compile("^/api/chat/files/(meta|content|thumbnail|office-pdf)$"),
            // 用户头像（task-152）：<img> 无法带 Authorization，匿名可读；应用层用 ?v= 版本校验
            // （v = avatar_updated_at 的 epoch 秒，不匹配返回 404），路径形状精确、不可枚举他人新图。
            Pattern.compile("^/api/users/[0-9]+/avatar$"),
            // 分享落地页与下载（匿名；范围下载由应用层 Range 校验）
            Pattern.compile("^/s/[A-Za-z0-9_-]{43}$"),
            Pattern.compile("^/s/[A-Za-z0-9_-]{43}/download$"),
            // 工作区邀请落地页（task-159）：匿名 HTML 页面，浏览器直接打开。
            // 邀请 token 由 WorkspaceInvitationService 生成：历史/当前为 32 位 UUID-hex，
            // 同时兼容 43 位 base64url 形状（与分享 token 同口径），两者都精确匹配。
            Pattern.compile("^/i/[A-Za-z0-9_-]{32}$"),
            Pattern.compile("^/i/[A-Za-z0-9_-]{43}$")
    );

    /** POST 公开路径（分享提取码校验；精确形状，避免把任意 POST /s/** 放进来）。 */
    private static final List<Pattern> PUBLIC_POST_PATTERNS = List.of(
            Pattern.compile("^/s/[A-Za-z0-9_-]{43}/verify$")
    );

    private PublicEndpoints() {
    }

    public static boolean isPublic(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        // 拒绝路径穿越/重复斜杠/编码点号，避免白名单前缀被 ../ 绕过
        String lower = path.toLowerCase(Locale.ROOT);
        if (path.contains("..") || path.contains("//") || lower.contains("%2e") || lower.contains("%2f")) {
            return false;
        }
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        if ("POST".equals(normalizedMethod)) {
            if (PUBLIC_POST_EXACT.contains(path)) {
                return true;
            }
            for (Pattern pattern : PUBLIC_POST_PATTERNS) {
                if (pattern.matcher(path).matches()) {
                    return true;
                }
            }
            return false;
        }
        if ("GET".equals(normalizedMethod)) {
            if (PUBLIC_GET_EXACT.contains(path)) {
                return true;
            }
            for (Pattern pattern : PUBLIC_GET_PATTERNS) {
                if (pattern.matcher(path).matches()) {
                    return true;
                }
            }
            for (String prefix : PUBLIC_GET_PREFIXES) {
                // 必须真的多出子路径，避免 "/api/transfer/avatar/" 空 id 或 "avatarX" 绕过
                if (path.startsWith(prefix) && path.length() > prefix.length()) {
                    return true;
                }
            }
        }
        return false;
    }
}
