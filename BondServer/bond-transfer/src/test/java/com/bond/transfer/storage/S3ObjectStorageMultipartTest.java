package com.bond.transfer.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S3 分片上传的 5MB 最小分片校验（除最后一片外）。
 * 不连接任何对象存储：S3Client 用 Mockito mock。
 */
class S3ObjectStorageMultipartTest {

    private static final long MB5 = ObjectStorage.MIN_MULTIPART_PART_SIZE;

    private final S3Client s3 = mock(S3Client.class);
    private final S3Presigner presigner = mock(S3Presigner.class);
    private final StorageSettings settings = new StorageSettings(
            "http://localhost:9000", "test-ak", "test-sk", "bond-transfer", "us-east-1", true, 3600);
    private final S3ObjectStorage storage = new S3ObjectStorage(s3, presigner, settings);

    @Test
    @DisplayName("非最后一片小于 5MB：completeMultipart 抛 IllegalArgumentException")
    void rejectsSmallNonLastPart() {
        List<ObjectStorage.PartETag> parts = List.of(
                new ObjectStorage.PartETag(1, "etag-1", MB5 - 1),
                new ObjectStorage.PartETag(2, "etag-2", 1024));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> storage.completeMultipart("transfers/1/big.bin", "upload-1", parts));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("5"),
                "message should explain the 5MB minimum: " + ex.getMessage());
    }

    @Test
    @DisplayName("中间片均 >=5MB、最后一片可小于 5MB：正常完成")
    void allowsSmallLastPart() {
        when(s3.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenReturn(CompleteMultipartUploadResponse.builder().build());
        List<ObjectStorage.PartETag> parts = List.of(
                new ObjectStorage.PartETag(1, "etag-1", MB5),
                new ObjectStorage.PartETag(2, "etag-2", MB5),
                new ObjectStorage.PartETag(3, "etag-3", 1024));

        assertDoesNotThrow(() -> storage.completeMultipart("transfers/1/big.bin", "upload-1", parts));
    }

    @Test
    @DisplayName("空 parts / 缺 ETag 时给出明确异常")
    void rejectsEmptyOrMissingEtag() {
        assertThrows(IllegalArgumentException.class,
                () -> storage.completeMultipart("k", "u", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> storage.completeMultipart("k", "u",
                        List.of(new ObjectStorage.PartETag(1, "  ", MB5))));
    }
}
