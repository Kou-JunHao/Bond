package com.bond.common.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 雪花 ID 生成器 —— Bond 服务端唯一的 ID 生成实现。
 *
 * <p>位布局（64 bit，最高位恒 0）：</p>
 * <pre>
 * | 1 bit unused | 41 bit timestamp(ms) | 5 bit workerId | 12 bit sequence |
 * </pre>
 *
 * <ul>
 *   <li>epoch = 2023-11-14T22:13:20Z（{@link #EPOCH}），41 bit 可用到约 2093 年；</li>
 *   <li>workerId 取值 0..31，默认从环境变量 {@code WORKER_ID} 读取，未配置时按 hostname 哈希派生；</li>
 *   <li>时钟回拨 &lt; {@link #MAX_BACKWARD_TOLERANCE_MS}ms：短暂自旋等待时钟追平；</li>
 *   <li>时钟回拨 &gt;= {@link #MAX_BACKWARD_TOLERANCE_MS}ms：抛 {@link IllegalStateException}，避免静默卡死；</li>
 *   <li>同一毫秒内序列号用尽（4096 个）时自旋到下一毫秒，不会生成重复 ID。</li>
 * </ul>
 *
 * <p>MyBatis-Plus 的 {@code IdType.ASSIGN_ID} 通过
 * {@code com.bond.common.id.SnowflakeIdentifierGenerator} 委托到本类，
 * 业务代码里的显式 {@code SnowflakeId.nextId()} 也走同一份静态状态，
 * 因此整个服务端只有一套雪花实现。</p>
 */
public final class SnowflakeId {

    /** 起始纪元：2023-11-14T22:13:20Z（毫秒）。 */
    public static final long EPOCH = 1700000000000L;

    /** workerId 占用位数（0..31，最多 32 个实例）。 */
    public static final int WORKER_ID_BITS = 5;

    /** 同一毫秒内的序列号位数（0..4095，每毫秒最多 4096 个 ID）。 */
    public static final int SEQUENCE_BITS = 12;

    public static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;

    public static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    /** 允许自旋等待的时钟回拨上限（毫秒）：回拨小于该值等待，大于等于该值抛异常。 */
    public static final long MAX_BACKWARD_TOLERANCE_MS = 5L;

    /** 自旋等待的兜底时间（真实时钟），防止 timeSource 被冻住时无限循环。 */
    private static final long SPIN_GUARD_NANOS = TimeUnit.MILLISECONDS.toNanos(500L);

    private static final String WORKER_ID_ENV = "WORKER_ID";
    private static final String WORKER_ID_PROPERTY = "WORKER_ID";

    private static final Logger log = LoggerFactory.getLogger(SnowflakeId.class);

    private static final Resolution DEFAULT_RESOLUTION = resolveWorkerId();

    /** 当前 workerId（可由 {@link #configureWorkerId(long)} 在启动/测试时覆盖）。 */
    private static volatile long workerId = DEFAULT_RESOLUTION.workerId();

    private static volatile String workerIdSource = DEFAULT_RESOLUTION.source();

    /** 时间源，默认 {@link System#currentTimeMillis()}；测试可替换。 */
    private static volatile LongSupplier timeSource = System::currentTimeMillis;

    private static long sequence = 0L;

    private static long lastTimestamp = -1L;

    private SnowflakeId() {
    }

    static {
        log.info("SnowflakeId initialized: workerId={} (source={}), epoch={}, workerIdBits={}, sequenceBits={}",
                workerId, workerIdSource, EPOCH, WORKER_ID_BITS, SEQUENCE_BITS);
        if ("hostname".equals(workerIdSource)) {
            log.warn("WORKER_ID 未显式配置，按 hostname 派生 workerId={}（{}）。"
                            + "多实例/多 JVM 部署必须显式设置环境变量 WORKER_ID=0..{}，否则可能撞 ID。",
                    workerId, DEFAULT_RESOLUTION.detail(), MAX_WORKER_ID);
        }
    }

    /**
     * 生成下一个 ID（线程安全、单调递增）。
     *
     * @throws IllegalStateException 时钟回拨超过允许范围，或时钟长时间不前进
     */
    public static synchronized long nextId() {
        long timestamp = timeSource.getAsLong();
        if (timestamp < lastTimestamp) {
            long backwardMs = lastTimestamp - timestamp;
            if (backwardMs >= MAX_BACKWARD_TOLERANCE_MS) {
                throw new IllegalStateException("Clock moved backwards by " + backwardMs
                        + "ms, lastTimestamp=" + lastTimestamp + ", currentTimestamp=" + timestamp
                        + "; refusing to generate id");
            }
            timestamp = spinUntilAfter(lastTimestamp, "Clock moved backwards by " + backwardMs + "ms");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0L) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        long currentWorkerId = workerId;
        return ((timestamp - EPOCH) << (WORKER_ID_BITS + SEQUENCE_BITS))
                | (currentWorkerId << SEQUENCE_BITS)
                | sequence;
    }

    /** 当前生效的 workerId。 */
    public static long getWorkerId() {
        return workerId;
    }

    /** workerId 的来源：env:WORKER_ID / property:WORKER_ID / hostname / runtime / test。 */
    public static String getWorkerIdSource() {
        return workerIdSource;
    }

    public static long getEpoch() {
        return EPOCH;
    }

    /**
     * 覆盖 workerId（测试或嵌入式场景使用；生产推荐环境变量 WORKER_ID）。
     *
     * @param id 0..{@link #MAX_WORKER_ID}
     * @throws IllegalStateException 越界
     */
    public static synchronized void configureWorkerId(long id) {
        workerId = requireValidWorkerId(id, "configureWorkerId");
        workerIdSource = "runtime";
        log.info("SnowflakeId workerId changed to {} (source=runtime)", id);
    }

    /** 校验 workerId 是否在 0..31，返回原值。 */
    public static long requireValidWorkerId(long id, String origin) {
        if (id < 0 || id > MAX_WORKER_ID) {
            throw new IllegalStateException("WORKER_ID " + id + " (from " + origin
                    + ") out of range 0.." + MAX_WORKER_ID);
        }
        return id;
    }

    // ── 内部实现 ──

    private static long waitNextMillis(long last) {
        long timestamp = timeSource.getAsLong();
        long guardDeadline = System.nanoTime() + SPIN_GUARD_NANOS;
        while (timestamp <= last) {
            Thread.onSpinWait();
            timestamp = timeSource.getAsLong();
            if (System.nanoTime() > guardDeadline) {
                throw new IllegalStateException("Clock did not advance beyond lastTimestamp=" + last
                        + " within " + TimeUnit.NANOSECONDS.toMillis(SPIN_GUARD_NANOS)
                        + "ms; refusing to wait forever");
            }
        }
        return timestamp;
    }

    private static long spinUntilAfter(long target, String reason) {
        long timestamp = timeSource.getAsLong();
        long guardDeadline = System.nanoTime() + SPIN_GUARD_NANOS;
        while (timestamp <= target) {
            Thread.onSpinWait();
            timestamp = timeSource.getAsLong();
            if (System.nanoTime() > guardDeadline) {
                throw new IllegalStateException(reason + " but clock did not catch up within "
                        + TimeUnit.NANOSECONDS.toMillis(SPIN_GUARD_NANOS) + "ms, lastTimestamp=" + target);
            }
        }
        return timestamp;
    }

    private static Resolution resolveWorkerId() {
        String fromEnv = trimToNull(System.getenv(WORKER_ID_ENV));
        if (fromEnv != null) {
            long id = parseWorkerId(fromEnv, "env " + WORKER_ID_ENV);
            return new Resolution(id, "env:" + WORKER_ID_ENV, WORKER_ID_ENV + "=" + fromEnv);
        }
        String fromProperty = trimToNull(System.getProperty(WORKER_ID_PROPERTY));
        if (fromProperty != null) {
            long id = parseWorkerId(fromProperty, "system property " + WORKER_ID_PROPERTY);
            return new Resolution(id, "property:" + WORKER_ID_PROPERTY, "-D" + WORKER_ID_PROPERTY + "=" + fromProperty);
        }
        String hostName = firstNonBlank(
                System.getenv("HOSTNAME"),
                System.getenv("COMPUTERNAME"),
                "unknown-host");
        long derived = Math.floorMod(hostName.hashCode(), (int) (MAX_WORKER_ID + 1));
        return new Resolution(derived, "hostname", "HOSTNAME=" + hostName + " -> hash&31");
    }

    private static long parseWorkerId(String raw, String origin) {
        final long id;
        try {
            id = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid WORKER_ID '" + raw + "' from " + origin
                    + ": must be an integer in 0.." + MAX_WORKER_ID, e);
        }
        return requireValidWorkerId(id, origin);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return "unknown-host";
    }

    // ── 测试钩子（包内可见） ──

    static synchronized void setTimeSource(LongSupplier source) {
        timeSource = Objects.requireNonNull(source, "timeSource");
    }

    static synchronized void resetForTest() {
        workerId = DEFAULT_RESOLUTION.workerId();
        workerIdSource = DEFAULT_RESOLUTION.source();
        sequence = 0L;
        lastTimestamp = -1L;
        timeSource = System::currentTimeMillis;
    }

    private record Resolution(long workerId, String source, String detail) {
    }
}
