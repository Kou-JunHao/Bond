package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 未完成上传会话（task-54，数据源 V7 {@code file_upload_sessions}）。
 *
 * <p>会话行只有在 complete 成功或被 abort 后才删除，因此表内所有未过期行都代表「未完成」。
 * {@code chunksReceived} 由服务端解析 etags 数组得出，{@code receivedBytes} 与会话行保持一致。</p>
 */
@Data
public class UploadSessionDTO {

    /** 对外仍是字符串，避免 ECMAScript 精度问题（会话 id 为雪花）。 */
    private String uploadId;

    private String fileName;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long workspaceId;

    private String parentPath;

    private long totalBytes;

    private long receivedBytes;

    private int chunksReceived;

    private int totalChunks;

    private OffsetDateTime createdAt;

    private OffsetDateTime expiresAt;
}
