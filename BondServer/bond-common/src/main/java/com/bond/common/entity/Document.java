package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 在线文档（V10）。正文权威存储为 {@code content_md}；{@code content_json} 预留块模型（MVP 恒 NULL）。
 */
@Data
@TableName("documents")
public class Document {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private Long ownerId;
    private String title;
    private String contentMd;
    /** 富文本 HTML（V29，已白名单净化；content_type=html 时为权威正文）。 */
    private String contentHtml;
    private String contentJson;
    private String contentType;
    private Long version;
    private Long sizeBytes;
    private String parentPath;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;
    private OffsetDateTime trashExpiresAt;
    private Long lastEditorId;
}
