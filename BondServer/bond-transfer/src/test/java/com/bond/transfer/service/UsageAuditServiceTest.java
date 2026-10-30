package com.bond.transfer.service;

import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.WorkspaceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * task-162：used_size 对账的纯逻辑测试（不连库；SQL 由 {@link WorkspaceMapper} 单测守护）。
 */
@ExtendWith(MockitoExtension.class)
class UsageAuditServiceTest {

    @Mock
    private WorkspaceMapper workspaceMapper;

    @InjectMocks
    private UsageAuditService service;

    private static UsageAuditRow row(long id, long used, long files, long docs) {
        UsageAuditRow r = new UsageAuditRow();
        r.setWorkspaceId(id);
        r.setOwnerId(100L + id);
        r.setName("ws-" + id);
        r.setUsedSize(used);
        r.setFileBytes(files);
        r.setDocumentBytes(docs);
        return r;
    }

    @Test
    @DisplayName("audit：diff = used_size - (文件+文档) 字节，dirty 统计非 0 项")
    void auditComputesDiff() {
        when(workspaceMapper.selectUsageAudit(any(), anyInt()))
                .thenReturn(List.of(row(1L, 100L, 40L, 10L), row(2L, 7L, 7L, 0L)));

        UsageAuditService.AuditSummary summary = service.audit(null);

        assertEquals(2, summary.checked());
        assertEquals(1, summary.dirty());
        assertEquals(50L, summary.totalDiff());
        UsageAuditService.Item first = summary.items().get(0);
        assertEquals(100L, first.usedSize());
        assertEquals(50L, first.storedBytes());
        assertEquals(50L, first.diff());
        assertEquals(0L, summary.items().get(1).diff());
        assertFalse(summary.truncated());
    }

    @Test
    @DisplayName("audit：空结果/空 id 列表不写库、不报错")
    void auditEmpty() {
        when(workspaceMapper.selectUsageAudit(any(), anyInt())).thenReturn(List.of());

        UsageAuditService.AuditSummary summary = service.audit(null);

        assertEquals(0, summary.checked());
        assertEquals(0L, summary.totalDiff());
    }

    @Test
    @DisplayName("audit：workspaceIds 空列表 → 直接返回，不查库")
    void auditWithEmptyIdListSkipsQuery() {
        UsageAuditService.AuditSummary summary = service.audit(List.of());

        assertEquals(0, summary.checked());
        verifyNoInteractions(workspaceMapper);
    }

    @Test
    @DisplayName("audit：超过 500 个工作区 → 400（防全库扫描）")
    void auditRejectsTooManyIds() {
        List<Long> ids = new ArrayList<>();
        for (long i = 0; i < UsageAuditService.MAX_WORKSPACES + 1; i++) {
            ids.add(i + 1);
        }

        BusinessException ex = assertThrows(BusinessException.class, () -> service.audit(ids));
        assertEquals(400, ex.getCode());
        verifyNoInteractions(workspaceMapper);
    }

    @Test
    @DisplayName("reconcile(dryRun=true)：只报告不写库")
    void reconcileDryRunDoesNotWrite() {
        when(workspaceMapper.selectUsageAudit(any(), anyInt()))
                .thenReturn(List.of(row(1L, 100L, 40L, 10L)));

        UsageAuditService.ReconcileSummary summary = service.reconcile(null, true);

        assertEquals(1, summary.checked());
        assertEquals(1, summary.dirty());
        assertEquals(0, summary.fixed());
        assertEquals(100L, summary.totalBefore());
        assertEquals(50L, summary.totalAfter());
        verify(workspaceMapper, never()).setUsedSize(anyLong(), anyLong());
    }

    @Test
    @DisplayName("reconcile(dryRun=false)：把 used_size 精确重算到 stored，返回前后总量")
    void reconcileWritesRecomputedSize() {
        when(workspaceMapper.selectUsageAudit(any(), anyInt()))
                .thenReturn(List.of(row(1L, 100L, 40L, 10L), row(2L, 7L, 7L, 0L)));

        UsageAuditService.ReconcileSummary summary = service.reconcile(null, false);

        assertEquals(2, summary.checked());
        assertEquals(1, summary.dirty());
        assertEquals(1, summary.fixed());
        assertEquals(107L, summary.totalBefore());
        assertEquals(57L, summary.totalAfter());
        verify(workspaceMapper, times(1)).setUsedSize(1L, 50L);
        verify(workspaceMapper, never()).setUsedSize(2L, 7L);
    }

    @Test
    @DisplayName("reconcile：diff=0 的工作区不产生写（幂等，重复执行无变化）")
    void reconcileIsIdempotent() {
        when(workspaceMapper.selectUsageAudit(any(), anyInt()))
                .thenReturn(List.of(row(3L, 5L, 5L, 0L)));

        UsageAuditService.ReconcileSummary summary = service.reconcile(null, false);

        assertEquals(0, summary.dirty());
        assertEquals(0, summary.fixed());
        verify(workspaceMapper, never()).setUsedSize(anyLong(), anyLong());
    }

    @Test
    @DisplayName("reconcile：传 workspaceIds 时按 id 查询并去重")
    void reconcileScopedByIds() {
        when(workspaceMapper.selectUsageAudit(anyList(), anyInt()))
                .thenReturn(List.of(row(9L, 0L, 0L, 0L)));

        UsageAuditService.ReconcileSummary summary =
                service.reconcile(java.util.Arrays.asList(9L, 9L, null), false);

        assertEquals(1, summary.checked());
        verify(workspaceMapper).selectUsageAudit(anyList(), anyInt());
    }
}
