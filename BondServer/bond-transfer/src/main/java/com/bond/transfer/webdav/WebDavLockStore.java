package com.bond.transfer.webdav;

import com.bond.common.entity.WebDavLock;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * WebDAV 锁存储抽象：生产用 DB（{@code com.bond.transfer.mapper.WebDavLockMapper}），单测用内存实现。
 *
 * <p>这样做是为了让 {@link WebDavLocks} 的冲突/覆盖判定可以脱离数据库单测，
 * 同时生产端与所有实例共享同一份锁状态（多实例部署时也不会各锁各的）。</p>
 */
public interface WebDavLockStore {

    List<WebDavLock> findActive(OffsetDateTime now);

    WebDavLock findByToken(String token);

    void insert(WebDavLock lock);

    void updateTimeout(String token, OffsetDateTime timeoutAt);

    void deleteByToken(String token);

    int deleteExpired(OffsetDateTime now);
}
