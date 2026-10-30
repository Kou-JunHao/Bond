package com.bond.common.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SnowflakeId 单测：唯一性 / 单调性 / 时钟回拨 / 序列耗尽 / workerId 校验。
 * 不依赖 Spring、数据库，可在本机直接 {@code mvn test}。
 */
class SnowflakeIdTest {

    private static final long T0 = SnowflakeId.EPOCH + 1_000_000L;

    @BeforeEach
    @AfterEach
    void reset() {
        SnowflakeId.resetForTest();
    }

    @Test
    @DisplayName("连续生成 100 万个 ID：无重复且严格递增（task-10 强制验证项）")
    void oneMillionIdsAreUniqueAndStrictlyIncreasing() {
        int total = 1_000_000;
        Set<Long> seen = new HashSet<>(total * 2);
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < total; i++) {
            long id = SnowflakeId.nextId();
            assertTrue(seen.add(id), "duplicate id at index " + i + ": " + id);
            assertTrue(id > previous, "id not strictly increasing at index " + i + ": " + previous + " -> " + id);
            previous = id;
        }
        assertEquals(total, seen.size());
    }

    @Test
    @DisplayName("ID 已超过 JS 安全整数 2^53（大于 253 位），必须字符串化")
    void idsExceedJavaScriptSafeInteger() {
        long id = SnowflakeId.nextId();
        assertTrue(id > (1L << 53), "expected id > 2^53 but was " + id);
    }

    @Test
    @DisplayName("时钟回拨 4ms（<5ms）：自旋等待后继续生成且 ID 递增")
    void smallClockBackwardsSpinsAndRecovers() {
        SnowflakeId.setTimeSource(sequence(T0, T0 - 4, T0 - 3, T0 - 2, T0 - 1, T0, T0 + 1));
        long first = SnowflakeId.nextId();
        long second = SnowflakeId.nextId();
        assertTrue(second > first, "second id should be greater after spin: " + first + " -> " + second);
        assertEquals(T0 + 1, timestampOf(second), "should resume at caught-up timestamp");
    }

    @Test
    @DisplayName("时钟回拨 5ms（>=5ms）：抛 IllegalStateException，不无限自旋")
    void largeClockBackwardsThrows() {
        SnowflakeId.setTimeSource(sequence(T0, T0 - 5));
        SnowflakeId.nextId();
        IllegalStateException ex = assertThrows(IllegalStateException.class, SnowflakeId::nextId);
        assertTrue(ex.getMessage().contains("moved backwards"), "unexpected message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("5ms"), "message should include deviation: " + ex.getMessage());
    }

    @Test
    @DisplayName("同一毫秒 4096 个序列耗尽后等待下一毫秒，不重复")
    void sequenceOverflowWaitsNextMillis() {
        AtomicLong calls = new AtomicLong();
        SnowflakeId.setTimeSource(() -> calls.incrementAndGet() <= 5000 ? T0 : T0 + 1);

        int total = (int) SnowflakeId.MAX_SEQUENCE + 1; // 4096 个/毫秒，第 4097 个触发进位
        Set<Long> seen = new HashSet<>();
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < total + 1; i++) {
            long id = SnowflakeId.nextId();
            assertTrue(seen.add(id), "duplicate id: " + id);
            assertTrue(id > previous, "not increasing at index " + i);
            previous = id;
        }
        assertEquals(total + 1, seen.size());
        assertEquals(T0 + 1, timestampOf(previous), "4097th id must be in next millisecond");
    }

    @Test
    @DisplayName("workerId 越界（-1 / 32）启动校验失败")
    void invalidWorkerIdRejected() {
        assertThrows(IllegalStateException.class, () -> SnowflakeId.configureWorkerId(-1));
        assertThrows(IllegalStateException.class, () -> SnowflakeId.configureWorkerId(32));
        assertEquals(0L, SnowflakeId.requireValidWorkerId(0, "test"));
        assertEquals(31L, SnowflakeId.requireValidWorkerId(31, "test"));
    }

    @Test
    @DisplayName("workerId 进入 ID 位段（5 bit）")
    void workerIdIsEncodedInId() {
        SnowflakeId.configureWorkerId(31);
        long id = SnowflakeId.nextId();
        assertEquals(31L, (id >> SnowflakeId.SEQUENCE_BITS) & SnowflakeId.MAX_WORKER_ID);
    }

    @Test
    @DisplayName("默认 workerId 由 WORKER_ID 或 hostname 解析，始终在 0..31")
    void defaultWorkerIdInRange() {
        assertTrue(SnowflakeId.getWorkerId() >= 0 && SnowflakeId.getWorkerId() <= SnowflakeId.MAX_WORKER_ID);
        assertEquals(SnowflakeId.EPOCH, SnowflakeId.getEpoch());
    }

    // ── helpers ──

    private static long timestampOf(long id) {
        return (id >> (SnowflakeId.WORKER_ID_BITS + SnowflakeId.SEQUENCE_BITS)) + SnowflakeId.EPOCH;
    }

    private static LongSupplier sequence(long... values) {
        AtomicInteger index = new AtomicInteger();
        return () -> values[Math.min(index.getAndIncrement(), values.length - 1)];
    }
}
