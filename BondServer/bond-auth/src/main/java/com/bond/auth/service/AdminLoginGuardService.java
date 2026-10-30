package com.bond.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.auth.mapper.AdminLoginBlockMapper;
import com.bond.auth.mapper.AdminLoginLogMapper;
import com.bond.auth.mapper.UserMapper;
import com.bond.common.entity.AdminLoginBlock;
import com.bond.common.entity.AdminLoginLog;
import com.bond.common.entity.User;
import com.bond.common.exception.RateLimitedException;
import com.bond.common.security.ClientIpResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 管理端登录 fail2ban 式防护（task-144）。
 *
 * <p>只作用于“管理后台登录”尝试：请求头 {@code X-Client-App: bond-admin}（admin-web 统一带），
 * 或目标账号 {@code is_admin=true}（兜底）。普通用户登录既不记录也不封禁。</p>
 *
 * <p>阈值与封禁时长走 {@code system_config}（{@code admin_guard.*}），失败计数基于
 * {@code admin_login_logs} 中 outcome ∈ {bad_credentials, user_disabled} 的记录；
 * captcha 阶段的 {@code captcha_required} 只记录不计数（避免验证码输错就把正常管理员封掉）。</p>
 *
 * <p>白名单：回环地址 + {@code admin_guard.ip_allowlist}（逗号分隔）永不被封。
 * IP 必须来自 {@link com.bond.common.security.ClientIpResolver}（网关 ClientIpFilter 覆盖后的可信值）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLoginGuardService {

    /** 管理端客户端标识请求头（admin-web 统一携带）。 */
    public static final String CLIENT_APP_HEADER = "X-Client-App";
    /** 管理端客户端标识值。 */
    public static final String CLIENT_APP_ADMIN = "bond-admin";

    public static final String CFG_ENABLED = "admin_guard.enabled";
    public static final String CFG_FAIL_WINDOW_SECONDS = "admin_guard.fail_window_seconds";
    public static final String CFG_FAIL_THRESHOLD_IP = "admin_guard.fail_threshold_ip";
    public static final String CFG_FAIL_THRESHOLD_USER = "admin_guard.fail_threshold_user";
    public static final String CFG_BAN_SECONDS = "admin_guard.ban_seconds";
    public static final String CFG_IP_ALLOWLIST = "admin_guard.ip_allowlist";

    public static final int DEFAULT_FAIL_WINDOW_SECONDS = 600;
    public static final int DEFAULT_FAIL_THRESHOLD_IP = 5;
    public static final int DEFAULT_FAIL_THRESHOLD_USER = 10;
    public static final int DEFAULT_BAN_SECONDS = 900;
    public static final long MAX_BAN_SECONDS = 86_400L;

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    private static final int MAX_REASON = 256;
    private static final int MAX_USER_AGENT = 512;
    private static final long INTERNAL_WARN_INTERVAL_MS = 60_000L;

    /** IP 链路退化（解析成内部地址）时的 WARN 采样时间戳，避免刷屏（task-146）。 */
    private final AtomicLong lastInternalIpWarn = new AtomicLong(0L);
    private static final int MAX_IP = 64;

    private final ConfigService configService;
    private final UserMapper userMapper;
    private final AdminLoginLogMapper logMapper;
    private final AdminLoginBlockMapper blockMapper;
    private final AdminLoginAuditWriter writer;

    // ------------------------------------------------------------------ 判定

    public boolean isEnabled() {
        return configService.getBoolean(CFG_ENABLED, true);
    }

    /** 请求头是否表明“admin-web 管理端”。 */
    public boolean isAdminClient(String clientAppHeader) {
        return clientAppHeader != null && CLIENT_APP_ADMIN.equalsIgnoreCase(clientAppHeader.trim());
    }

    /** 按用户名查用户（失败也不抛：审计不能影响登录）。 */
    public User findUser(String username) {
        String name = safeUsername(username);
        if (name.isEmpty()) {
            return null;
        }
        try {
            return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, name));
        } catch (RuntimeException e) {
            log.warn("admin login guard: user lookup failed for username={}: {}", maskUsername(name), e.toString());
            return null;
        }
    }

    /** 是否属于“管理端登录”：admin 客户端头优先，其次目标账号 is_admin=true。 */
    public boolean isAdminAttempt(boolean adminClient, User user) {
        if (adminClient) {
            return true;
        }
        return user != null && Boolean.TRUE.equals(user.getIsAdmin());
    }

    // ------------------------------------------------------------------ 登录前拦截

    /**
     * 登录前检查 IP/账号是否处于封禁期；命中则记录 blocked 日志并抛 429（带 Retry-After）。
     */
    public void enforceBlockIfActive(String ip, String username) {
        refreshPolicy();
        if (!isEnabled()) {
            return;
        }
        try {
            OffsetDateTime now = OffsetDateTime.now();
            BlockState state = findActiveBlock(ip, safeUsername(username), now);
            if (state == null) {
                return;
            }
            writeLog("blocked", ip, username, null, null,
                    "登录尝试过于频繁，请在 " + retryMinutes(state.remainingSeconds) + " 分钟后重试", null);
            throw new RateLimitedException(
                    "登录尝试过于频繁，请在 " + retryMinutes(state.remainingSeconds) + " 分钟后重试",
                    state.remainingSeconds);
        } catch (RateLimitedException e) {
            throw e;
        } catch (RuntimeException e) {
            // 防护检查自身异常不影响登录
            log.warn("admin login guard check failed: ip={}, error={}", ip, e.toString());
        }
    }

    // ------------------------------------------------------------------ 结果记录

    public void recordSuccess(String ip, String username, Long userId, String userAgent) {
        writeLog("success", ip, username, userId, userAgent, null, null);
    }

    public void recordCaptchaRequired(String ip, String username, Long userId, String userAgent, String reason) {
        writeLog("captcha_required", ip, username, userId, userAgent, reason, null);
    }

    /**
     * 记录一次失败并评估是否触发封禁。
     *
     * <p>outcome ∈ bad_credentials / user_disabled 的失败会参与计数；error 只记录。</p>
     */
    public void recordFailure(String outcome, String ip, String username, Long userId, String userAgent, String reason) {
        try {
            refreshPolicy();
            Integer attemptNo = nextAttemptNo(ip, username);
            writeLog(outcome, ip, username, userId, userAgent, reason, attemptNo);
            if (!isEnabled()) {
                return;
            }
            if (!"bad_credentials".equals(outcome) && !"user_disabled".equals(outcome)) {
                return;
            }
            evaluateBan(ip, safeUsername(username));
        } catch (RuntimeException e) {
            log.warn("admin login guard failure handling error: ip={}, outcome={}, error={}",
                    ip, outcome, e.toString());
        }
    }

    // ------------------------------------------------------------------ 内部逻辑

    private void evaluateBan(String ip, String username) {
        int windowSeconds = clamp(configService.getInt(CFG_FAIL_WINDOW_SECONDS, DEFAULT_FAIL_WINDOW_SECONDS), 60, 86_400);
        OffsetDateTime since = OffsetDateTime.now().minusSeconds(windowSeconds);

        int thresholdIp = clamp(configService.getInt(CFG_FAIL_THRESHOLD_IP, DEFAULT_FAIL_THRESHOLD_IP), 3, 100);
        long ipFails = logMapper.countFailuresByIp(ip, since);
        if (ipFails >= thresholdIp && !isIpAllowlisted(ip)) {
            createBlock("ip", ip, ipFails, "窗口内失败 " + ipFails + " 次（阈值 " + thresholdIp + "）");
        }

        int thresholdUser = clamp(configService.getInt(CFG_FAIL_THRESHOLD_USER, DEFAULT_FAIL_THRESHOLD_USER), 3, 100);
        if (!username.isEmpty()) {
            long userFails = logMapper.countFailuresByUsername(username, since);
            if (userFails >= thresholdUser) {
                createBlock("user", username, userFails, "窗口内失败 " + userFails + " 次（阈值 " + thresholdUser + "）");
            }
        }
    }

    private void createBlock(String scope, String value, long failCount, String reason) {
        if ("ip".equals(scope) && isIpAllowlisted(value)) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        long base = clamp(configService.getInt(CFG_BAN_SECONDS, DEFAULT_BAN_SECONDS), 60, (int) MAX_BAN_SECONDS);
        int recent = 0;
        try {
            recent = blockMapper.countRecentBlocks(scope, value, now.minusDays(7));
        } catch (RuntimeException ignored) {
            // 退避基数读不到就按基础时长处理
        }
        long duration = base;
        for (int i = 0; i < Math.min(recent, 10); i++) {
            duration = Math.min(duration * 2, MAX_BAN_SECONDS);
        }
        duration = Math.min(Math.max(duration, 60L), MAX_BAN_SECONDS);

        AdminLoginBlock block = new AdminLoginBlock();
        block.setScope(scope);
        block.setValue(value);
        block.setReason(truncate(reason, MAX_REASON));
        block.setFailCount((int) Math.min(failCount, Integer.MAX_VALUE));
        block.setBannedAt(now);
        block.setExpiresAt(now.plusSeconds(duration));
        boolean created = writer.createBlock(block);
        if (created) {
            log.warn("admin login guard: {} blocked scope={} value={} for {}s (fails={})",
                    "fail2ban", scope, "ip".equals(scope) ? value : maskUsername(value), duration, failCount);
        }
    }

    private BlockState findActiveBlock(String ip, String username, OffsetDateTime now) {
        AdminLoginBlock byIp = null;
        if (!ip.isEmpty()) {
            byIp = blockMapper.selectActive("ip", ip, now);
        }
        AdminLoginBlock byUser = null;
        if (!username.isEmpty()) {
            byUser = blockMapper.selectActive("user", username, now);
        }
        AdminLoginBlock chosen = byIp != null ? byIp : byUser;
        if (chosen == null) {
            return null;
        }
        long seconds = Math.max(1L, Duration.between(now, chosen.getExpiresAt()).getSeconds());
        return new BlockState(chosen.getScope(), chosen.getValue(), seconds);
    }

    private Integer nextAttemptNo(String ip, String username) {
        try {
            OffsetDateTime lastSuccess = logMapper.lastSuccessAt(ip, username);
            long consecutive = logMapper.countFailuresAfter(ip, username, lastSuccess);
            return (int) Math.min(consecutive + 1, Integer.MAX_VALUE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void writeLog(String outcome, String ip, String username, Long userId, String userAgent,
                          String reason, Integer attemptNo) {
        AdminLoginLog row = new AdminLoginLog();
        row.setUserId(userId);
        row.setUsername(truncate(safeUsername(username), 512));
        row.setIp(truncate(ip == null ? "" : ip, MAX_IP));
        row.setUserAgent(truncate(userAgent, MAX_USER_AGENT));
        row.setOutcome(outcome);
        row.setReason(truncate(reason, MAX_REASON));
        row.setAttemptNo(attemptNo);
        row.setCreatedAt(OffsetDateTime.now());
        writer.writeLog(row);
    }

    // ------------------------------------------------------------------ 配置/工具

    public boolean isIpAllowlisted(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String candidate = ip.trim().toLowerCase(Locale.ROOT);
        if (LOOPBACK.contains(candidate)) {
            return true;
        }
        // task-146：网关/nginx 自身的内部地址（172.18.x、10.x、192.168.x 等）永不封禁，
        // 否则一次失败阈值就会把代理封掉导致全网登录 429；同时打 WARN 提示 IP 链路退化。
        if (ClientIpResolver.isInternalIp(candidate)) {
            warnInternalNeverBanned(candidate);
            return true;
        }
        String csv = configService.get(CFG_IP_ALLOWLIST, "");
        if (csv == null || csv.isBlank()) {
            return false;
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .anyMatch(candidate::equals);
    }

    private void warnInternalNeverBanned(String ip) {
        long now = System.currentTimeMillis();
        long last = lastInternalIpWarn.get();
        if (now - last >= INTERNAL_WARN_INTERVAL_MS && lastInternalIpWarn.compareAndSet(last, now)) {
            log.warn("admin login guard: internal ip {} is exempt from ban (client ip chain may be degraded)", ip);
        }
    }

    public Set<String> allowlistEntries() {        Set<String> entries = new LinkedHashSet<>();
        String csv = configService.get(CFG_IP_ALLOWLIST, "");
        if (csv != null) {
            for (String part : csv.split(",")) {
                String entry = part.trim();
                if (!entry.isEmpty()) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    /**
     * 策略修改要求“下一次登录立即生效”：强制刷新一次配置缓存。
     *
     * <p>管理端登录是低频路径，system_config 行数很少，一次全表刷新成本可忽略；
     * 这样 {@code PUT /api/admin/login-guard} 后无需等待后台 30s 定时刷新。</p>
     */
    private void refreshPolicy() {
        try {
            configService.refreshCache();
        } catch (RuntimeException e) {
            log.warn("admin login guard: config refresh failed: {}", e.toString());
        }
    }

    private static String safeUsername(String username) {
        return username == null ? "" : username.trim();
    }    private static String maskUsername(String username) {
        if (username == null || username.length() <= 2) {
            return "***";
        }
        return username.charAt(0) + "***" + username.charAt(username.length() - 1);
    }

    private static String retryMinutes(long seconds) {
        long minutes = Math.max(1L, (seconds + 59L) / 60L);
        return String.valueOf(minutes);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record BlockState(String scope, String value, long remainingSeconds) {
    }
}
