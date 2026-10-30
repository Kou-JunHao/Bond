package com.bond.transfer.preview;

import com.bond.common.security.GatewaySignature;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * 预览短期令牌（放在 URL query，供 &lt;img&gt;/媒体播放器使用，不需要 Authorization 头）。
 *
 * <p>默认 TTL 10 分钟；绑定 fileId + userId + scope（content/thumb）。HMAC-SHA256 签名。
 * 密钥优先 `PREVIEW_TOKEN_SECRET`，否则复用必须存在的 `INTERNAL_GATEWAY_SECRET`（不引入不安全默认值）。</p>
 */
@Service
public class PreviewTokenService {

    public static final String SCOPE_CONTENT = "content";
    public static final String SCOPE_THUMB = "thumb";
    /** PDF 分页预览：与 thumbnail 分开授权，避免旧缩略图令牌被拿来翻页。 */
    public static final String SCOPE_PREVIEW = "preview";
    /** Office→PDF 转换预览（task-59）：独立 scope，避免内容/缩略图令牌被拿来触发转换。 */
    public static final String SCOPE_OFFICE = "office";
    /**
     * 聊天文件跨账号预览（task-84 方案 C）：令牌根绑 messageId、不含 fileId。
     *
     * <p>每次取流都会按「消息未删 + 请求者在会话参与者内 + 文件仍存在」三步复校验，
     * 因此令牌泄露窗口只有 10 分钟，且撤销语义干净（删消息/移除成员/删文件后立刻 404）。</p>
     */
    public static final String SCOPE_CHAT_META = "chat-meta";
    public static final String SCOPE_CHAT_CONTENT = "chat-content";
    public static final String SCOPE_CHAT_THUMB = "chat-thumb";
    public static final String SCOPE_CHAT_OFFICE = "chat-office";
    public static final long TTL_SECONDS = 600L;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String secret;
    private final long ttlSeconds;

    public PreviewTokenService() {
        this(resolveSecret(), TTL_SECONDS);
    }

    /** 测试/内部构造（密钥需 >=32 字符）。 */
    PreviewTokenService(String secret, long ttlSeconds) {
        this.secret = GatewaySignature.requireSecret(() -> secret);
        this.ttlSeconds = ttlSeconds;
    }

    private static String resolveSecret() {
        String configured = System.getenv("PREVIEW_TOKEN_SECRET");
        if (configured == null || configured.isBlank()) {
            return GatewaySignature.requireSecretFromEnv();
        }
        return GatewaySignature.requireSecret(() -> configured);
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public String issue(Long fileId, Long userId, String scope) {
        long expiresAt = System.currentTimeMillis() + ttlSeconds * 1000L;
        String payload = fileId + ":" + userId + ":" + scope + ":" + expiresAt;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + hmacHex(encoded);
    }

    /** 校验并解析令牌；任何不匹配（含篡改/过期/scope/fileId 不符）返回 empty。 */
    public Optional<Ticket> resolve(String token, Long expectedFileId, String expectedScope) {
        if (token == null || token.isBlank() || token.indexOf('.') < 0) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        String encoded = token.substring(0, dot);
        String signature = token.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
        byte[] expected = hmacHex(encoded).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = payload.split(":", -1);
            if (parts.length != 4) {
                return Optional.empty();
            }
            Long fileId = Long.valueOf(parts[0]);
            Long userId = Long.valueOf(parts[1]);
            String scope = parts[2];
            long expiresAt = Long.parseLong(parts[3]);
            if (expectedFileId != null && !expectedFileId.equals(fileId)) {
                return Optional.empty();
            }
            if (expectedScope != null && !expectedScope.equals(scope)) {
                return Optional.empty();
            }
            if (System.currentTimeMillis() > expiresAt) {
                return Optional.empty();
            }
            return Optional.of(new Ticket(fileId, userId, scope, expiresAt));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public record Ticket(Long fileId, Long userId, String scope, long expiresAtMillis) {
    }

    /**
     * 签发聊天文件令牌：payload 为 {@code messageId:userId:scope:expires}。
     *
     * <p>与文件令牌共用签名/格式，但用 {@code chat-*} scope 区分；两种令牌互相不能串用。</p>
     */
    public String issueForMessage(Long messageId, Long userId, String scope) {
        long expiresAt = System.currentTimeMillis() + ttlSeconds * 1000L;
        String payload = messageId + ":" + userId + ":" + scope + ":" + expiresAt;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + hmacHex(encoded);
    }

    /**
     * 校验聊天文件令牌；token 中的第一个字段是 messageId（不是 fileId）。
     *
     * <p>调用方必须传 {@code chat-*} scope，因此文件预览令牌无法在此通过校验。</p>
     */
    public Optional<MessageTicket> resolveForMessage(String token, String expectedScope) {
        if (token == null || token.isBlank() || token.indexOf('.') < 0) {
            return Optional.empty();
        }
        // 硬保证：只接受 chat-* scope，因此文件预览令牌（content/thumb/...）在此必被拒。
        if (expectedScope == null || !expectedScope.startsWith("chat-")) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        String encoded = token.substring(0, dot);
        String signature = token.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
        byte[] expected = hmacHex(encoded).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = payload.split(":", -1);
            if (parts.length != 4) {
                return Optional.empty();
            }
            Long messageId = Long.valueOf(parts[0]);
            Long userId = Long.valueOf(parts[1]);
            String scope = parts[2];
            long expiresAt = Long.parseLong(parts[3]);
            if (expectedScope != null && !expectedScope.equals(scope)) {
                return Optional.empty();
            }
            if (System.currentTimeMillis() > expiresAt) {
                return Optional.empty();
            }
            return Optional.of(new MessageTicket(messageId, userId, scope, expiresAt));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 聊天文件令牌解析结果：{@code messageId} 是唯一的访问根。 */
    public record MessageTicket(Long messageId, Long userId, String scope, long expiresAtMillis) {
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign preview token", e);
        }
    }
}
