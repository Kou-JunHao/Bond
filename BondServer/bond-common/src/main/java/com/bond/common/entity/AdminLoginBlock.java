package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 管理端登录 fail2ban 封禁（task-144，持久化，重启不丢）。
 *
 * <p>{@code scope=ip} 按客户端 IP 封禁；{@code scope=user} 按登录账号封禁。
 * 未解除的封禁行由部分唯一索引 {@code uq_admin_login_blocks_active} 约束。</p>
 */
@Data
@TableName("admin_login_blocks")
public class AdminLoginBlock {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** ip / user */
    private String scope;

    /** IP 或用户名（scope 决定含义）。 */
    private String value;

    private String reason;

    private Integer failCount;

    private OffsetDateTime bannedAt;

    private OffsetDateTime expiresAt;

    private OffsetDateTime liftedAt;

    private Long liftedBy;
}
