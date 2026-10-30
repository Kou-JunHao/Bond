package com.bond.transfer.controller;

import com.bond.common.dto.Result;
import com.bond.transfer.audit.AdminAudit;
import com.bond.transfer.service.UsageAuditService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运维维护端点（task-162）：工作区 {@code used_size} 对账。
 *
 * <p>全部在 {@code /api/admin/**} 下，由 {@code AdminInterceptor} 保护（无 token 401、非管理员 403），
 * 写操作带 {@code @AdminAudit}。</p>
 */
@RestController
@RequestMapping("/api/admin/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {

    private final UsageAuditService usageAuditService;

    /**
     * 只读对账（dry-run）：返回每个工作区 used_size vs 有效存储的 diff。
     *
     * @param workspaceIds 可选；不传 = 前 500 个（truncated 标记是否截断）
     */
    @GetMapping("/usage-audit")
    public Result<UsageAuditService.AuditSummary> usageAudit(
            @RequestParam(value = "workspaceIds", required = false) List<Long> workspaceIds) {
        return Result.ok(usageAuditService.audit(workspaceIds));
    }

    /**
     * 对账并写回（body：{@code workspaceIds} 可为 null=前 500 个；{@code dryRun} 缺省 true）。
     */
    @AdminAudit(action = "maintenance.usage-reconcile", targetType = "workspace")
    @PostMapping("/usage-reconcile")
    public Result<UsageAuditService.ReconcileSummary> reconcile(@RequestBody ReconcileRequest req) {
        boolean dryRun = req == null || req.getDryRun() == null || Boolean.TRUE.equals(req.getDryRun());
        List<Long> ids = req == null ? null : req.getWorkspaceIds();
        return Result.ok(usageAuditService.reconcile(ids, dryRun));
    }

    @Data
    public static class ReconcileRequest {
        /** 可选；不传 = 前 500 个空间。 */
        private List<Long> workspaceIds;
        /** true（默认）= 只报告不写库；false = 写回 used_size。 */
        private Boolean dryRun;
    }
}
