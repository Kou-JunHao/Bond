package com.bond.transfer.ops;

import com.bond.transfer.mapper.OpsMapper;
import com.bond.transfer.mapper.ShareLinkMapper;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.UploadSessionService;
import com.bond.transfer.trash.TrashService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * task-157：过期数据统一清理的编排测试（各分类用 mock 隔离，不连 DB/RustFS）。
 */
@ExtendWith(MockitoExtension.class)
class ExpiryCleanupServiceTest {

    @Mock
    private TrashService trashService;
    @Mock
    private ShareLinkMapper shareLinkMapper;
    @Mock
    private UploadSessionService uploadSessionService;
    @Mock
    private OpsMapper opsMapper;
    @Mock
    private ConfigService configService;
    @Mock
    private OpsJobRegistry opsJobRegistry;

    private ExpiryCleanupService service;

    @BeforeEach
    void setUp() {
        service = new ExpiryCleanupService(trashService, shareLinkMapper, uploadSessionService,
                opsMapper, configService, opsJobRegistry);

        lenient().when(configService.getBoolean("ops.cleanup.enabled", true)).thenReturn(true);
        lenient().when(configService.getInt("ops.cleanup.batch", 200)).thenReturn(200);
        lenient().when(configService.getInt("ops.cleanup.max_batches", 50)).thenReturn(50);
        lenient().when(configService.getBoolean("ops.trash_gc.enabled", true)).thenReturn(true);
        lenient().when(configService.getBoolean("ops.share_expiry.enabled", true)).thenReturn(true);
        lenient().when(configService.getBoolean("ops.upload_session_gc.enabled", true)).thenReturn(true);
        lenient().when(configService.getBoolean("ops.audit_log_gc.enabled", true)).thenReturn(true);
        lenient().when(configService.getInt("ops.audit_log_retention_days", 90)).thenReturn(90);

        lenient().when(trashService.cleanupExpired())
                .thenReturn(new OpsJobRegistry.JobResult(3L, 0L, "rounds=1"));
        lenient().when(shareLinkMapper.markExpired(any(OffsetDateTime.class))).thenReturn(2);
        lenient().when(uploadSessionService.cleanupExpired(anyInt()))
                .thenReturn(new UploadSessionService.CleanupResult(4, 0, 1));
        lenient().when(opsMapper.deleteOperationLogsBefore(any(OffsetDateTime.class), anyInt())).thenReturn(0);
        lenient().when(opsMapper.deleteLoginLogsBefore(any(OffsetDateTime.class), anyInt())).thenReturn(0);
    }

    @Test
    @DisplayName("四类清理全部执行：processed = 物理删除 + 标记 + 会话 + 审计")
    void runsAllCategoriesAndAggregates() {
        when(opsMapper.deleteOperationLogsBefore(any(OffsetDateTime.class), eq(200))).thenReturn(5);
        when(opsMapper.deleteLoginLogsBefore(any(OffsetDateTime.class), eq(200))).thenReturn(1);

        OpsJobRegistry.JobResult result = service.cleanup();

        // trash 3 + share 2 + uploads 4 + audit (5+1)
        assertEquals(15L, result.processed());
        assertEquals(0L, result.errors());
        assertTrue(result.detail().contains("trash=3"));
        assertTrue(result.detail().contains("shareExpired=2"));
        assertTrue(result.detail().contains("uploads=4"));
        assertTrue(result.detail().contains("auditLogs=6"));
    }

    @Test
    @DisplayName("总开关/分类开关关闭时不触碰对应依赖")
    void disabledSwitchesSkipCategories() {
        when(configService.getBoolean("ops.trash_gc.enabled", true)).thenReturn(false);
        when(configService.getBoolean("ops.share_expiry.enabled", true)).thenReturn(false);
        when(configService.getBoolean("ops.upload_session_gc.enabled", true)).thenReturn(false);
        when(configService.getBoolean("ops.audit_log_gc.enabled", true)).thenReturn(false);

        OpsJobRegistry.JobResult result = service.cleanup();

        verifyNoInteractions(trashService, shareLinkMapper, uploadSessionService);
        verifyNoInteractions(opsMapper);
        assertEquals(0L, result.processed());
        assertEquals(0L, result.errors());
    }

    @Test
    @DisplayName("单分类异常不阻断其它分类，errors 计数")
    void categoryFailureDoesNotBlockOthers() {
        when(trashService.cleanupExpired()).thenThrow(new IllegalStateException("db down"));

        OpsJobRegistry.JobResult result = service.cleanup();

        verify(shareLinkMapper).markExpired(any(OffsetDateTime.class));
        verify(uploadSessionService).cleanupExpired(50);
        assertEquals(1L, result.errors());
        assertTrue(result.detail().contains("trash=error"));
        // share 2 + uploads 4
        assertEquals(6L, result.processed());
    }

    @Test
    @DisplayName("审计日志分批：200 行 + 1 行 = 两批 201 行后停止")
    void auditBatchStopsWhenBatchNotFull() {
        when(opsMapper.deleteOperationLogsBefore(any(OffsetDateTime.class), eq(200))).thenReturn(200, 1);
        when(opsMapper.deleteLoginLogsBefore(any(OffsetDateTime.class), eq(200))).thenReturn(0);

        OpsJobRegistry.JobResult result = service.cleanup();

        verify(opsMapper, times(2)).deleteOperationLogsBefore(any(OffsetDateTime.class), eq(200));
        verify(opsMapper, times(1)).deleteLoginLogsBefore(any(OffsetDateTime.class), eq(200));
        assertTrue(result.detail().contains("auditLogs=201"));
    }

    @Test
    @DisplayName("max_batches 上限生效：每批 10 行、上限 2 批 → 只跑 2 轮")
    void maxBatchesCapsAuditLoop() {
        when(configService.getInt("ops.cleanup.batch", 200)).thenReturn(10);
        when(configService.getInt("ops.cleanup.max_batches", 50)).thenReturn(2);
        when(opsMapper.deleteOperationLogsBefore(any(OffsetDateTime.class), eq(10))).thenReturn(10);
        when(opsMapper.deleteLoginLogsBefore(any(OffsetDateTime.class), eq(10))).thenReturn(0);

        OpsJobRegistry.JobResult result = service.cleanup();

        verify(opsMapper, times(2)).deleteOperationLogsBefore(any(OffsetDateTime.class), eq(10));
        assertTrue(result.detail().contains("auditLogs=20"));
    }

    @Test
    @DisplayName("可重入：第二次运行各来源返回 0 → processed=0（不重复删除/标记）")
    void secondRunIsNoOp() {
        service.cleanup();

        when(trashService.cleanupExpired()).thenReturn(new OpsJobRegistry.JobResult(0L, 0L, "rounds=0"));
        when(shareLinkMapper.markExpired(any(OffsetDateTime.class))).thenReturn(0);
        when(uploadSessionService.cleanupExpired(anyInt()))
                .thenReturn(new UploadSessionService.CleanupResult(0, 0, 0));

        OpsJobRegistry.JobResult second = service.cleanup();

        assertEquals(0L, second.processed());
    }

    @Test
    @DisplayName("审计保留天数：cutoff = now - 90 天（±2 秒）")
    void auditCutoffUsesConfiguredRetentionDays() {
        when(opsMapper.deleteOperationLogsBefore(any(OffsetDateTime.class), anyInt())).thenReturn(0);
        when(opsMapper.deleteLoginLogsBefore(any(OffsetDateTime.class), anyInt())).thenReturn(0);

        service.cleanup();

        ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(opsMapper).deleteOperationLogsBefore(captor.capture(), anyInt());
        OffsetDateTime expected = OffsetDateTime.now().minusDays(90);
        assertTrue(Duration.between(expected, captor.getValue()).abs().toSeconds() <= 2);
    }

    @Test
    @DisplayName("scheduledRun：总开关关闭 → 不注册 job、不执行任何清理")
    void scheduledRunSkipsWhenMasterSwitchDisabled() {
        when(configService.getBoolean("ops.cleanup.enabled", true)).thenReturn(false);

        service.scheduledRun();

        verifyNoInteractions(opsJobRegistry);
        verifyNoInteractions(trashService, shareLinkMapper, uploadSessionService);
    }

    @Test
    @DisplayName("scheduledRun：开关打开 → 以 expiry-cleanup 名称注册并执行")
    @SuppressWarnings("unchecked")
    void scheduledRunRegistersJob() {
        when(opsJobRegistry.run(eq(ExpiryCleanupService.JOB_NAME), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<OpsJobRegistry.JobResult>) invocation.getArgument(1)).get());

        service.scheduledRun();

        verify(opsJobRegistry).run(eq(ExpiryCleanupService.JOB_NAME), any(Supplier.class));
        verify(trashService).cleanupExpired();
    }

    @Test
    @DisplayName("deleteInBatches：不足一批立即停止（200 批 → 201 行两批）")
    void deleteInBatchesBoundary() {
        int[] sequence = {200, 1};
        int[] calls = {0};
        IntUnaryOperator deleteCall = limit -> sequence[Math.min(calls[0]++, sequence.length - 1)];

        int total = ExpiryCleanupService.deleteInBatches(200, 50, deleteCall);
        assertEquals(201, total);
        assertEquals(2, calls[0]);
    }

    @Test
    @DisplayName("deleteInBatches：空表零调用一次即停")
    void deleteInBatchesEmptyTable() {
        int[] calls = {0};
        int total = ExpiryCleanupService.deleteInBatches(200, 50, ignored -> {
            calls[0]++;
            return 0;
        });
        assertEquals(0, total);
        assertEquals(1, calls[0]);
        verify(uploadSessionService, never()).cleanupExpired(anyInt());
    }
}
