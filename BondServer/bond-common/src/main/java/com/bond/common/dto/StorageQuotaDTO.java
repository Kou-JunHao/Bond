package com.bond.common.dto;

import lombok.Data;

/**
 * 存储配额（个人空间 / 团队工作区通用）。
 *
 * <p>数值都在 JS 安全整数范围内（< 2^53），保持数字；本 DTO 不含 ID 字段。</p>
 */
@Data
public class StorageQuotaDTO {
    /** 已用字节（逻辑占用，按 file_size 累加）。 */
    private long usedBytes;
    /** 配额上限：个人空间=用户档位 storage_bytes；团队工作区=owner 档位。 */
    private long maxBytes;
    /** 非目录文件数。 */
    private long fileCount;
    /** 回收站占用字节（task-88：回收站内容继续占配额；客户端用于「回收站占用 x GB」提示）。 */
    private long trashBytes;
    /** 生效档位：free / plus / team。 */
    private String planCode;
}
