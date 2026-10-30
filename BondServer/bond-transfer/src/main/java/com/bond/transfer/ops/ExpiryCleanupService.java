package com.bond.transfer.ops;

import com.bond.transfer.mapper.OpsMapper;
import com.bond.transfer.mapper.ShareLinkMapper;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.UploadSessionService;
import com.bond.transfer.trash.TrashService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 过期数据统一清理（task-157）。
 *
 * <p>背景：回收站有 {@code trash_expires_at}、分享有 {@code expires_at}、上传会话有
 * {@code expires_at}，过去只靠「读时过滤」，磁盘与配额永不释放；本服务每天一次把它们
 * 真正落地：</p>
 * <ul>
 *   <li>回收站：物理删除到期项（递归删对象 + 原子归还配额），保留期 0（不限期）写入 NULL 永不清理，
 *       且以**删除时档位**为准、升级不回溯；</li>
 *   <li>分享：{@code status=0 → 2}（已过期）标记，不删行（审计需要），让管理端「已过期」筛选准确；</li>
 *   <li>上传会话：未完成且超 24h 的会话 abort multipart + 删行，已完成会话历史不动；</li>
 *   <li>审计日志：{@code admin_operation_logs} / {@code admin_login_logs} 保留 90 天，分批删除。</li>
 * </ul>
 *
 * <p>可观测：每次运行通过 {@link OpsJobRegistry} 暴露 processed/errors/detail，管理端
 * {@code GET /api/admin/ops/jobs} 可见；开关来自 {@code system_config} 的 {@code ops.*} 键，
 * 关掉 {@code ops.cleanup.enabled} 即整体暂停（回滚方式）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpiryCleanupService {

    public static final String JOB_NAME = "expiry-cleanup";

    private static final int MAX_BATCH = 500;
    private static final int MAX_BATCHES = 100;

    private final TrashService trashService;
    private final ShareLinkMapper shareLinkMapper;
    private final UploadSessionService uploadSessionService;
    private final OpsMapper opsMapper;
    private final ConfigService configService;
    private final OpsJobRegistry opsJobRegistry;

    /** 调度入口：总开关关闭时直接跳过（不计入 job 快照，避免误导为「跑过且成功」）。 */
    public void scheduledRun() {
        if (!configService.getBoolean("ops.cleanup.enabled", true)) {
            log.debug("expiry cleanup skipped: ops.cleanup.enabled=false");
            return;
        }
        opsJobRegistry.run(JOB_NAME, this::cleanup);
    }

    /**
     * 执行一轮完整清理（各分类独立 try/catch：一类失败不阻断其它类，计入 errors）。
     *
     * @return 汇总结果；{@code processed} 为实际处理行数（物理删除 + 标记 + 会话删除 + 审计删除）
     */
    public OpsJobRegistry.JobResult cleanup() {
        OffsetDateTime now = OffsetDateTime.now();
        int batch = clamp(configService.getInt("ops.cleanup.batch", 200), 1, MAX_BATCH);
        int maxBatches = clamp(configService.getInt("ops.cleanup.max_batches", 50), 1, MAX_BATCHES);

        long processed = 0L;
        long errors = 0L;
        List<String> parts = new ArrayList<>();

        if (configService.getBoolean("ops.trash_gc.enabled", true)) {
            try {
                OpsJobRegistry.JobResult trash = trashService.cleanupExpired();
                processed += trash.processed();
                errors += trash.errors();
                parts.add("trash=" + trash.processed() + "(" + trash.detail() + ")");
            } catch (RuntimeException e) {
                errors++;
                log.warn("expiry cleanup: trash gc failed: {}", e.toString());
                parts.add("trash=error");
            }
        }

        if (configService.getBoolean("ops.share_expiry.enabled", true)) {
            try {
                int marked = shareLinkMapper.markExpired(now);
                processed += marked;
                parts.add("shareExpired=" + marked);
            } catch (RuntimeException e) {
                errors++;
                log.warn("expiry cleanup: share expiry marking failed: {}", e.toString());
                parts.add("shareExpired=error");
            }
        }

        if (configService.getBoolean("ops.upload_session_gc.enabled", true)) {
            try {
                UploadSessionService.CleanupResult result = uploadSessionService.cleanupExpired(maxBatches);
                processed += result.deleted();
                errors += result.failed();
                parts.add("uploads=" + result.deleted() + "(failed=" + result.failed()
                        + ",rounds=" + result.rounds() + ")");
            } catch (RuntimeException e) {
                errors++;
                log.warn("expiry cleanup: upload session gc failed: {}", e.toString());
                parts.add("uploads=error");
            }
        }

        if (configService.getBoolean("ops.audit_log_gc.enabled", true)) {
            try {
                int days = clamp(configService.getInt("ops.audit_log_retention_days", 90), 1, 3650);
                OffsetDateTime cutoff = now.minusDays(days);
                int opLogs = deleteInBatches(batch, maxBatches,
                        limit -> opsMapper.deleteOperationLogsBefore(cutoff, limit));
                int loginLogs = deleteInBatches(batch, maxBatches,
                        limit -> opsMapper.deleteLoginLogsBefore(cutoff, limit));
                processed += (long) opLogs + loginLogs;
                parts.add("auditLogs=" + (opLogs + loginLogs) + "(days=" + days + ")");
            } catch (RuntimeException e) {
                errors++;
                log.warn("expiry cleanup: audit log retention failed: {}", e.toString());
                parts.add("auditLogs=error");
            }
        }

        String detail = String.join(",", parts);
        log.info("expiry cleanup done: processed={}, errors={}, {}", processed, errors, detail);
        return new OpsJobRegistry.JobResult(processed, errors, detail);
    }

    /** 分批删除直到某批不足 batch（或达到 maxBatches 上限）。 */
    static int deleteInBatches(int batch, int maxBatches, java.util.function.IntUnaryOperator deleteCall) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int deleted = deleteCall.applyAsInt(batch);
            total += deleted;
            if (deleted < batch) {
                break;
            }
        }
        return total;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
