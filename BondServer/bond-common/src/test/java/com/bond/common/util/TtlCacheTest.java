package com.bond.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * task-30：有界 TTL 缓存的语义单测（假时钟推进，不 sleep）。
 */
class TtlCacheTest {

    private final AtomicLong clock = new AtomicLong(1_000L);

    private TtlCache<String, String> cache(long ttlMs, int maxEntries) {
        return new TtlCache<>(ttlMs, maxEntries, clock::get);
    }

    @Test
    @DisplayName("TTL 内命中，过期后视为不存在")
    void expiresAfterTtl() {
        TtlCache<String, String> cache = cache(100L, 10);
        cache.put("k", "v");

        assertEquals("v", cache.get("k"));
        clock.addAndGet(100L);
        assertNull(cache.get("k"));
    }

    @Test
    @DisplayName("getOrCompute 在 TTL 内只加载一次，过期后重新加载")
    void getOrComputeCachesResult() {
        TtlCache<String, String> cache = cache(100L, 10);
        AtomicInteger loads = new AtomicInteger();
        java.util.function.Supplier<String> loader = () -> "v" + loads.incrementAndGet();

        assertEquals("v1", cache.getOrCompute("k", loader));
        assertEquals("v1", cache.getOrCompute("k", loader), "TTL 内应命中缓存");
        assertEquals(1, loads.get());

        clock.addAndGet(101L);
        assertEquals("v2", cache.getOrCompute("k", loader), "过期后应重新加载");
        assertEquals(2, loads.get());
    }

    @Test
    @DisplayName("并发 getOrCompute 同一 key 只加载一次（单飞）")
    void concurrentGetOrComputeLoadsOnce() throws Exception {
        TtlCache<String, String> cache = cache(60_000L, 10);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.function.Supplier<String> loader = () -> {
            loads.incrementAndGet();
            loaderEntered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "v";
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> cache.getOrCompute("k", loader));
            loaderEntered.await(2, TimeUnit.SECONDS);
            Future<String> second = executor.submit(() -> cache.getOrCompute("k", loader));
            release.countDown();

            assertEquals("v", first.get(2, TimeUnit.SECONDS));
            assertEquals("v", second.get(2, TimeUnit.SECONDS));
            assertEquals(1, loads.get(), "同一 key 并发只允许加载一次");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("invalidate / clear 后不再命中")
    void invalidateRemovesEntry() {
        TtlCache<String, String> cache = cache(60_000L, 10);
        cache.put("a", "1");
        cache.put("b", "2");

        cache.invalidate("a");
        assertNull(cache.get("a"));
        assertEquals("2", cache.get("b"));

        cache.clear();
        assertNull(cache.get("b"));
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("超过 maxEntries：先清过期，仍超限则淘汰最旧，容量有硬上限")
    void enforcesCapacityBound() {
        TtlCache<String, String> cache = cache(100L, 2);
        cache.put("a", "1");
        cache.put("b", "2");

        clock.addAndGet(1_000L); // a/b 全部过期
        cache.put("c", "3");

        assertEquals(1, cache.size(), "过期条目应在超限时被清理");
        assertEquals("3", cache.get("c"));
    }

    @Test
    @DisplayName("未过期但超过上限：淘汰最旧条目，容量不超过 maxEntries")
    void evictsOldestWhenOverCapacity() {
        TtlCache<String, String> cache = cache(60_000L, 2);
        cache.put("a", "1");
        clock.addAndGet(10L);
        cache.put("b", "2");
        clock.addAndGet(10L);
        cache.put("c", "3");

        assertEquals(2, cache.size());
        assertNull(cache.get("a"), "最旧条目应被淘汰");
        assertEquals("2", cache.get("b"));
        assertEquals("3", cache.get("c"));
    }

    @Test
    @DisplayName("null key 不缓存（直接执行 loader）；null 值允许但不产生命中语义")
    void nullKeyIsPassThrough() {
        TtlCache<String, String> cache = cache(100L, 10);
        assertEquals("computed", cache.getOrCompute(null, () -> "computed"));
        cache.put(null, "ignored");
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("构造参数非法时快速失败")
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new TtlCache<>(0L, 10));
        assertThrows(IllegalArgumentException.class, () -> new TtlCache<>(100L, 0));
        assertThrows(IllegalArgumentException.class, () -> new TtlCache<>(100L, 10, null));
    }

    @Test
    @DisplayName("批量写入后容量仍受 maxEntries 约束（不会无界增长）")
    void manyKeysStayBounded() {
        TtlCache<String, String> cache = cache(60_000L, 5);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String key = "k" + i;
            keys.add(key);
            cache.put(key, "v" + i);
        }
        assertEquals(5, cache.size());
    }
}
