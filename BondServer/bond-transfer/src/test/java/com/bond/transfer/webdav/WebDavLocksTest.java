package com.bond.transfer.webdav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WebDAV 锁（task-126）单元测试：令牌、冲突、depth 覆盖、过期与刷新。
 *
 * <p>存储用 {@link InMemoryWebDavLockStore}，语义与生产 DB 实现一致。</p>
 */
class WebDavLocksTest {

    private InMemoryWebDavLockStore store;
    private WebDavLocks locks;

    @BeforeEach
    void setUp() {
        store = new InMemoryWebDavLockStore();
        locks = new WebDavLocks(store);
    }

    @Test
    void lockThenUnlockWithCorrectToken() {
        WebDavLocks.LockInfo info = locks.lock("10:/a.txt", "tester", "0", 1800);
        assertNotNull(info);
        assertTrue(info.token().startsWith("opaquelocktoken:"));
        assertEquals("10:/a.txt", info.path());
        assertTrue(locks.hasLock("10:/a.txt"));

        assertTrue(locks.unlock(info.token()));
        assertFalse(locks.hasLock("10:/a.txt"));
        assertEquals(0, store.size());
    }

    @Test
    void unlockWithWrongTokenKeepsLock() {
        locks.lock("10:/a.txt", "", "0", 1800);

        assertFalse(locks.unlock("opaquelocktoken:wrong"));
        assertTrue(locks.hasLock("10:/a.txt"));
    }

    @Test
    void secondLockOnSameResourceConflictsWith423() {
        locks.lock("10:/a.txt", "", "0", 1800);

        WebDavException error = assertThrows(WebDavException.class,
                () -> locks.lock("10:/a.txt", "", "infinity", 1800));
        assertEquals(423, error.getStatus());
    }

    @Test
    void depthInfinityCoversChildrenButDepthZeroDoesNot() {
        WebDavLocks.LockInfo root = locks.lock("10:/docs", "", "infinity", 1800);
        assertTrue(locks.hasLock("10:/docs/a.txt"));
        assertTrue(locks.hasLock("10:/docs/sub/b.txt"));
        assertThrows(WebDavException.class, () -> locks.lock("10:/docs/a.txt", "", "0", 1800));
        assertTrue(locks.unlock(root.token()));

        locks.lock("10:/docs", "", "0", 1800);
        assertFalse(locks.hasLock("10:/docs/a.txt"));
    }

    @Test
    void timeoutIsClampedToDefaultsAndMaximum() {
        OffsetDateTime now = OffsetDateTime.now();
        WebDavLocks.LockInfo defaulted = locks.lock("10:/a.txt", "", "0", 0);
        assertTrue(defaulted.timeoutAt().isAfter(now.plusSeconds(1700)));
        assertTrue(defaulted.timeoutAt().isBefore(now.plusSeconds(1900)));

        WebDavLocks.LockInfo capped = locks.lock("10:/b.txt", "", "0", Long.MAX_VALUE);
        assertTrue(capped.timeoutAt().isBefore(now.plusSeconds(24 * 60 * 60 + 120)));
        assertTrue(capped.timeoutAt().isAfter(now.plusSeconds(24 * 60 * 60 - 120)));
    }

    @Test
    void expiredLockIsIgnoredAndPurged() {
        WebDavLocks.LockInfo info = locks.lock("10:/a.txt", "", "0", 1800);
        store.expireAll();

        assertFalse(locks.hasLock("10:/a.txt"));
        assertNull(locks.find(info.token()));
        assertEquals(1, locks.purgeExpired());
        assertEquals(0, store.size());

        assertNotNull(locks.lock("10:/a.txt", "", "0", 1800));
    }

    @Test
    void refreshExtendsTimeout() {
        WebDavLocks.LockInfo info = locks.lock("10:/a.txt", "", "0", 60);
        OffsetDateTime before = info.timeoutAt();

        WebDavLocks.LockInfo refreshed = locks.refresh(info.token(), 600);
        assertNotNull(refreshed);
        assertTrue(refreshed.timeoutAt().isAfter(before));
        assertNull(locks.refresh("opaquelocktoken:missing", 600));
        assertNull(locks.refresh(null, 600));
    }

    @Test
    void twoPathsGetDistinctTokens() {
        WebDavLocks.LockInfo first = locks.lock("10:/a.txt", "", "0", 1800);
        WebDavLocks.LockInfo second = locks.lock("10:/b.txt", "", "0", 1800);

        assertNotEquals(first.token(), second.token());
        assertTrue(locks.unlock(first.token()));
        assertTrue(locks.hasLock("10:/b.txt"));
    }

    @Test
    void coveringListsParentLocksAndCoversNormalizesPaths() {
        WebDavLocks.LockInfo root = locks.lock("10:/docs/", "", "infinity", 1800);

        assertEquals(1, locks.covering("10:/docs/deep/x.md").size());
        assertEquals(root.token(), locks.covering("10:/docs/deep/x.md").get(0).token());
        assertTrue(WebDavLocks.covers("infinity", "/docs", "/docs/a"));
        assertFalse(WebDavLocks.covers("0", "/docs", "/docs/a"));
        assertEquals("/a", WebDavLocks.normalize("a/"));
    }
}
