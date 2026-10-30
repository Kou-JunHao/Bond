package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("workspace_members")
public class WorkspaceMember {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private Long userId;
    private Integer role;
    private LocalDateTime joinedAt;
    /**
     * V34（task-183B）：all=不受限（默认，与历史行为一致）；restricted=仅按 workspace_acl 授权访问。
     * owner/admin 的该字段被忽略（永远等价 all）。
     */
    private String accessMode;
}
