package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 文档历史版本（V10，每次保存/恢复写一条；版本号与 documents.version 同步、只增不复用）。 */
@Data
@TableName("document_versions")
public class DocumentVersion {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long documentId;
    private Long version;
    private String contentMd;
    /** 历史版本 HTML（V29，已白名单净化）。 */
    private String contentHtml;
    private Long sizeBytes;
    private Long editorId;
    private OffsetDateTime createdAt;
}
