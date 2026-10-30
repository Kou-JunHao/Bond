package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.WorkspaceAcl;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工作区文件级 ACL（V34 / task-183B）。
 *
 * <p>查询/整包替换都可用 MyBatis-Plus 的 {@code LambdaQueryWrapper}/{@code delete}+{@code insert}
 * 完成（单成员授权数有上限、一次只取一个人的），因此不额外写 SQL；强制点按
 * {@code (workspace_id, user_id)} 取全部授权，走 {@code idx_workspace_acl_workspace_user}。</p>
 */
@Mapper
public interface WorkspaceAclMapper extends BaseMapper<WorkspaceAcl> {
}
