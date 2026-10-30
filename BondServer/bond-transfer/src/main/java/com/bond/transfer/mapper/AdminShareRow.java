package com.bond.transfer.mapper;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 管理端分享治理列表的 join 查询行（share_links + users + workspace_files）。
 *
 * <p>一次 join 取齐列表所需字段，避免 N+1；由 {@code AdminShareService} 映射为
 * {@code AdminShareDTO} 并补公网 URL。</p>
 */
@Data
public class AdminShareRow {
    private Long id;
    private String token;
    private Long fileId;
    private String fileName;
    private Long ownerId;
    private String ownerUsername;
    private Integer status;
    private OffsetDateTime expiresAt;
    private Integer downloadCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
