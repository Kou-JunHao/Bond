package com.bond.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.Workspace;
import org.apache.ibatis.annotations.Mapper;

/**
 * bond-auth 侧的 workspaces 访问入口，仅用于注册时创建个人空间。
 * （bond-transfer 有自己的 WorkspaceMapper，两个服务各自扫描自己的 mapper 包，互不冲突。）
 */
@Mapper
public interface WorkspaceMapper extends BaseMapper<Workspace> {
}
