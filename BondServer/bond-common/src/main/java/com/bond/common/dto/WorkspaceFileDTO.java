package com.bond.common.dto;

import lombok.Data;

@Data
public class WorkspaceFileDTO {
    private Long id;
    private Long workspaceId;
    private String fileName;
    private Long fileSize;
    private String minioPath;
    private String contentType;
    private Long uploadedBy;
    private String parentPath;
    private Boolean isDirectory;
    private Long createdAt;
}
