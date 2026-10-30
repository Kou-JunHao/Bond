package com.bond.transfer.ops;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 运维定时任务的运行记录（task-74 C；task-157 扩展 nextRunAt 与 deletedCount/failedCount 别名）。
 *
 * <p>单实例内存即可：本项目按单实例部署（聊天 WS 的 hub 同样依赖单实例），
 * 重启后历史记录清空无妨，管理端只用于“最近一次跑得怎么样”。</p>
 *
 * <p>对外字段（{@code GET /api/admin/ops/jobs}）：{@code name/status/startedAt/finishedAt/
 * durationMs/processed/errors/detail/nextRunAt}，并额外提供语义别名
 * {@code deletedCount}（= processed）与 {@code failedCount}（= errors），响应只增不改。</p>
 */
@Slf4j
@Component
public class OpsJobRegistry {

    /** 单个任务一次运行的结果（processed/errors 由任务自己统计）。 */
    public record JobResult(long processed, long errors, String detail) {
        public static JobResult of(long processed, String detail) {
            return new JobResult(processed, 0, detail);
        }
    }

    /** 对外暴露的任务快照（{@code GET /api/admin/ops/jobs} 的响应元素）。 */
    public record JobSnapshot(String name,
                              String status,
                              String startedAt,
                              String finishedAt,
                              long durationMs,
                              long processed,
                              long errors,
                              String detail,
                              String nextRunAt) {

        /** 语义别名：任务实际处理/删除的行数。 */
        @JsonProperty("deletedCount")
        public long deletedCount() {
            return processed;
        }

        /** 语义别名：任务失败计数。 */
        @JsonProperty("failedCount")
        public long failedCount() {
            return errors;
        }
    }

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_COMPLETED_WITH_ERRORS = "COMPLETED_WITH_ERRORS";
    private static final String STATUS_FAILED = "FAILED";

    private final Map<String, JobSnapshot> latest = new ConcurrentHashMap<>();
    private final Map<String, String> nextRuns = new ConcurrentHashMap<>();

    /**
     * 执行一次任务并记录结果。任务内部异常不再向外抛（定时任务里抛只会进日志），
     * 统一记成 FAILED 快照，供管理端观测。
     */
    public JobResult run(String name, Supplier<JobResult> job) {
        Instant start = Instant.now();
        latest.put(name, snapshot(name, STATUS_RUNNING, start.toString(), null, 0L, 0L, 0L, null));
        try {
            JobResult result = job.get();
            if (result == null) {
                result = new JobResult(0L, 0L, "");
            }
            Instant end = Instant.now();
            long durationMs = Duration.between(start, end).toMillis();
            String status = result.errors() > 0 ? STATUS_COMPLETED_WITH_ERRORS : STATUS_COMPLETED;
            latest.put(name, snapshot(name, status, start.toString(), end.toString(), durationMs,
                    result.processed(), result.errors(), result.detail()));
            log.info("ops job {} {} in {}ms: processed={}, errors={}, {}", name, status, durationMs,
                    result.processed(), result.errors(), result.detail());
            return result;
        } catch (RuntimeException e) {
            Instant end = Instant.now();
            long durationMs = Duration.between(start, end).toMillis();
            latest.put(name, snapshot(name, STATUS_FAILED, start.toString(), end.toString(), durationMs,
                    0L, 1L, e.toString()));
            log.error("ops job {} failed after {}ms", name, durationMs, e);
            return new JobResult(0L, 1L, e.toString());
        }
    }

    /**
     * 登记一个「已调度但可能还没跑过」的任务：让管理端在首次运行前也能看到 nextRunAt。
     * 已有运行快照时不覆盖，只更新下次执行时间。
     */
    public void registerScheduled(String name, String nextRunAtIso) {
        nextRuns.put(name, nextRunAtIso);
        latest.computeIfAbsent(name, key ->
                snapshot(key, STATUS_PENDING, null, null, 0L, 0L, 0L, null));
    }

    /** 最近一次运行快照（合并 nextRunAt），按任务名排序（稳定，便于后台展示）。 */
    public List<JobSnapshot> snapshot() {
        return latest.values().stream()
                .map(snap -> new JobSnapshot(snap.name(), snap.status(), snap.startedAt(), snap.finishedAt(),
                        snap.durationMs(), snap.processed(), snap.errors(), snap.detail(),
                        nextRuns.get(snap.name())))
                .sorted(Comparator.comparing(JobSnapshot::name))
                .toList();
    }

    private JobSnapshot snapshot(String name, String status, String startedAt, String finishedAt,
                                 long durationMs, long processed, long errors, String detail) {
        return new JobSnapshot(name, status, startedAt, finishedAt, durationMs, processed, errors, detail,
                nextRuns.get(name));
    }
}
