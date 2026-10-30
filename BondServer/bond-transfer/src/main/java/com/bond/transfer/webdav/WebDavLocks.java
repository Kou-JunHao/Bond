package com.bond.transfer.webdav;

import com.bond.common.entity.WebDavLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * WebDAV 排他写锁（task-126）。
 *
 * <p>与 MVP 的“假锁”不同，这里所有状态都落在 {@link WebDavLockStore}（生产是
 * {@code webdav_locks} 表），因此：</p>
 * <ul>
 *   <li>锁在进程重启/多实例部署时依然有效；</li>
 *   <li>被锁资源的写操作会被 {@link WebDavService#handle} 拦成 423，不再静默覆盖；</li>
 *   <li>锁过期后自动清理，不依赖客户端发 UNLOCK。</li>
 * </ul>
 *
 * <p>只实现 exclusive write + depth 0/infinity，对应 WebDAV class 2 的最小可用能力；
 * 共享锁（shared lock）明确不支持。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebDavLocks {

    static final long DEFAULT_TIMEOUT_SECONDS = 1800L;
    static final long MAX_TIMEOUT_SECONDS = 24 * 60 * 60L;
    static final String TOKEN_PREFIX = "opaquelocktoken:";

    private final WebDavLockStore store;

    public record LockInfo(String token, String path, String owner, String depth,
                           OffsetDateTime timeoutAt) {
    }

    /** 创建锁；同一路径/子树已有有效锁时抛 423（WebDAV Locked）。 */
    public LockInfo lock(String path, String owner, String depth, long timeoutSeconds) {
        String key = normalize(path);
        String normalizedDepth = "0".equals(depth) ? "0" : "infinity";
        long timeout = clampTimeout(timeoutSeconds);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        store.deleteExpired(now);

        for (WebDavLock existing : store.findActive(now)) {
            boolean coveredByExisting = covers(existing.getDepth(), existing.getResourcePath(), key);
            boolean coversExisting = "infinity".equals(normalizedDepth)
                    && covers("0", key, existing.getResourcePath());
            if (coveredByExisting || coversExisting) {
                throw new WebDavException(423, "resource is locked");
            }
        }

        WebDavLock lock = new WebDavLock();
        lock.setToken(TOKEN_PREFIX + UUID.randomUUID());
        lock.setResourcePath(key);
        lock.setOwner(owner);
        lock.setDepth(normalizedDepth);
        lock.setTimeoutAt(now.plusSeconds(timeout));
        lock.setCreatedAt(now);
        try {
            store.insert(lock);
        } catch (DuplicateKeyException e) {
            // 并发抢锁：DB 唯一索引兜底，语义与先查后锁一致。
            throw new WebDavException(423, "resource is locked");
        }
        log.info("WebDAV lock created: path={}, depth={}, timeout={}s", key, normalizedDepth, timeout);
        return toInfo(lock);
    }

    /** 刷新锁（LOCK 到已锁定资源且携带同一 token）。返回 null 表示 token 不存在/已过期。 */
    public LockInfo refresh(String token, long timeoutSeconds) {
        if (token == null || token.isBlank()) {
            return null;
        }
        WebDavLock existing = store.findByToken(token);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (existing == null || existing.getTimeoutAt() == null || !existing.getTimeoutAt().isAfter(now)) {
            return null;
        }
        OffsetDateTime timeoutAt = now.plusSeconds(clampTimeout(timeoutSeconds));
        store.updateTimeout(token, timeoutAt);
        existing.setTimeoutAt(timeoutAt);
        return toInfo(existing);
    }

    /** 删除锁；token 不存在返回 false。 */
    public boolean unlock(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        WebDavLock existing = store.findByToken(token);
        if (existing == null) {
            return false;
        }
        store.deleteByToken(token);
        return true;
    }

    public LockInfo find(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        WebDavLock lock = store.findByToken(token);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (lock == null || lock.getTimeoutAt() == null || !lock.getTimeoutAt().isAfter(now)) {
            return null;
        }
        return toInfo(lock);
    }

    /** 当前所有有效的锁（PROPFIND 输出 lockdiscovery 用）。 */
    public List<LockInfo> active() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<LockInfo> locks = new ArrayList<>();
        for (WebDavLock lock : store.findActive(now)) {
            locks.add(toInfo(lock));
        }
        return locks;
    }

    /** 指定路径是否被有效锁覆盖（含父目录 depth=infinity 的锁）。 */
    public boolean hasLock(String path) {
        String key = normalize(path);
        for (LockInfo lock : active()) {
            if (covers(lock.depth(), lock.path(), key)) {
                return true;
            }
        }
        return false;
    }

    /** 返回覆盖指定路径的所有锁。 */
    public List<LockInfo> covering(String path) {
        String key = normalize(path);
        List<LockInfo> result = new ArrayList<>();
        for (LockInfo lock : active()) {
            if (covers(lock.depth(), lock.path(), key)) {
                result.add(lock);
            }
        }
        return result;
    }

    int purgeExpired() {
        return store.deleteExpired(OffsetDateTime.now(ZoneOffset.UTC));
    }

    static boolean covers(String depth, String lockPath, String candidate) {
        String base = normalize(lockPath);
        String target = normalize(candidate);
        if (target.equals(base)) {
            return true;
        }
        if (!"infinity".equalsIgnoreCase(depth)) {
            return false;
        }
        return target.startsWith(base + "/");
    }

    /**
     * 归一化锁资源路径。
     *
     * <p>锁 key 形如 {@code {workspaceId}:/a/b} 或 {@code personal:/a/b}（{@link WebDavService}
     * 生成的 key 含挂载前缀），因此不能简单给所有路径补前导斜杠，否则 {@code "10:/a.txt}"}
     * 会被改写为 {@code "/10:/a.txt"} 导致与调用方比较不一致。</p>
     */
    static String normalize(String path) {
        String value = path == null ? "" : path.trim();
        if (value.isEmpty()) {
            return "/";
        }
        String prefix = "";
        String rest = value;
        int colon = value.indexOf(':');
        if (colon > 0) {
            prefix = value.substring(0, colon + 1);
            rest = value.substring(colon + 1);
        }
        if (!rest.startsWith("/")) {
            rest = "/" + rest;
        }
        while (rest.length() > 1 && rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        return prefix + rest;
    }

    static long clampTimeout(long seconds) {
        if (seconds <= 0) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.min(seconds, MAX_TIMEOUT_SECONDS);
    }

    private static LockInfo toInfo(WebDavLock lock) {
        return new LockInfo(lock.getToken(), lock.getResourcePath(), lock.getOwner(), lock.getDepth(),
                lock.getTimeoutAt());
    }
}
