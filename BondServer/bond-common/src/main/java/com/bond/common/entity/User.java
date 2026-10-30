package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Data
@TableName("users")
public class User {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String username;
    private String passwordHash;
    private String nickname;
    private String avatarUrl;
    private Integer status;
    private Boolean isAdmin;
    /** 当前档位：free / plus / team（V3）。 */
    private String planCode;
    /** 档位到期时间；NULL 表示不过期（仅 free 或人工永久授权）。 */
    private OffsetDateTime planExpiresAt;
    /** 登录/找回邮箱（V14）；不区分大小写唯一，可选。 */
    private String email;
    /** 早于该时刻签发的 refresh token 一律失效（改密/管理员重置时置 now()，V14）。 */
    private OffsetDateTime tokensValidAfter;
    /** 头像最后更新时间（V31 / task-152）；avatar_url 中的 v 参数与之匹配才可访问。 */
    private OffsetDateTime avatarUpdatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
