package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class WorkspaceFileDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long workspaceId;
    private String fileName;
    private Long fileSize;
    private String minioPath;
    private String contentType;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long uploadedBy;
    private String parentPath;
    private Boolean isDirectory;
    private String fileHash;
    private Long createdAt;
}
