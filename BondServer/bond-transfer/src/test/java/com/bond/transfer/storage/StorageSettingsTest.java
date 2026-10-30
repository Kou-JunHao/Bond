package com.bond.transfer.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置解析：storage.* 优先、旧 minio.* 兜底、再退默认值；endpoint 非法时启动即失败。
 */
class StorageSettingsTest {

    @Test
    @DisplayName("storage.* 优先于旧 minio.*")
    void storageKeysWinOverLegacyMinioKeys() {
        Map<String, String> props = new HashMap<>();
        props.put("storage.endpoint", "http://rustfs:9000");
        props.put("storage.bucket", "bond-new");
        props.put("storage.access-key", "new-ak");
        props.put("storage.secret-key", "new-sk");
        props.put("minio.endpoint", "http://old-minio:9000");
        props.put("minio.bucket", "old-bucket");

        StorageSettings settings = StorageSettings.resolve(lookup(props));

        assertEquals("http://rustfs:9000", settings.endpoint());
        assertEquals("bond-new", settings.bucket());
        assertEquals("new-ak", settings.accessKey());
        assertEquals("new-sk", settings.secretKey());
    }

    @Test
    @DisplayName("仅提供旧 minio.* 时仍可启动（兼容旧部署文件）")
    void legacyMinioKeysAreStillSupported() {
        Map<String, String> props = new HashMap<>();
        props.put("minio.endpoint", "http://minio:9000");
        props.put("minio.access-key", "legacy-ak");
        props.put("minio.secret-key", "legacy-sk");
        props.put("minio.bucket", "bond-transfer");

        StorageSettings settings = StorageSettings.resolve(lookup(props));

        assertEquals("http://minio:9000", settings.endpoint());
        assertEquals("legacy-ak", settings.accessKey());
        assertEquals("legacy-sk", settings.secretKey());
        assertEquals("bond-transfer", settings.bucket());
    }

    @Test
    @DisplayName("未配置时使用内置默认值：us-east-1 / path-style=true / 预签名 1h")
    void defaultsAppliedWhenNothingConfigured() {
        StorageSettings settings = StorageSettings.resolve(lookup(Map.of()));

        assertEquals(StorageSettings.DEFAULT_ENDPOINT, settings.endpoint());
        assertEquals(StorageSettings.DEFAULT_BUCKET, settings.bucket());
        assertEquals(StorageSettings.DEFAULT_REGION, settings.region());
        assertTrue(settings.pathStyle());
        assertEquals(StorageSettings.DEFAULT_PRESIGN_EXPIRY_SECONDS, settings.presignExpirySeconds());
    }

    @Test
    @DisplayName("path-style 显式 false 生效")
    void pathStyleCanBeDisabled() {
        Map<String, String> props = new HashMap<>();
        props.put("storage.endpoint", "https://s3.example.com");
        props.put("storage.path-style", "false");

        StorageSettings settings = StorageSettings.resolve(lookup(props));

        assertFalse(settings.pathStyle());
    }

    @Test
    @DisplayName("非法 endpoint 启动即失败（fail fast）")
    void invalidEndpointFailsFast() {
        Map<String, String> props = new HashMap<>();
        props.put("storage.endpoint", "not-a-url");

        assertThrows(IllegalArgumentException.class, () -> StorageSettings.resolve(lookup(props)));
    }

    private static Function<String, String> lookup(Map<String, String> props) {
        return props::get;
    }
}
