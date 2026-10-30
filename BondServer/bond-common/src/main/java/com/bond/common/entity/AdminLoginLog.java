package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 管理端登录审计日志（task-144）。
 *
 * <p>仅记录被判定为“管理后台登录”的尝试：请求头 {@code X-Client-App: bond-admin}，
 * 或目标账号本身 {@code is_admin = true}（兜底）。普通用户登录不入本表。</p>
 */
@Data
@TableName("admin_login_logs")
public class AdminLoginLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 原样记录尝试的用户名（失败时可能不存在对应账号）。 */
    private String username;

    private String ip;

    private String userAgent;

    /** success / bad_credentials / user_disabled / blocked / captcha_required / error */
    private String outcome;

    /** 后端原始文案（截断后入库）。 */
    private String reason;

    /** 同一 IP+username 自上次成功以来的连续失败序号（成功事件为 null）。 */
    private Integer attemptNo;

    private OffsetDateTime createdAt;
}
