package com.bond.common.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 分片对外 JSON 契约。task-107：与 {@link TransferTaskDTO} 一样显式钉死 lowerCamelCase，
 * 防止环境级 Jackson 命名策略把 {@code taskId/chunkIndex/chunkSize} 变成下划线而让客户端读空。
 */
@Data
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public class ChunkDTO {
    /** ID 超出 JS 安全整数范围（>2^53），序列化为字符串，避免前端精度丢失。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;
    private Integer chunkIndex;
    private Long chunkSize;
    private String etag;
    private Integer status;
}
