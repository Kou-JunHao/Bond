package com.bond.transfer.preview;

import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 删除文件时的缩略图清理：列出全部尺寸并逐个删除；存储异常只 warn 不抛。 */
@ExtendWith(MockitoExtension.class)
class ThumbnailEvictTest {

    @Mock
    private ObjectStorage objectStorage;

    @Test
    void evictFileDeletesEveryCachedSizeOfThatFile() {
        ThumbnailService service = new ThumbnailService(objectStorage, "ffmpeg");
        when(objectStorage.listObjects("thumbnails/9-")).thenReturn(List.of(
                new ObjectStorage.StoredObject("thumbnails/9-320.jpg", 1L, Instant.now()),
                new ObjectStorage.StoredObject("thumbnails/9-640-p1.jpg", 1L, Instant.now()),
                new ObjectStorage.StoredObject("thumbnails/9-pages.txt", 1L, Instant.now())));

        service.evictFile(9L);

        verify(objectStorage, times(3)).deleteObject(anyString());
    }

    @Test
    void evictFileSwallowsStorageFailures() {
        ThumbnailService service = new ThumbnailService(objectStorage, "ffmpeg");
        when(objectStorage.listObjects("thumbnails/9-")).thenThrow(new RuntimeException("storage down"));

        assertDoesNotThrow(() -> service.evictFile(9L));
    }

    @Test
    void evictFileIgnoresNullFileId() {
        ThumbnailService service = new ThumbnailService(objectStorage, "ffmpeg");
        assertDoesNotThrow(() -> service.evictFile(null));
    }
}
