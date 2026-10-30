package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 邮件用途绑定（task-76，V17）。
 *
 * <p>{@code purpose} 取值见 {@code com.bond.common.email.EmailPurpose}；{@code templateCode}
 * 指向 {@code email_templates.code}。绑定失效（模板被删/禁用）时发送侧回退内置默认，不会丢信。</p>
 */
@Data
@TableName("email_template_bindings")
public class EmailTemplateBinding {

    @TableId(type = IdType.INPUT)
    private String purpose;

    private String templateCode;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long updatedBy;

    private OffsetDateTime updatedAt;
}
