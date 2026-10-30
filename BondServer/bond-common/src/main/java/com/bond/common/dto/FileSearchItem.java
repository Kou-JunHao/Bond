package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * task-117：文件搜索结果项。
 *
 * <p><b>字段名是 API 契约</b>（与 {@link WorkspaceFileDTO} 的 fileName/fileSize 不同，这里是
 * name/size/path；已写入 {@code docs/API-CONTRACT.md} 与 {@code docs/openapi.json}，并由
 * {@code ContractRegressionTest} 守护）。</p>
 */
public record FileSearchItem(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String name,
        String path,
        Boolean isDirectory,
        Long size,
        String contentType,
        Long updatedAtMillis,
        @JsonSerialize(using = ToStringSerializer.class) Long workspaceId) {
}
