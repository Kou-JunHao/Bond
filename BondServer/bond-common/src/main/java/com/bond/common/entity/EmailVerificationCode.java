package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 邮箱验证码（V14，task-57）。只存 HMAC-SHA256，不存明文；一次性消费。
 */
@Data
@TableName("email_verification_codes")
public class EmailVerificationCode {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String email;
    /** 关联用户（注册时可为空，重置/改邮箱时带上）。 */
    private Long userId;
    /** register / reset_password / change_email。 */
    private String purpose;
    private String codeHash;
    private OffsetDateTime expiresAt;
    private Integer attempts;
    private OffsetDateTime consumedAt;
    private String ip;
    private OffsetDateTime createdAt;
}
