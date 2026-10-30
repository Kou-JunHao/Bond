package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("workspace_invitations")
public class WorkspaceInvitation {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private Long inviterId;
    private Long inviteeId;
    private String inviteToken;
    private Integer role;
    private Integer status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}
