package com.bond.transfer.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * 对象存储 Bean 装配：
 * <ul>
 *   <li>{@link S3Client} / {@link S3Presigner}：endpointOverride + path-style，兼容 MinIO/RustFS/R2/B2；</li>
 *   <li>{@link ObjectStorage}：注入 {@link S3ObjectStorage}，业务层只依赖接口。</li>
 * </ul>
 * 配置解析顺序见 {@link StorageSettings#resolve(Environment)}（storage.* → 旧 minio.* → 默认值）。
 */
@Configuration
public class ObjectStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageConfig.class);

    @Bean
    public StorageSettings storageSettings(Environment environment) {
        StorageSettings settings = StorageSettings.resolve(environment);
        log.info("Object storage configured: endpoint={}, bucket={}, region={}, pathStyle={}",
                settings.endpoint(), settings.bucket(), settings.region(), settings.pathStyle());
        return settings;
    }

    @Bean(destroyMethod = "close")
    public S3Client s3Client(StorageSettings settings) {
        return S3Client.builder()
                .endpointOverride(URI.create(settings.endpoint()))
                .region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
                .forcePathStyle(settings.pathStyle())
                .build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(StorageSettings settings) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(settings.endpoint()))
                .region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(settings.pathStyle())
                        .build())
                .build();
    }

    @Bean
    public ObjectStorage objectStorage(S3Client s3Client, S3Presigner s3Presigner, StorageSettings settings) {
        return new S3ObjectStorage(s3Client, s3Presigner, settings);
    }
}
