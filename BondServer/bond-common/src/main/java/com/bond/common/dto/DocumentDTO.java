package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 文档 DTO：列表不返回正文（{@code contentMd} 为 null），详情/保存返回正文。
 * ID 一律字符串序列化（与全仓 41 处一致，给 Web 端留命）。
 */
@Data
public class DocumentDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long workspaceId;
    private String title;
    private String parentPath;
    private Long sizeBytes;
    private Long version;
    private String contentType;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastEditorId;
    private String lastEditorName;
    private Long createdAt;
    private Long updatedAt;
    private String contentMd;
    /** 富文本 HTML（content_type=html 时非空；已白名单净化）。 */
    private String contentHtml;
    /** 正文别名：markdown 文档为 contentMd、html 文档为 contentHtml（客户端/契约 task-137 使用）。 */
    private String content;
    /** 更新时间毫秒（契约字段，与 updatedAt 同值）。 */
    private Long updatedAtMillis;
    /** 最后编辑者昵称（契约字段，与 lastEditorName 同值）。 */
    private String lastEditorNickname;
}
