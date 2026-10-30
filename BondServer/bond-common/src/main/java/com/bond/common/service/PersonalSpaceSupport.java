package com.bond.common.service;

import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.enums.WorkspaceRole;
import com.bond.common.enums.WorkspaceType;
import com.bond.common.utils.SnowflakeId;

/**
 * 个人空间（personal workspace）的构造工具。
 *
 * <p>bond-auth（注册时创建）与 bond-transfer（懒创建 / 启动回填）都依赖本类，
 * 保证两边生成的行完全一致：type='personal'、owner 即用户、owner 同时是 OWNER 成员。</p>
 *
 * <p>注意：id 必须用 {@link SnowflakeId#nextId()} 生成，禁止在 SQL 迁移里硬编码，
 * 否则会与雪花 ID 空间冲突。</p>
 */
public final class PersonalSpaceSupport {

    /** 个人空间默认名称（客户端「文件页」不依赖该值，仅用于后台管理展示）。 */
    public static final String PERSONAL_SPACE_NAME = "个人空间";

    private PersonalSpaceSupport() {
    }

    public static Workspace newPersonalWorkspace(Long ownerId, long maxSize) {
        Workspace ws = new Workspace();
        ws.setId(SnowflakeId.nextId());
        ws.setName(PERSONAL_SPACE_NAME);
        ws.setDescription("每个用户的私有文件空间");
        ws.setOwnerId(ownerId);
        ws.setMaxSize(maxSize);
        ws.setUsedSize(0L);
        ws.setType(WorkspaceType.PERSONAL.getValue());
        return ws;
    }

    public static WorkspaceMember newOwnerMember(Long workspaceId, Long userId) {
        WorkspaceMember member = new WorkspaceMember();
        member.setId(SnowflakeId.nextId());
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(WorkspaceRole.OWNER.getValue());
        return member;
    }

    public static boolean isPersonal(Workspace workspace) {
        return workspace != null && WorkspaceType.PERSONAL.getValue().equals(workspace.getType());
    }
}
