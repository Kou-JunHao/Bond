package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 邮件模板（task-63，V16）。
 *
 * <p>正文只做受控变量替换（白名单见 {@code EmailTemplateRenderer}），不支持脚本/内嵌 HTML 执行；
 * {@code variables} 存 JSON 数组字符串（如 {@code ["code","username"]}），供后台 UI 提示可用变量。</p>
 */
@Data
@TableName("email_templates")
public class EmailTemplate {

    @TableId(type = IdType.INPUT)
    private String code;

    private String name;

    private String subject;

    private String bodyHtml;

    private String bodyText;

    /** JSON 数组字符串，如 ["code","username","appName"]。 */
    private String variables;

    private Boolean enabled;

    private Boolean isBuiltin;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long updatedBy;

    private OffsetDateTime updatedAt;
}
