package com.bond.transfer.service;

import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.WorkspaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作区配额对账（task-162）：把 {@code workspaces.used_size} 与「有效存储」重算比对，
 * 支持 dry-run 与写回修复。
 *
 * <p><b>口径（与删除/恢复作业一致）</b>：
 * {@code 有效存储 = SUM(workspace_files.file_size WHERE deleted_at IS NULL AND is_directory = FALSE)
 *           + SUM(documents.size_bytes WHERE deleted_at IS NULL)}。
 * 回收站行（deleted_at 非空）在 task-162 起**不计入配额**（删除时已释放），因此不在有效存储里。
 * 目录行 file_size 恒 0，恒不计。</p>
 *
 * <p>历史数据：task-88 时期「回收站占配额」，本单切到「删除即释放」后，老回收站行会表现为
 * {@code used_size > stored}（diff 为正）——用本服务 reconcile 一次性对平即可。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageAuditService {

    /** 单次请求最多处理的 workspace 数（防一次扫全库/写全库）。 */
    public static final int MAX_WORKSPACES = 500;

    private final WorkspaceMapper workspaceMapper;

    /** 对账项：usedSize=库中值，stored=文件+文档，diff=usedSize-stored（>0 偏大，<0 偏小）。 */
    public record Item(Long workspaceId, Long ownerId, String name, long usedSize,
                       long fileBytes, long documentBytes, long storedBytes, long diff) {
    }

    public record AuditSummary(int checked, int dirty, long totalDiff, boolean truncated, List<Item> items) {
    }

    public record ReconcileSummary(boolean dryRun, int checked, int dirty, int fixed,
                                   long totalBefore, long totalAfter, List<Item> items) {
    }

    /** 只读对账（不改库）。 */
    public AuditSummary audit(List<Long> workspaceIds) {
        List<Item> items = scan(workspaceIds);
        long totalDiff = 0L;
        int dirty = 0;
        for (Item item : items) {
            totalDiff += item.diff();
            if (item.diff() != 0) {
                dirty++;
            }
        }
        return new AuditSummary(items.size(), dirty, totalDiff, items.size() >= MAX_WORKSPACES, items);
    }

    /**
     * 对账并（可选）写回：{@code dryRun=true} 时只报告，不写库；写回把 {@code used_size}
     * 精确设为 stored，幂等（重复执行 diff=0，不再产生写）。
     */
    public ReconcileSummary reconcile(List<Long> workspaceIds, boolean dryRun) {
        List<Item> items = scan(workspaceIds);
        long before = 0L;
        long after = 0L;
        int dirty = 0;
        int fixed = 0;
        for (Item item : items) {
            before += item.usedSize();
            after += item.storedBytes();
            if (item.diff() == 0) {
                continue;
            }
            dirty++;
            if (!dryRun) {
                workspaceMapper.setUsedSize(item.workspaceId(), item.storedBytes());
                fixed++;
            }
        }
        if (!dryRun) {
            log.info("usage reconcile done: checked={}, dirty={}, fixed={}, totalBefore={}, totalAfter={}",
                    items.size(), dirty, fixed, before, after);
        }
        return new ReconcileSummary(dryRun, items.size(), dirty, fixed, before, after, items);
    }

    private List<Item> scan(List<Long> workspaceIds) {
        if (workspaceIds != null) {
            workspaceIds = workspaceIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
            if (workspaceIds.size() > MAX_WORKSPACES) {
                throw new BusinessException(400, "单次最多对账 " + MAX_WORKSPACES + " 个工作区");
            }
            if (workspaceIds.isEmpty()) {
                return List.of();
            }
        }
        List<UsageAuditRow> rows = workspaceMapper.selectUsageAudit(workspaceIds, MAX_WORKSPACES);
        List<Item> items = new ArrayList<>(rows.size());
        for (UsageAuditRow row : rows) {
            long files = Math.max(0L, row.getFileBytes());
            long docs = Math.max(0L, row.getDocumentBytes());
            long stored = files + docs;
            long used = Math.max(0L, row.getUsedSize());
            items.add(new Item(row.getWorkspaceId(), row.getOwnerId(), row.getName(),
                    used, files, docs, stored, used - stored));
        }
        return items;
    }
}
