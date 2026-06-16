package com.bond.common.dto;

import lombok.Data;

@Data
public class ChunkDTO {
    private Long id;
    private Long taskId;
    private Integer chunkIndex;
    private Long chunkSize;
    private String etag;
    private Integer status;
}
