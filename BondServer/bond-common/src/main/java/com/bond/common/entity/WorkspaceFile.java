package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

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
    private LocalDateTime createdAt;
}
