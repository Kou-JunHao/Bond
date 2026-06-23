package com.bond.common.dto;

import lombok.Data;

@Data
public class WorkspaceDTO {
    private Long id;
    private String name;
    private String description;
    private Long ownerId;
    private Long maxSize;
    private Long usedSize;
    private Long createdAt;
}
