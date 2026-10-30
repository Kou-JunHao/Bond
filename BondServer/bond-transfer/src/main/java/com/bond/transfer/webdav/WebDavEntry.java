package com.bond.transfer.webdav;

import java.time.Instant;

/**
 * PROPFIND 返回的单个资源描述。
 *
 * @param href        客户端可见路径（已 URL 编码；目录以 "/" 结尾）
 * @param displayName 显示名
 * @param directory   是否目录
 * @param size        文件字节数（目录为 0）
 * @param lastModified 最后修改时间
 * @param etag        ETag（带双引号）或 null
 * @param contentType MIME 类型（目录为 httpd/unix-directory）
 * @param lockToken   覆盖该资源的锁 token（无锁为 null；task-126）
 */
public record WebDavEntry(
        String href,
        String displayName,
        boolean directory,
        long size,
        Instant lastModified,
        String etag,
        String contentType,
        String lockToken) {
}
