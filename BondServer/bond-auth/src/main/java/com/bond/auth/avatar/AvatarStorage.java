package com.bond.auth.avatar;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.Optional;

/**
 * 头像对象存储（task-152）。
 *
 * <p>刻意不复用 bond-transfer 的 {@code ObjectStorage} 抽象：那是网盘业务的 S3 门面，auth 仅需「一个
 * bucket 的 put/get/delete」，另起一套可以避免把 transfer 的 multipart / 预签名语义带进 auth 模块。
 * 两者共用同一套 {@code storage.*} 配置键，运维只需要配一份；但头像使用**独立 bucket**
 * {@code bond-avatar}（默认），与网盘对象的配额、生命周期、清理规则彻底隔离——头像不计入用户存储配额。</p>
 */
@Slf4j
@Component
public class AvatarStorage {

    /** 头像独立 bucket；网盘 bucket 默认 bond-transfer。 */
    static final String DEFAULT_BUCKET = "bond-avatar";

    private final S3Client client;
    private final String bucket;

    public AvatarStorage(@Value("${storage.endpoint:http://localhost:9000}") String endpoint,
                         @Value("${storage.access-key:minioadmin}") String accessKey,
                         @Value("${storage.secret-key:minioadmin}") String secretKey,
                         @Value("${storage.region:us-east-1}") String region,
                         @Value("${storage.avatar-bucket:" + DEFAULT_BUCKET + "}") String bucket) {
        this.bucket = (bucket == null || bucket.isBlank()) ? DEFAULT_BUCKET : bucket.trim();
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
        this.client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();
    }

    /** 启动时确保 bucket 存在；失败只告警，不阻断 auth 启动（上传时再暴露真实错误）。 */
    @PostConstruct
    void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException notFound) {
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("avatar bucket created: {}", bucket);
            } catch (Exception createFailed) {
                log.warn("avatar bucket create failed: {} ({})", bucket, createFailed.getMessage());
            }
        } catch (Exception headFailed) {
            log.warn("avatar bucket probe failed: {} ({})", bucket, headFailed.getMessage());
        }
    }

    public void put(long userId, byte[] jpeg) {
        client.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key(userId))
                        .contentType("image/jpeg")
                        // 头像 URL 自带 v 参数，可以长缓存；换头像后 v 变化即失效。
                        .cacheControl("public, max-age=604800, immutable")
                        .build(),
                RequestBody.fromBytes(jpeg));
    }

    public Optional<byte[]> get(long userId) {
        try {
            return Optional.of(client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key(userId))
                    .build()).asByteArray());
        } catch (NoSuchKeyException missing) {
            return Optional.empty();
        } catch (S3Exception e) {
            log.warn("avatar read failed: userId={} status={} code={}", userId, e.statusCode(), e.awsErrorDetails() == null
                    ? null : e.awsErrorDetails().errorCode());
            return Optional.empty();
        }
    }

    /** 删除旧头像；对象不存在视为成功（幂等）。失败只告警，不影响接口 204。 */
    public void delete(long userId) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(userId)).build());
        } catch (Exception e) {
            log.warn("avatar delete failed: userId={} ({})", userId, e.getMessage());
        }
    }

    static String key(long userId) {
        return "avatars/" + userId + ".jpg";
    }
}
