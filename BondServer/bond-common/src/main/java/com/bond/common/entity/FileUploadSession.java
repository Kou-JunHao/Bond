package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 网盘大文件分片/断点续传上传会话（V7）。
 *
 * <p>复用 S3 Multipart Upload：{@code storageUploadId}/{@code storageKey} 是对象存储侧的协议状态，
 * {@code etags} 为按分片 index 对齐的 ETag 数组（JSONB，缺失分片为 null），
 * {@code receivedBytes} 为已确认收到的分片字节总和（幂等重传不重复累加）。</p>
 *
 * <p>本实体只描述状态；读取时由 mapper 用 {@code etags::text} 显式转字符串，
 * 避免 PostgreSQL jsonb 与 String 的类型映射差异。</p>
 */
@Data
@TableName("file_upload_sessions")
public class FileUploadSession {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long workspaceId;

    private String parentPath;

    private String fileName;

    private String contentType;

    private Long fileSize;

    private Integer chunkSize;

    private Integer totalChunks;

    /** 对象存储返回的 multipart uploadId。 */
    private String storageUploadId;

    /** 最终对象 key：workspaces/{workspaceId}/{uuid}/{fileName}。 */
    private String storageKey;

    /** JSON 数组字符串（按 index 对齐，缺失为 null）；DB 为 JSONB。 */
    private String etags;

    private Long receivedBytes;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    private OffsetDateTime expiresAt;
}
