package com.bond.transfer.webdav;

import com.bond.common.entity.WebDavLock;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** 单测用的内存锁存储：语义与 DB 实现一致（token/path 唯一、过期即删除）。 */
public class InMemoryWebDavLockStore implements WebDavLockStore {

    private final List<WebDavLock> locks = new ArrayList<>();
    private long nextId = 1L;

    @Override
    public List<WebDavLock> findActive(OffsetDateTime now) {
        List<WebDavLock> result = new ArrayList<>();
        for (WebDavLock lock : locks) {
            if (lock.getTimeoutAt() != null && lock.getTimeoutAt().isAfter(now)) {
                result.add(lock);
            }
        }
        return result;
    }

    @Override
    public WebDavLock findByToken(String token) {
        for (WebDavLock lock : locks) {
            if (lock.getToken().equals(token)) {
                return lock;
            }
        }
        return null;
    }

    @Override
    public void insert(WebDavLock lock) {
        lock.setId(nextId++);
        locks.add(lock);
    }

    @Override
    public void updateTimeout(String token, OffsetDateTime timeoutAt) {
        WebDavLock lock = findByToken(token);
        if (lock != null) {
            lock.setTimeoutAt(timeoutAt);
        }
    }

    @Override
    public void deleteByToken(String token) {
        locks.removeIf(lock -> lock.getToken().equals(token));
    }

    @Override
    public int deleteExpired(OffsetDateTime now) {
        int before = locks.size();
        locks.removeIf(lock -> lock.getTimeoutAt() == null || !lock.getTimeoutAt().isAfter(now));
        return before - locks.size();
    }

    /** 测试辅助：把所有锁置为已过期。 */
    public void expireAll() {
        OffsetDateTime past = OffsetDateTime.now().minusHours(1);
        for (WebDavLock lock : locks) {
            lock.setTimeoutAt(past);
        }
    }

    public int size() {
        return locks.size();
    }
}
