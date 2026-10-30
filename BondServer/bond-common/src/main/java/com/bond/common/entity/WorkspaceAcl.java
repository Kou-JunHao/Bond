package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 工作区文件级 ACL 授权（V34 / task-183B）。
 *
 * <p>语义：restricted 成员的有效访问范围 = 全部 {@code can_read=true} 的授权行之并集；
 * 目录授权（{@code scope_type='folder'}）覆盖 {@code scope_path} 本身及其全部子路径，
 * 文件授权（{@code scope_type='file'}）只覆盖 {@code file_id} 这一行。
 * owner/admin（role 0/1）忽略本表，永远等价于「全部权限」。</p>
 */
@Data
@TableName("workspace_acl")
public class WorkspaceAcl {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private Long userId;
    /** folder / file。 */
    private String scopeType;
    /** 规范化逻辑路径：/ 开头、无尾斜杠；目录授权下 / 表示整个空间。 */
    private String scopePath;
    /** scope_type=file 时的文件行 id；folder 为 null。 */
    private Long fileId;
    private Boolean canRead;
    private Boolean canWrite;
    private OffsetDateTime createdAt;
    private Long createdBy;
}
