package com.bond.transfer.storage;

import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.Locale;
import java.util.function.Function;

/**
 * 对象存储配置（不可变）。
 *
 * <p>解析顺序（逐项）：<b>storage.* → 旧 minio.*（兼容旧部署文件）→ 环境变量 MINIO_* → 内置默认值</b>。
 * 这样运维把旧的 {@code application-transfer.yml}（只有 {@code minio.*}）直接挂进来也还能启动，
 * 不会因为改成 storage.* 而踩空。</p>
 *
 * <p>配置项：{@code storage.endpoint / access-key / secret-key / bucket / region / path-style /
 * presign-expiry-seconds}。region 默认 {@code us-east-1}（MinIO/RustFS 不校验）。</p>
 */
public record StorageSettings(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region,
        boolean pathStyle,
        long presignExpirySeconds) {

    public static final String DEFAULT_ENDPOINT = "http://localhost:9000";
    public static final String DEFAULT_ACCESS_KEY = "minioadmin";
    public static final String DEFAULT_SECRET_KEY = "minioadmin";
    public static final String DEFAULT_BUCKET = "bond-transfer";
    public static final String DEFAULT_REGION = "us-east-1";
    public static final long DEFAULT_PRESIGN_EXPIRY_SECONDS = 3600L;

    public StorageSettings {
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("storage.endpoint must not be blank");
        if (accessKey == null || accessKey.isBlank()) throw new IllegalArgumentException("storage.access-key must not be blank");
        if (secretKey == null || secretKey.isBlank()) throw new IllegalArgumentException("storage.secret-key must not be blank");
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("storage.bucket must not be blank");
        URI uri = URI.create(endpoint.trim());
        if (uri.getScheme() == null || uri.getHost() == null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("storage.endpoint is not a valid http(s) URL: " + endpoint);
        }
    }

    public static StorageSettings resolve(Environment environment) {
        if (environment == null) {
            throw new IllegalArgumentException("Environment must not be null");
        }
        return resolve(environment::getProperty);
    }

    /** 供单测直接注入 map 查找函数，避免依赖 Spring Environment。 */
    static StorageSettings resolve(Function<String, String> lookup) {
        String endpoint = firstNonBlank(
                lookup.apply("storage.endpoint"),
                lookup.apply("minio.endpoint"),
                lookup.apply("STORAGE_ENDPOINT"),
                lookup.apply("MINIO_ENDPOINT"),
                DEFAULT_ENDPOINT);
        String accessKey = firstNonBlank(
                lookup.apply("storage.access-key"),
                lookup.apply("minio.access-key"),
                lookup.apply("MINIO_ACCESS_KEY"),
                DEFAULT_ACCESS_KEY);
        String secretKey = firstNonBlank(
                lookup.apply("storage.secret-key"),
                lookup.apply("minio.secret-key"),
                lookup.apply("MINIO_SECRET_KEY"),
                DEFAULT_SECRET_KEY);
        String bucket = firstNonBlank(
                lookup.apply("storage.bucket"),
                lookup.apply("minio.bucket"),
                lookup.apply("STORAGE_BUCKET"),
                DEFAULT_BUCKET);
        String region = firstNonBlank(
                lookup.apply("storage.region"),
                lookup.apply("minio.region"),
                lookup.apply("STORAGE_REGION"),
                DEFAULT_REGION);
        boolean pathStyle = parseBoolean(firstNonBlank(
                lookup.apply("storage.path-style"),
                lookup.apply("minio.path-style"),
                lookup.apply("STORAGE_PATH_STYLE")), true);
        long presignExpirySeconds = parsePositiveLong(firstNonBlank(
                lookup.apply("storage.presign-expiry-seconds"),
                lookup.apply("minio.presign-expiry-seconds"),
                lookup.apply("STORAGE_PRESIGN_EXPIRY_SECONDS")), DEFAULT_PRESIGN_EXPIRY_SECONDS);

        return new StorageSettings(endpoint.trim(), accessKey.trim(), secretKey.trim(),
                bucket.trim(), region.trim(), pathStyle, presignExpirySeconds);
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    static boolean parseBoolean(String raw, boolean defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> defaultValue;
        };
    }

    static long parsePositiveLong(String raw, long defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
