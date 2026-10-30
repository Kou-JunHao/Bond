package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/** 文档版本列表项（不含正文）。 */
@Data
public class DocumentVersionDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long documentId;
    private Long version;
    private Long sizeBytes;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long editorId;
    private String editorName;
    private Long createdAt;
    /** 该版本正文（仅「查看单个版本」返回；列表为 null）。 */
    private String contentMd;
    private String contentHtml;
    private String content;
}
