package com.bond.common.util;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 有界 TTL 进程内缓存（不引入 Caffeine / Spring Cache 依赖）。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>{@link #get(Object)} 过期条目视为不存在，并顺手删除；</li>
 *   <li>{@link #getOrCompute(Object, Supplier)} 同一 key 并发只加载一次（{@code compute} 原子）；</li>
 *   <li>{@link #put(Object, Object)} 后若超过 {@code maxEntries}：先清过期，仍超限则按
 *       过期时间从旧到新淘汰——保证缓存大小有硬上限，不会成为内存泄漏。</li>
 * </ul>
 *
 * <p>规模受 {@code maxEntries} 约束，淘汰用一次 O(n log n) 排序，只在超限时触发，可接受。</p>
 */
public final class TtlCache<K, V> {

    private final long ttlMillis;
    private final int maxEntries;
    private final LongSupplier clock;
    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();

    public TtlCache(long ttlMillis, int maxEntries) {
        this(ttlMillis, maxEntries, System::currentTimeMillis);
    }

    /** 可注入时钟的构造器（单测用来验证 TTL 过期，不需要 sleep）。 */
    public TtlCache(long ttlMillis, int maxEntries, LongSupplier clock) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be > 0");
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be > 0");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.ttlMillis = ttlMillis;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    public V get(K key) {
        if (key == null) {
            return null;
        }
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtMillis() <= clock.getAsLong()) {
            entries.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    /** 命中直接返回；未命中（或已过期）时用 loader 加载并写入。 */
    public V getOrCompute(K key, Supplier<V> loader) {
        if (key == null) {
            V computed = loader.get();
            return computed;
        }
        V cached = get(key);
        if (cached != null) {
            return cached;
        }
        long now = clock.getAsLong();
        Entry<V> entry = entries.compute(key, (k, old) -> {
            if (old != null && old.expiresAtMillis() > now) {
                return old;
            }
            return new Entry<>(loader.get(), now + ttlMillis);
        });
        return entry == null ? null : entry.value();
    }

    public void put(K key, V value) {
        if (key == null) {
            return;
        }
        entries.put(key, new Entry<>(value, clock.getAsLong() + ttlMillis));
        enforceCapacity();
    }

    public void invalidate(K key) {
        if (key != null) {
            entries.remove(key);
        }
    }

    public void clear() {
        entries.clear();
    }

    /**
     * 当前未过期条目的只读快照（key → value）。
     *
     * <p>用于需要按 value 维度批量失效的场景（例如档位参数变更后清理该档位的用户缓存）；
     * 快照是拷贝，遍历后调用 {@link #invalidate(Object)} 不受并发修改影响。</p>
     */
    public Map<K, V> snapshot() {
        long now = clock.getAsLong();
        Map<K, V> copy = new java.util.LinkedHashMap<>();
        entries.forEach((key, entry) -> {
            if (entry.expiresAtMillis() > now) {
                copy.put(key, entry.value());
            }
        });
        return java.util.Collections.unmodifiableMap(copy);
    }

    public int size() {
        return entries.size();
    }

    private void enforceCapacity() {
        if (entries.size() <= maxEntries) {
            return;
        }
        long now = clock.getAsLong();
        entries.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
        int overflow = entries.size() - maxEntries;
        if (overflow <= 0) {
            return;
        }
        // 最旧（最先过期）的先淘汰；同 expiresAt 时按未知顺序，不影响正确性
        entries.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().expiresAtMillis()))
                .limit(overflow)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(entries::remove);
    }

    private record Entry<V>(V value, long expiresAtMillis) {
    }
}
