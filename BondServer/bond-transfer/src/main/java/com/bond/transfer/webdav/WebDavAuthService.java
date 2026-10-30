package com.bond.transfer.webdav;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.entity.User;
import com.bond.common.entity.WebDavPassword;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WebDavPasswordMapper;
import com.bond.transfer.service.ConfigService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * WebDAV Basic 认证。
 *
 * <p>语义固定：</p>
 * <ul>
 *     <li>{@code webdav.enabled=false}（默认）时整个 {@code /dav/**} 返回 404，不暴露挂载点；</li>
 *     <li>请求带 {@code X-Forwarded-Proto} 且不是 https 时返回 403（Basic 凭据不能走明文）；</li>
 *     <li>Basic 用户名 = Bond 用户名；密码先匹配该用户的<b>应用专用密码</b>（V9，可单独吊销），
 *         匹配不到再回退到登录密码（迁移期兜底，命中时会 warn 一次便于以后下线）；</li>
 *     <li>校验结果在进程内缓存 10 分钟，避免 Explorer 每个请求都跑 BCrypt；缓存条目记录
 *         {@code passwordId}，专用密码被吊销时由 {@link #invalidateUser(Long)} 立即清掉；</li>
 *     <li>同一 IP 连续 10 次失败后 10 分钟内返回 429（防爆破）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebDavAuthService {

    static final String CFG_ENABLED = "webdav.enabled";
    /** 认证缓存窗口：60 秒。吊销不会等 TTL——{@link #invalidateUser(Long)} 会主动清条目。 */
    static final long CACHE_TTL_MILLIS = 60 * 1000L;
    static final int CACHE_MAX_ENTRIES = 10_000;
    static final int MAX_FAILURES = 10;
    static final long WINDOW_MILLIS = 10 * 60 * 1000L;

    private final ConfigService configService;
    private final UserMapper userMapper;
    private final WebDavPasswordMapper webDavPasswordMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final Map<String, CachedLogin> cache = new ConcurrentHashMap<>();
    private final Map<String, FailureState> failures = new ConcurrentHashMap<>();
    /** 每个用户只 warn 一次"在用登录密码挂载"，避免 Explorer 高频请求刷爆日志。 */
    private final Set<Long> warnedLoginPasswordUsers = ConcurrentHashMap.newKeySet();

    private record CachedLogin(Long userId, Long passwordId, long expiresAtMillis) {
    }

    private record FailureState(int count, long windowStartMillis) {
    }

    public boolean isEnabled() {
        return configService.getBoolean(CFG_ENABLED, false);
    }

    /**
     * 确认 WebDAV 可用（开关打开 + 若走代理则必须是 HTTPS）。
     *
     * <p>X-Forwarded-Proto 可能是逗号分隔链（nginx 设 {@code https}，Spring Cloud Gateway
     * 转发时可能追加自己的 {@code http}，形成 {@code https,http}）。只要链中存在 {@code https}
     * 就视为安全；整条链都是明文才拒绝。</p>
     *
     * @throws WebDavException 404（关闭）/ 403（明文 HTTP）
     */
    public void ensureAvailable(HttpServletRequest request) {
        if (!isEnabled()) {
            throw new WebDavException(404, "WebDAV is disabled");
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null && !forwardedProto.isBlank()) {
            boolean secure = false;
            for (String part : forwardedProto.split(",")) {
                if ("https".equalsIgnoreCase(part.trim())) {
                    secure = true;
                    break;
                }
            }
            if (!secure) {
                throw new WebDavException(403, "WebDAV requires HTTPS");
            }
        }
    }

    /**
     * 校验 Basic 认证并返回用户 id。
     *
     * @throws WebDavException 400（头格式非法）/ 401（凭据错误）/ 429（爆破限流）
     */
    public Long authenticate(HttpServletRequest request) {
        String clientIp = clientIp(request);
        ensureNotLimited(clientIp);

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            throw unauthorized();
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            recordFailure(clientIp);
            throw unauthorized();
        }
        int colon = decoded.indexOf(':');
        if (colon <= 0) {
            recordFailure(clientIp);
            throw unauthorized();
        }
        String username = normalizeUsername(decoded.substring(0, colon));
        String password = decoded.substring(colon + 1);
        if (username.isEmpty() || password.isEmpty()) {
            recordFailure(clientIp);
            throw unauthorized();
        }

        String cacheKey = username.toLowerCase(Locale.ROOT) + ":" + sha256Hex(password);
        CachedLogin cached = cache.get(cacheKey);
        if (cached != null) {
            if (cached.expiresAtMillis() > System.currentTimeMillis()) {
                return cached.userId();
            }
            cache.remove(cacheKey);
        }

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .apply("lower(username) = lower({0})", username));
        if (user == null) {
            recordFailure(clientIp);
            log.info("WebDAV 认证失败: ip={}, username={}", clientIp, username);
            throw unauthorized();
        }

        Long passwordId = matchWebDavPassword(user.getId(), password);
        if (passwordId != null) {
            touchLastUsed(passwordId);
        } else {
            // 迁移期兜底：仍允许用账号登录密码挂载（以后下线），但每个用户只 warn 一次。
            if (user.getPasswordHash() == null
                    || !passwordEncoder.matches(password, user.getPasswordHash())) {
                recordFailure(clientIp);
                log.info("WebDAV 认证失败: ip={}, username={}", clientIp, username);
                throw unauthorized();
            }
            if (warnedLoginPasswordUsers.add(user.getId())) {
                log.warn("WebDAV 正在使用账号登录密码挂载（user_id={}）；建议改用应用专用密码，"
                        + "登录密码兜底将在后续版本下线", user.getId());
            }
        }

        failures.remove(clientIp);
        if (cache.size() >= CACHE_MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(cacheKey, new CachedLogin(user.getId(), passwordId,
                System.currentTimeMillis() + CACHE_TTL_MILLIS));
        return user.getId();
    }

    /**
     * 立即清掉某个用户的认证缓存（专用密码生成/吊销后调用）。
     *
     * <p>缓存 key 是「用户名 + 密码 SHA-256」，无法从 passwordId 反查；按 userId 整批清掉
     * 最简单也最安全：吊销后下一次请求必然重新校验，不会出现 10 分钟窗口。</p>
     */
    public void invalidateUser(Long userId) {
        if (userId == null) {
            return;
        }
        cache.entrySet().removeIf(entry -> userId.equals(entry.getValue().userId()));
    }

    /** 按 userId 找未吊销的专用密码，逐个 bcrypt 匹配；命中返回 passwordId。 */
    private Long matchWebDavPassword(Long userId, String password) {
        List<WebDavPassword> passwords = webDavPasswordMapper.selectList(
                new LambdaQueryWrapper<WebDavPassword>()
                        .eq(WebDavPassword::getUserId, userId)
                        .isNull(WebDavPassword::getRevokedAt));
        if (passwords == null || passwords.isEmpty()) {
            return null;
        }
        for (WebDavPassword row : passwords) {
            if (row.getPasswordHash() != null && passwordEncoder.matches(password, row.getPasswordHash())) {
                return row.getId();
            }
        }
        return null;
    }

    /** 记录专用密码最近使用时间；失败只 debug，不影响认证。 */
    private void touchLastUsed(Long passwordId) {
        try {
            WebDavPassword update = new WebDavPassword();
            update.setId(passwordId);
            update.setLastUsedAt(OffsetDateTime.now());
            webDavPasswordMapper.updateById(update);
        } catch (RuntimeException e) {
            log.debug("更新 WebDAV 专用密码 last_used_at 失败: {}", e.getMessage());
        }
    }

    private WebDavException unauthorized() {
        return new WebDavException(401, "Unauthorized")
                .header("WWW-Authenticate", "Basic realm=\"Bond\", charset=\"UTF-8\"");
    }

    private void ensureNotLimited(String clientIp) {
        FailureState state = failures.get(clientIp);
        if (state == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - state.windowStartMillis() > WINDOW_MILLIS) {
            failures.remove(clientIp);
            return;
        }
        if (state.count() >= MAX_FAILURES) {
            long retryAfterSeconds =
                    Math.max(1L, (WINDOW_MILLIS - (now - state.windowStartMillis())) / 1000L);
            throw new WebDavException(429, "Too many failed attempts")
                    .header("Retry-After", String.valueOf(retryAfterSeconds));
        }
    }

    private void recordFailure(String clientIp) {
        long now = System.currentTimeMillis();
        failures.compute(clientIp, (key, old) -> {
            if (old == null || now - old.windowStartMillis() > WINDOW_MILLIS) {
                return new FailureState(1, now);
            }
            return new FailureState(old.count() + 1, old.windowStartMillis());
        });
    }

    private static String normalizeUsername(String raw) {
        String username = raw == null ? "" : raw.trim();
        // Windows 资源管理器可能发送带域前缀的用户名（如 DOMAIN 前缀或 "." 前缀），
        // 只取最后一个路径分隔符之后的用户名部分（Java 注释里不能出现反斜杠+u 的序列，否则会被当成 Unicode 转义）。
        int slash = username.lastIndexOf('\\');
        if (slash >= 0 && slash < username.length() - 1) {
            username = username.substring(slash + 1).trim();
        }
        if (username.length() > 64) {
            username = username.substring(0, 64);
        }
        return username;
    }

    private static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
