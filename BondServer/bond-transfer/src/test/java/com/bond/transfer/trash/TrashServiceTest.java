package com.bond.transfer.trash;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bond.common.entity.WorkspaceFile;
import com.bond.transfer.mapper.DocumentMapper;
import com.bond.transfer.mapper.OpsMapper;
import com.bond.transfer.mapper.ShareLinkMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.ops.OpsJobRegistry;
import com.bond.transfer.preview.ThumbnailService;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceService;
import com.bond.transfer.storage.ObjectStorage;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回收站服务回归（task-37 建立、task-88 改为「方案 B：回收站占配额」）。
 *
 * <p>B 语义：软删不释放配额、恢复不占配额、物理清理时按实际对象字节一次性精确释放。</p>
 */
@ExtendWith(MockitoExtension.class)
class TrashServiceTest {

    private static final Long USER = 1L;
    private static final Long WS = 5L;

    @Mock
    private WorkspaceFileMapper fileMapper;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private WorkspaceMapper workspaceMapper;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private PersonalSpaceService personalSpaceService;
    @Mock
    private ShareLinkMapper shareLinkMapper;
    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private ThumbnailService thumbnailService;
    @Mock
    private ConfigService configService;
    @Mock
    private OpsMapper opsMapper;
    @Mock
    private OpsJobRegistry opsJobRegistry;
    @Mock
    private PlatformTransactionManager transactionManager;
    @InjectMocks
    private TrashService trashService;

    @BeforeAll
    static void initLambdaCache() {
        // 纯单测里没有 MyBatis 启动流程，LambdaUpdateWrapper 需要手动初始化 TableInfo 缓存
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test-lambda-cache");
        TableInfoHelper.initTableInfo(assistant, WorkspaceFile.class);
    }

    private static WorkspaceFile trashed(Long id, String parent, String name, long size, boolean dir) {
        WorkspaceFile f = new WorkspaceFile();
        f.setId(id);
        f.setWorkspaceId(WS);
        f.setParentPath(parent);
        f.setFileName(name);
        f.setFileSize(size);
        f.setIsDirectory(dir);
        f.setMinioPath("workspaces/5/x/" + name);
        f.setContentType("application/pdf");
        f.setDeletedAt(OffsetDateTime.now().minusDays(1));
        f.setTrashExpiresAt(OffsetDateTime.now().plusDays(29));
        return f;
    }

    // ── restore ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("恢复：清空 deleted_at/trash_expires_at，并重新占用配额（2026-10 起取消方案 B）")
    void restoreFileClearsTrashAndReservesQuota() {
        when(fileMapper.selectById(9L)).thenReturn(trashed(9L, "/", "a.pdf", 10L, false));

        trashService.restore(USER, List.of(9L));

        verify(workspaceService).reserveSpace(WS, 10L);
        LambdaUpdateWrapper<WorkspaceFile> wrapper = captureUpdate();
        assertTrue(wrapper.getSqlSet().contains("deleted_at"));
        assertTrue(wrapper.getSqlSet().contains("trash_expires_at"));
    }

    @Test
    @DisplayName("恢复：原父目录已删除 → 回根目录")
    void restoreFallsBackToRootWhenParentMissing() {
        when(fileMapper.selectById(9L)).thenReturn(trashed(9L, "/gone", "a.pdf", 10L, false));

        trashService.restore(USER, List.of(9L));

        LambdaUpdateWrapper<WorkspaceFile> wrapper = captureUpdate();
        assertTrue(wrapper.getSqlSet().contains("deleted_at"));
        verify(workspaceService).reserveSpace(WS, 10L);
    }

    @Test
    @DisplayName("恢复：同名文件已占用 → 自动改名并搬运对象 key（并重新占用配额）")
    void restoreRenamesWhenNameConflict() {
        when(fileMapper.selectById(9L)).thenReturn(trashed(9L, "/", "a.pdf", 10L, false));
        when(fileMapper.selectCount(any())).thenReturn(1L, 0L);
        when(objectStorage.getObject("workspaces/5/x/a.pdf"))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        trashService.restore(USER, List.of(9L));

        verify(objectStorage).putObject(eq("workspaces/5/x/a (已恢复).pdf"), any(), eq(10L), any());
        verify(workspaceService).reserveSpace(WS, 10L);
    }

    @Test
    @DisplayName("恢复：不在回收站的项 → 404（批量接口记为失败明细）")
    void restoreRejectsMissingOrNotTrashed() {
        WorkspaceFile active = trashed(9L, "/", "a.pdf", 10L, false);
        active.setDeletedAt(null);
        when(fileMapper.selectById(9L)).thenReturn(active);

        TrashBatchResult result = trashService.restore(USER, List.of(9L));

        assertEquals(1, result.failed().size());
        assertTrue(result.failed().get(0).reason().contains("不存在"));
        verify(fileMapper, never()).update(isNull(), any());
    }

    // ── purge ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("物理清理单文件：删对象 + 删行（配额在删除时已释放，此处不再释放）")
    void purgeDeletesObjectWithoutReleasingBytes() {
        when(fileMapper.selectById(9L)).thenReturn(trashed(9L, "/", "a.pdf", 10L, false));
        when(objectStorage.objectExists("workspaces/5/x/a.pdf")).thenReturn(true);

        trashService.purge(USER, List.of(9L));

        verify(objectStorage).deleteObject("workspaces/5/x/a.pdf");
        verify(fileMapper).deleteBatchIds(List.of(9L));
        verify(workspaceService, never()).reserveSpace(any(), anyLong());
        verify(thumbnailService).evictFile(9L);
    }

    @Test
    @DisplayName("物理清理目录：目录本身不计入，仅删文件后代（不再释放配额）")
    void purgeDirectoryDeletesFileDescendantsOnly() {
        WorkspaceFile dir = trashed(10L, "/", "docs", 0L, true);
        WorkspaceFile file = trashed(11L, "/docs", "a.pdf", 30L, false);
        when(fileMapper.selectById(10L)).thenReturn(dir);
        when(fileMapper.selectList(any())).thenReturn(List.of(file));
        when(objectStorage.objectExists(any())).thenReturn(true);

        trashService.purge(USER, List.of(10L));

        verify(workspaceService, never()).reserveSpace(any(), anyLong());
        verify(fileMapper).deleteBatchIds(List.of(10L, 11L));
    }

    @Test
    @DisplayName("blob 已不存在时仍可清理（幂等：缺失记 blobMissing，不报错）")
    void purgeToleratesMissingBlob() {
        when(fileMapper.selectById(9L)).thenReturn(trashed(9L, "/", "a.pdf", 10L, false));
        when(objectStorage.objectExists(any())).thenReturn(false);

        trashService.purge(USER, List.of(9L));

        verify(objectStorage, never()).deleteObject(any());
        verify(workspaceService, never()).reserveSpace(any(), anyLong());
    }

    // ── empty ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("清空回收站：逐个删除但不再释放配额")
    void emptyPurgesAllTrashedRows() {
        WorkspaceFile a = trashed(1L, "/", "a.pdf", 10L, false);
        WorkspaceFile b = trashed(2L, "/", "b.pdf", 20L, false);
        when(fileMapper.selectList(any())).thenReturn(List.of(a, b));
        when(documentMapper.selectList(any())).thenReturn(List.of());
        when(objectStorage.objectExists(any())).thenReturn(true);

        trashService.empty(USER, WS);

        verify(fileMapper, times(2)).deleteBatchIds(anyList());
        verify(workspaceService, never()).reserveSpace(any(), anyLong());
    }

    // ── scheduled cleanup ────────────────────────────────────────────────

    @Test
    @DisplayName("定时清理：租约 + 批处理 + 统计（不再释放配额）")
    void cleanupExpiredPurgesBatch() {
        WorkspaceFile root = trashed(9L, "/", "a.pdf", 10L, false);
        when(opsMapper.tryAdvisoryLock(anyLong())).thenReturn(true);
        when(configService.getInt(anyString(), anyInt())).thenReturn(200);
        when(fileMapper.selectList(any())).thenReturn(List.of(root), List.of());
        when(objectStorage.objectExists(any())).thenReturn(true);

        OpsJobRegistry.JobResult result = trashService.cleanupExpired();

        assertEquals(1L, result.processed());
        verify(fileMapper).deleteBatchIds(List.of(9L));
        verify(workspaceService, never()).reserveSpace(any(), anyLong());
        verify(opsMapper).advisoryUnlock(anyLong());
    }

    @Test
    @DisplayName("定时清理：单个根失败不中断其它根（部分失败计入统计）")
    void cleanupCountsFailuresAndContinues() {
        WorkspaceFile first = trashed(11L, "/", "a.pdf", 10L, false);
        WorkspaceFile second = trashed(12L, "/", "b.pdf", 20L, false);
        when(opsMapper.tryAdvisoryLock(anyLong())).thenReturn(true);
        when(configService.getInt(anyString(), anyInt())).thenReturn(200);
        when(fileMapper.selectList(any())).thenReturn(List.of(first, second), List.of());
        doThrow(new IllegalStateException("db down")).doReturn(0)
                .when(fileMapper).deleteBatchIds(anyList());
        when(objectStorage.objectExists(any())).thenReturn(true);

        OpsJobRegistry.JobResult result = trashService.cleanupExpired();

        assertEquals(1L, result.errors());
        verify(fileMapper, times(2)).deleteBatchIds(anyList());
        verify(workspaceService, never()).reserveSpace(any(), anyLong());
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<WorkspaceFile> captureUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<WorkspaceFile>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(fileMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }
}
