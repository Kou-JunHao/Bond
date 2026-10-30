package com.bond.transfer.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@link ObjectStorage} 的 AWS SDK v2 S3 实现。
 *
 * <p>通过 {@code endpointOverride} + {@code pathStyle=true} 适配所有 S3 兼容后端：
 * 适配所有 S3 兼容后端：MinIO、RustFS、Garage、Cloudflare R2、B2 等。
 * 行为与旧对象存储实现一致：单次 PUT / GET / DELETE，启动时自动建 bucket。</p>
 */
public class S3ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageSettings settings;

    public S3ObjectStorage(S3Client s3, S3Presigner presigner, StorageSettings settings) {
        this.s3 = s3;
        this.presigner = presigner;
        this.settings = settings;
    }

    /** 启动时确保 bucket 存在，失败只告警不阻塞启动（与旧实现行为一致）。 */
    @PostConstruct
    public void initBucket() {
        String bucket = settings.bucket();
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("Object storage ready: endpoint={}, bucket={}, region={}, pathStyle={}",
                    settings.endpoint(), bucket, settings.region(), settings.pathStyle());
        } catch (NoSuchBucketException e) {
            createBucket(bucket);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                createBucket(bucket);
            } else {
                log.error("Failed to verify object storage bucket '{}' at {} (status={})",
                        bucket, settings.endpoint(), e.statusCode(), e);
            }
        } catch (Exception e) {
            // 后端暂不可用时不要让整个服务启动失败（与旧 MinIO 初始化行为一致）
            log.error("Object storage is not reachable yet: endpoint={}, bucket={}", settings.endpoint(), bucket, e);
        }
    }

    private void createBucket(String bucket) {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Created bucket: {}", bucket);
        } catch (Exception e) {
            log.error("Failed to create bucket '{}' at {}", bucket, settings.endpoint(), e);
        }
    }

    @Override
    public void putObject(String key, InputStream data, long size, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(settings.bucket())
                .key(key)
                .contentType(contentType != null && !contentType.isBlank()
                        ? contentType : "application/octet-stream")
                .contentLength(size)
                .build();
        s3.putObject(request, RequestBody.fromInputStream(data, size));
    }

    @Override
    public InputStream getObject(String key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(settings.bucket())
                .key(key)
                .build();
        ResponseInputStream<GetObjectResponse> response = s3.getObject(request);
        return response;
    }

    @Override
    public InputStream getObject(String key, long offset, long length) {
        if (offset < 0) {
            throw new IllegalArgumentException("range offset must be >= 0: " + offset);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("range length must be > 0: " + length);
        }
        long end = offset + length - 1;
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(settings.bucket())
                .key(key)
                .range("bytes=" + offset + "-" + end)
                .build();
        return s3.getObject(request);
    }

    @Override
    public void deleteObject(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(settings.bucket()).key(key).build());
    }

    @Override
    public boolean objectExists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(settings.bucket()).key(key).build());
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public ObjectStat statObject(String key) {
        HeadObjectResponse response = s3.headObject(
                HeadObjectRequest.builder().bucket(settings.bucket()).key(key).build());
        long size = response.contentLength() != null ? response.contentLength() : 0L;
        return new ObjectStat(size, response.contentType(), response.eTag());
    }

    @Override
    public List<StoredObject> listObjects(String prefix) {
        List<StoredObject> result = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder builder = ListObjectsV2Request.builder()
                    .bucket(settings.bucket())
                    .prefix(prefix != null ? prefix : "");
            if (continuationToken != null) {
                builder.continuationToken(continuationToken);
            }
            ListObjectsV2Response response = s3.listObjectsV2(builder.build());
            for (S3Object object : response.contents()) {
                long size = object.size() != null ? object.size() : 0L;
                java.time.Instant lastModified = object.lastModified() != null
                        ? object.lastModified() : java.time.Instant.EPOCH;
                result.add(new StoredObject(object.key(), size, lastModified));
            }
            continuationToken = Boolean.TRUE.equals(response.isTruncated())
                    ? response.nextContinuationToken() : null;
        } while (continuationToken != null);
        return result;
    }

    @Override
    public String presignedGetUrl(String key, Duration ttl) {
        Duration effectiveTtl = ttl != null && !ttl.isNegative() && !ttl.isZero()
                ? ttl : Duration.ofSeconds(settings.presignExpirySeconds());
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(effectiveTtl)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(settings.bucket())
                        .key(key)
                        .build())
                .build();
        return presigner.presignGetObject(request).url().toString();
    }

    @Override
    public void copyObject(String sourceKey, String destinationKey) {
        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(settings.bucket())
                .sourceKey(sourceKey)
                .destinationBucket(settings.bucket())
                .destinationKey(destinationKey)
                .build());
    }

    // ── 分片上传 ──

    @Override
    public String initMultipart(String key, String contentType) {
        CreateMultipartUploadRequest.Builder builder = CreateMultipartUploadRequest.builder()
                .bucket(settings.bucket())
                .key(key);
        if (contentType != null && !contentType.isBlank()) {
            builder.contentType(contentType);
        }
        return s3.createMultipartUpload(builder.build()).uploadId();
    }

    @Override
    public String uploadPart(String key, String uploadId, int partNumber, InputStream data, long size) {
        if (partNumber < 1 || partNumber > 10_000) {
            throw new IllegalArgumentException("partNumber must be 1..10000: " + partNumber);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("part size must be > 0: " + size);
        }
        UploadPartRequest request = UploadPartRequest.builder()
                .bucket(settings.bucket())
                .key(key)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .contentLength(size)
                .build();
        return s3.uploadPart(request, RequestBody.fromInputStream(data, size)).eTag();
    }

    @Override
    public void completeMultipart(String key, String uploadId, List<PartETag> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("multipart parts must not be empty");
        }
        List<PartETag> ordered = parts.stream()
                .sorted(Comparator.comparingInt(PartETag::partNumber))
                .toList();
        PartETag last = ordered.get(ordered.size() - 1);
        for (PartETag part : ordered) {
            if (part.etag() == null || part.etag().isBlank()) {
                throw new IllegalArgumentException("multipart part " + part.partNumber() + " has no ETag");
            }
            if (part != last && part.size() < MIN_MULTIPART_PART_SIZE) {
                throw new IllegalArgumentException("multipart part " + part.partNumber() + " is "
                        + part.size() + " bytes, smaller than the S3 minimum of "
                        + MIN_MULTIPART_PART_SIZE + " bytes (only the last part may be smaller)");
            }
        }
        List<CompletedPart> completedParts = ordered.stream()
                .map(part -> CompletedPart.builder()
                        .partNumber(part.partNumber())
                        .eTag(part.etag())
                        .build())
                .toList();
        CompleteMultipartUploadRequest request = CompleteMultipartUploadRequest.builder()
                .bucket(settings.bucket())
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build();
        s3.completeMultipartUpload(request);
    }

    @Override
    public void abortMultipart(String key, String uploadId) {
        s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                .bucket(settings.bucket())
                .key(key)
                .uploadId(uploadId)
                .build());
    }
}
