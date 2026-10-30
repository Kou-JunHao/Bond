package com.bond.transfer.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * 对象存储抽象：只声明业务真正需要的 S3 语义操作，实现可替换
 * （MinIO / RustFS / Garage / Cloudflare R2 / B2 等 S3 兼容后端）。
 *
 * <p>bucket 来自配置 {@code storage.bucket}（与旧实现一致，全局单 bucket），
 * key 规则保持不变：{@code transfers/{id}/...}、{@code workspaces/{workspaceId}/...}、{@code avatars/{userId}...}，
 * 因此切换后端不会影响已有对象路径。</p>
 *
 * <p>实现方不做业务校验；只负责 S3 协议映射。<b>分片上传</b>：除最后一片外，
 * 每个 part 必须 &gt;= 5MB（S3 规范，MinIO/RustFS 同样校验），
 * {@link #completeMultipart} 会显式校验并在不满足时抛 {@link IllegalArgumentException}。</p>
 */
public interface ObjectStorage {

    /** 最小分片大小（5MB，除最后一片外）。 */
    long MIN_MULTIPART_PART_SIZE = 5L * 1024 * 1024;

    /** 简单上传（单次 PUT）。 */
    void putObject(String key, InputStream data, long size, String contentType);

    /** 读取整个对象。调用方负责关闭返回的流。 */
    InputStream getObject(String key);

    /** Range 读取：offset 起、最多 length 字节。调用方负责关闭返回的流。 */
    InputStream getObject(String key, long offset, long length);

    /** 删除对象；对象不存在不报错（S3 语义）。 */
    void deleteObject(String key);

    boolean objectExists(String key);

    /** HEAD 对象元数据。 */
    ObjectStat statObject(String key);

    /** 列举指定前缀下的对象（自动翻页）。 */
    List<StoredObject> listObjects(String prefix);

    /** 生成预签名 GET URL。 */
    String presignedGetUrl(String key, Duration ttl);

    /**
     * 服务端复制对象（S3 CopyObject，数据不经过本服务）。
     *
     * <p>用于秒传：同一 bucket 内已有对象复制到新 key，调用方随后创建新的文件行。
     * 复制后源对象与目标对象各自独立，删除其一不影响另一个。</p>
     */
    void copyObject(String sourceKey, String destinationKey);

    // ── 分片上传（S3 Multipart Upload） ──

    /** 初始化分片上传，返回 uploadId。 */
    String initMultipart(String key, String contentType);

    /** 上传一个 part，返回 ETag（调用方保存，complete 时回传）。 */
    String uploadPart(String key, String uploadId, int partNumber, InputStream data, long size);

    /**
     * 完成分片上传。
     *
     * @throws IllegalArgumentException 非最后一个 part 小于 {@link #MIN_MULTIPART_PART_SIZE}
     */
    void completeMultipart(String key, String uploadId, List<PartETag> parts);

    /** 中止分片上传（清理已上传 parts）。 */
    void abortMultipart(String key, String uploadId);

    // ── 返回模型 ──

    /** 对象元数据；size/contentType/etag 可能为 null（后端未返回时）。 */
    record ObjectStat(long size, String contentType, String etag) {
    }

    /** 列举结果。{@code lastModified} 为对象最后修改时间（后端未返回时用 {@link java.time.Instant#EPOCH}）。 */
    record StoredObject(String key, long size, java.time.Instant lastModified) {
    }

    /** 分片信息：partNumber + ETag + 该片字节数（用于 complete 时校验最小分片大小）。 */
    record PartETag(int partNumber, String etag, long size) {
    }
}
