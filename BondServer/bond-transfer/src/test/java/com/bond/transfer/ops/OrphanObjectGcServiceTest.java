package com.bond.transfer.ops;

import com.bond.transfer.mapper.OpsMapper;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** task-74 B：孤儿对象只报告 + 预览缓存 TTL 清理。 */
@ExtendWith(MockitoExtension.class)
class OrphanObjectGcServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private OpsMapper opsMapper;
    @Mock
    private ConfigService configService;
    @Mock
    private OpsJobRegistry opsJobRegistry;

    private OrphanObjectGcService service;

    @BeforeEach
    void setUp() {
        service = new OrphanObjectGcService(objectStorage, opsMapper, configService, opsJobRegistry);
        // 默认：无预览对象、无会话引用
        lenient().when(objectStorage.listObjects("files/")).thenReturn(List.of());
        lenient().when(objectStorage.listObjects("thumbnails/")).thenReturn(List.of());
        lenient().when(objectStorage.listObjects("previews/")).thenReturn(List.of());
        lenient().when(opsMapper.selectUploadSessionKeys()).thenReturn(List.of());
    }

    private void stubOrphanScan(boolean deleteEnabled, int minAgeHours, int maxDelete) {
        when(configService.getBoolean("gc.orphan.enabled", true)).thenReturn(true);
        when(configService.getBoolean("gc.orphan.delete", false)).thenReturn(deleteEnabled);
        when(configService.getInt("gc.orphan.min_age_hours", 24)).thenReturn(minAgeHours);
        when(configService.getInt("gc.orphan.max_delete_per_run", 500)).thenReturn(maxDelete);
    }

    private void stubPreviewScan(boolean enabled) {
        when(configService.getBoolean("gc.preview.enabled", true)).thenReturn(enabled);
        if (enabled) {
            when(configService.getInt("preview.cache_ttl_days", 30)).thenReturn(30);
        }
    }

    private static ObjectStorage.StoredObject object(String key, long size, Instant lastModified) {
        return new ObjectStorage.StoredObject(key, size, lastModified);
    }

    @Test
    @DisplayName("默认只报告：识别孤儿但不删除")
    void reportsOrphansWithoutDeleting() {
        stubOrphanScan(false, 24, 500);
        stubPreviewScan(true);
        when(objectStorage.listObjects("workspaces/")).thenReturn(List.of(
                object("workspaces/orphan-a", 100L, NOW.minus(3, ChronoUnit.DAYS)),
                object("workspaces/orphan-b", 50L, NOW.minus(2, ChronoUnit.DAYS)),
                object("workspaces/keep", 10L, NOW.minus(10, ChronoUnit.DAYS))));
        when(opsMapper.selectReferencedObjectKeys()).thenReturn(List.of("workspaces/keep"));

        OpsJobRegistry.JobResult result = service.collect(NOW);

        assertEquals(2, result.processed());
        assertEquals(0, result.errors());
        assertTrue(result.detail().contains("orphans=2"));
        assertTrue(result.detail().contains("orphanBytes=150"));
        verify(objectStorage, never()).deleteObject(anyString());
    }

    @Test
    @DisplayName("显式开启删除：只删超过 min-age 的对象")
    void deletesOldOrphansWhenEnabled() {
        stubOrphanScan(true, 24, 500);
        stubPreviewScan(true);
        when(objectStorage.listObjects("workspaces/")).thenReturn(List.of(
                object("workspaces/old", 100L, NOW.minus(2, ChronoUnit.DAYS)),
                object("workspaces/fresh", 100L, NOW.minus(1, ChronoUnit.HOURS))));
        when(opsMapper.selectReferencedObjectKeys()).thenReturn(List.of());

        OpsJobRegistry.JobResult result = service.collect(NOW);

        verify(objectStorage).deleteObject("workspaces/old");
        verify(objectStorage, never()).deleteObject("workspaces/fresh");
        assertEquals(2, result.processed());
        assertTrue(result.detail().contains("deletedOrphans=1"));
    }

    @Test
    @DisplayName("单轮删除上限生效")
    void respectsMaxDeletePerRun() {
        stubOrphanScan(true, 0, 1);
        stubPreviewScan(true);
        when(objectStorage.listObjects("workspaces/")).thenReturn(List.of(
                object("workspaces/a", 1L, NOW.minus(1, ChronoUnit.DAYS)),
                object("workspaces/b", 1L, NOW.minus(1, ChronoUnit.DAYS)),
                object("workspaces/c", 1L, NOW.minus(1, ChronoUnit.DAYS))));
        when(opsMapper.selectReferencedObjectKeys()).thenReturn(List.of());

        service.collect(NOW);

        verify(objectStorage, times(1)).deleteObject(anyString());
    }

    @Test
    @DisplayName("上传会话引用中的对象不算孤儿")
    void uploadSessionKeysAreReferenced() {
        stubOrphanScan(false, 24, 500);
        stubPreviewScan(true);
        when(objectStorage.listObjects("workspaces/")).thenReturn(List.of(
                object("workspaces/uploading", 1L, NOW.minus(1, ChronoUnit.DAYS))));
        when(opsMapper.selectReferencedObjectKeys()).thenReturn(List.of());
        when(opsMapper.selectUploadSessionKeys()).thenReturn(List.of("workspaces/uploading"));

        OpsJobRegistry.JobResult result = service.collect(NOW);

        assertEquals(0, result.processed());
    }

    @Test
    @DisplayName("预览缓存按 preview.cache_ttl_days 清理（thumbnails + previews）")
    void purgesExpiredPreviewCaches() {
        when(configService.getBoolean("gc.orphan.enabled", true)).thenReturn(false);
        stubPreviewScan(true);
        when(objectStorage.listObjects("thumbnails/")).thenReturn(List.of(
                object("thumbnails/1-320.jpg", 1L, NOW.minus(31, ChronoUnit.DAYS)),
                object("thumbnails/2-320.jpg", 1L, NOW.minus(3, ChronoUnit.DAYS))));
        when(objectStorage.listObjects("previews/")).thenReturn(List.of(
                object("previews/abc.pdf", 1L, NOW.minus(40, ChronoUnit.DAYS))));

        OpsJobRegistry.JobResult result = service.collect(NOW);

        verify(objectStorage, times(2)).deleteObject(anyString());
        assertEquals(2, result.processed());
        assertTrue(result.detail().contains("purgedPreview=2"));
    }

    @Test
    @DisplayName("列表失败：错误计数 +1，不中断其他前缀")
    void listFailureCountsError() {
        stubOrphanScan(false, 24, 500);
        stubPreviewScan(true);
        when(objectStorage.listObjects("workspaces/")).thenThrow(new IllegalStateException("s3 down"));
        when(objectStorage.listObjects("files/")).thenReturn(List.of(
                object("files/orphan", 1L, NOW.minus(3, ChronoUnit.DAYS))));
        when(opsMapper.selectReferencedObjectKeys()).thenReturn(List.of());

        OpsJobRegistry.JobResult result = service.collect(NOW);

        assertEquals(1, result.processed());
        assertEquals(1, result.errors());
        assertTrue(result.detail().contains("orphans=1"));
    }

    @Test
    @DisplayName("删除失败：错误计数 +1，继续后续对象")
    void deleteFailureCountsError() {
        stubOrphanScan(true, 0, 500);
        stubPreviewScan(true);
        when(objectStorage.listObjects("workspaces/")).thenReturn(List.of(
                object("workspaces/a", 1L, NOW.minus(1, ChronoUnit.DAYS)),
                object("workspaces/b", 1L, NOW.minus(1, ChronoUnit.DAYS))));
        when(opsMapper.selectReferencedObjectKeys()).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("denied"))
                .when(objectStorage).deleteObject("workspaces/a");

        OpsJobRegistry.JobResult result = service.collect(NOW);

        verify(objectStorage).deleteObject("workspaces/b");
        assertEquals(1, result.errors());
    }
}
