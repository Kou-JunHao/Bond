package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class WorkspaceDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String name;
    private String description;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ownerId;
    private Long maxSize;
    private Long usedSize;
    /** 成员数（含 owner）；列表/详情由聚合查询一次填充，避免 N+1。 */
    private Long memberCount;
    /** 文件数（不含目录）；列表/详情由聚合查询一次填充，避免 N+1。 */
    private Long fileCount;
    private Long createdAt;
}
