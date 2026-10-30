package com.bond.transfer.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Bean 装配冒烟测试：真正构造 S3Client / S3Presigner（不发起网络请求），
 * 防止 endpointOverride / forcePathStyle / serviceConfiguration 组合在运行期才暴露问题。
 */
class ObjectStorageConfigTest {

    private static final StorageSettings SETTINGS = new StorageSettings(
            "http://localhost:9000", "test-ak", "test-sk", "bond-transfer", "us-east-1", true, 3600);

    @Test
    @DisplayName("按 endpointOverride + path-style 构造 client/presigner/objectStorage 不抛异常")
    void buildsBeansWithoutNetwork() {
        ObjectStorageConfig config = new ObjectStorageConfig();
        try (S3Client client = config.s3Client(SETTINGS);
             S3Presigner presigner = config.s3Presigner(SETTINGS)) {
            assertNotNull(client);
            assertNotNull(presigner);
            ObjectStorage storage = config.objectStorage(client, presigner, SETTINGS);
            assertInstanceOf(S3ObjectStorage.class, storage);
        }
    }
}
