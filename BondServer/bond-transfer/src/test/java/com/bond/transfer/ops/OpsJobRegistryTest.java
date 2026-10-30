package com.bond.transfer.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** task-74 C：运维任务运行记录（内存快照）。 */
class OpsJobRegistryTest {

    @Test
    @DisplayName("成功任务：记录 COMPLETED + processed/errors/detail")
    void recordsCompletedJob() {
        OpsJobRegistry registry = new OpsJobRegistry();
        OpsJobRegistry.JobResult result = registry.run("demo", () -> new OpsJobRegistry.JobResult(7, 0, "ok"));

        assertEquals(7, result.processed());
        OpsJobRegistry.JobSnapshot snapshot = registry.snapshot().get(0);
        assertEquals("demo", snapshot.name());
        assertEquals("COMPLETED", snapshot.status());
        assertEquals(7, snapshot.processed());
        assertEquals(0, snapshot.errors());
        assertTrue(snapshot.detail().contains("ok"));
        assertTrue(snapshot.startedAt() != null && snapshot.finishedAt() != null);
    }

    @Test
    @DisplayName("部分失败：COMPLETED_WITH_ERRORS 且错误数保留")
    void recordsCompletedWithErrors() {
        OpsJobRegistry registry = new OpsJobRegistry();
        registry.run("demo", () -> new OpsJobRegistry.JobResult(3, 2, "partial"));

        OpsJobRegistry.JobSnapshot snapshot = registry.snapshot().get(0);
        assertEquals("COMPLETED_WITH_ERRORS", snapshot.status());
        assertEquals(2, snapshot.errors());
    }

    @Test
    @DisplayName("抛异常：FAILED 快照，不向定时任务外抛")
    void recordsFailedJob() {
        OpsJobRegistry registry = new OpsJobRegistry();
        OpsJobRegistry.JobResult result = registry.run("demo", () -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(1, result.errors());
        assertEquals("FAILED", registry.snapshot().get(0).status());
        assertTrue(registry.snapshot().get(0).detail().contains("boom"));
    }

    @Test
    @DisplayName("任务返回 null 视为空结果")
    void nullResultTreatedAsEmpty() {
        OpsJobRegistry registry = new OpsJobRegistry();
        OpsJobRegistry.JobResult result = registry.run("demo", () -> null);

        assertEquals(0, result.processed());
        assertEquals(0, result.errors());
    }

    @Test
    @DisplayName("快照按任务名排序且只保留最近一次")
    void snapshotSortedAndLatestWins() {
        OpsJobRegistry registry = new OpsJobRegistry();
        AtomicInteger first = new AtomicInteger(1);
        registry.run("zeta", () -> new OpsJobRegistry.JobResult(first.get(), 0, ""));
        registry.run("alpha", () -> new OpsJobRegistry.JobResult(9, 0, ""));
        registry.run("zeta", () -> new OpsJobRegistry.JobResult(2, 0, ""));

        List<OpsJobRegistry.JobSnapshot> snapshots = registry.snapshot();
        assertEquals(2, snapshots.size());
        assertEquals("alpha", snapshots.get(0).name());
        assertEquals("zeta", snapshots.get(1).name());
        assertEquals(2, snapshots.get(1).processed());
    }
}
