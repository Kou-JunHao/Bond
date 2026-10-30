package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 邮件投递日志（task-80，V18）。
 *
 * <p>只保存可审计的元数据：模板、主题、状态、耗时、变量<b>名</b>；
 * 绝不保存正文、验证码、临时密码等变量<b>值</b>。收件人只存掩码。</p>
 */
@Data
@TableName("email_delivery_log")
public class EmailDeliveryLog {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 收件人对应的用户（未注册邮箱可空）。 */
    private Long userId;

    /** 收件人掩码，例如 bo***@example.com。 */
    private String toMasked;

    /** 发送用途（按用途发送时才有）。 */
    private String purpose;

    /** 实际使用的模板 code（含自定义模板）。 */
    private String templateCode;

    /** 渲染后的主题（正文不落库）。 */
    private String subject;

    /** sent / failed。 */
    private String status;

    /** 失败原因摘要（截断 + 脱敏）。 */
    private String failureReason;

    /** 变量名列表的 JSON 数组字符串，例如 ["code","username"]。 */
    private String variableKeys;

    /** 渲染后残留 `{{` 的个数，0 表示正常。 */
    private Integer placeholdersLeft;

    /** smtp / resend / bondmail。 */
    private String provider;

    /** 发送耗时（毫秒）。 */
    private Integer durationMs;

    /** 本次投递尝试次数（含自动重试；task-134，V27）。 */
    private Integer attempts;

    /**
     * 管理端手动重发用载荷（task-134，V27）：AES-GCM(enc:v1:) 加密的 to+variables JSON。
     * 绝不存明文收件人/变量值；null 表示该条不可重发。
     */
    private String resendPayload;

    private OffsetDateTime createdAt;
}
