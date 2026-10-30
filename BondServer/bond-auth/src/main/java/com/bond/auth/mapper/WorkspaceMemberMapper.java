package com.bond.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.WorkspaceMember;
import org.apache.ibatis.annotations.Mapper;

/** bond-auth 侧的 workspace_members 访问入口，仅用于注册时创建个人空间的 OWNER 成员行。 */
@Mapper
public interface WorkspaceMemberMapper extends BaseMapper<WorkspaceMember> {
}
