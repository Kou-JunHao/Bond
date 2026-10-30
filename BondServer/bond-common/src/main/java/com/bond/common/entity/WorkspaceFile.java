package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Data
@TableName("workspace_files")
public class WorkspaceFile {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String fileName;
    private Long fileSize;
    private String minioPath;
    private String contentType;
    private Long uploadedBy;
    private String parentPath;
    private Boolean isDirectory;
    private String fileHash;
    private LocalDateTime createdAt;
    /** V25：最后一次变更时间（改名/移动/软删/恢复都会刷新），文件搜索按它倒序做 keyset 分页。 */
    private OffsetDateTime updatedAt;

    // ── 回收站（V6）：非空即表示已软删，物理对象保留到彻底删除/过期清理 ──
    private OffsetDateTime deletedAt;
    private Long deletedBy;
    /** 回收站过期时间；null = 不限期（仅付费档 retention=0 时出现）。 */
    private OffsetDateTime trashExpiresAt;
}
