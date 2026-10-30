package com.bond.transfer.preview;

import com.bond.common.entity.WorkspaceFile;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 缩略图缓存兜底 GC：孤儿（fileId 不存在）/ 过期（超过 cache_days）/ 单轮上限。 */
@ExtendWith(MockitoExtension.class)
class ThumbnailCacheGcServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T03:30:00Z");

    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private WorkspaceFileMapper fileMapper;
    @Mock
    private ConfigService configService;
    @InjectMocks
    private ThumbnailCacheGcService gcService;

    private static ObjectStorage.StoredObject object(String key, Instant lastModified) {
        return new ObjectStorage.StoredObject(key, 1024L, lastModified);
    }

    private static WorkspaceFile existingFile(Long id) {
        WorkspaceFile file = new WorkspaceFile();
        file.setId(id);
        return file;
    }

    @Test
    void deletesOrphanObjectWhoseFileIsGone() {
        when(objectStorage.listObjects("thumbnails/")).thenReturn(List.of(
                object("thumbnails/1-320.jpg", NOW),
                object("thumbnails/2-320.jpg", NOW)));
        when(configService.getInt("thumbnail.cache_days", 30)).thenReturn(30);
        when(fileMapper.selectBatchIds(any())).thenReturn(List.of(existingFile(2L)));

        int deleted = gcService.collect(NOW);

        assertEquals(1, deleted);
        verify(objectStorage).deleteObject("thumbnails/1-320.jpg");
        verify(objectStorage, never()).deleteObject("thumbnails/2-320.jpg");
    }

    @Test
    void deletesObjectOlderThanCacheDays() {
        Instant old = NOW.minus(31, ChronoUnit.DAYS);
        when(objectStorage.listObjects("thumbnails/")).thenReturn(List.of(
                object("thumbnails/3-320.jpg", old),
                object("thumbnails/3-pages.txt", NOW)));
        when(configService.getInt("thumbnail.cache_days", 30)).thenReturn(30);
        when(fileMapper.selectBatchIds(any())).thenReturn(List.of(existingFile(3L)));

        int deleted = gcService.collect(NOW);

        assertEquals(1, deleted);
        verify(objectStorage).deleteObject("thumbnails/3-320.jpg");
        verify(objectStorage, never()).deleteObject("thumbnails/3-pages.txt");
    }

    @Test
    void keepsFreshThumbnailOfExistingFile() {
        when(objectStorage.listObjects("thumbnails/")).thenReturn(List.of(
                object("thumbnails/4-128.jpg", NOW.minus(1, ChronoUnit.DAYS))));
        when(configService.getInt("thumbnail.cache_days", 30)).thenReturn(30);
        when(fileMapper.selectBatchIds(any())).thenReturn(List.of(existingFile(4L)));

        int deleted = gcService.collect(NOW);

        assertEquals(0, deleted);
        verify(objectStorage, never()).deleteObject(any());
    }

    @Test
    void enforcesPerRunDeleteLimit() {
        List<ObjectStorage.StoredObject> objects = new ArrayList<>();
        List<WorkspaceFile> files = new ArrayList<>();
        for (long id = 1; id <= 600; id++) {
            objects.add(object("thumbnails/" + id + "-320.jpg", NOW.minus(31, ChronoUnit.DAYS)));
            files.add(existingFile(id));
        }
        when(objectStorage.listObjects("thumbnails/")).thenReturn(objects);
        when(configService.getInt("thumbnail.cache_days", 30)).thenReturn(30);
        when(fileMapper.selectBatchIds(any())).thenReturn(files);

        int deleted = gcService.collect(NOW);

        assertEquals(ThumbnailCacheGcService.MAX_DELETE_PER_RUN, deleted);
        verify(objectStorage, times(ThumbnailCacheGcService.MAX_DELETE_PER_RUN)).deleteObject(any());
    }

    @Test
    void parsesFileIdFromThumbnailKeys() {
        assertEquals(12L, ThumbnailService.parseFileId("thumbnails/12-320.jpg"));
        assertEquals(12L, ThumbnailService.parseFileId("thumbnails/12-640-p3.jpg"));
        assertEquals(12L, ThumbnailService.parseFileId("thumbnails/12-pages.txt"));
        assertNull(ThumbnailService.parseFileId("thumbnails/abc-320.jpg"));
        assertNull(ThumbnailService.parseFileId("workspaces/1-2/thumb.jpg"));
        assertNull(ThumbnailService.parseFileId(null));
    }
}
