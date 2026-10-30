package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.enums.WorkspaceRole;
import com.bond.common.enums.WorkspaceType;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.Entitlements;
import com.bond.transfer.entitlement.PlanLimits;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceInvitationMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作区管理台后端缺口：改角色、配额上限、owner 保护。
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceAdminOpsTest {

    private static final Long WS_ID = 100L;
    private static final Long OWNER_ID = 7L;
    private static final Long ADMIN_ID = 8L;
    private static final Long MEMBER_ID = 9L;
    private static final long GB = 1024L * 1024 * 1024;

    @Mock
    private WorkspaceMapper workspaceMapper;
    @Mock
    private WorkspaceMemberMapper memberMapper;
    @Mock
    private WorkspaceFileMapper fileMapper;
    @Mock
    private WorkspaceInvitationMapper invitationMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private EntitlementResolver entitlementResolver;
    @Mock
    private UsageService usageService;
    @Mock
    private ConfigService configService;

    @InjectMocks
    private WorkspaceService workspaceService;

    // ── 改角色 ──

    @Test
    @DisplayName("owner 可以把成员提升为管理员")
    void ownerCanPromoteMemberToAdmin() {
        when(memberMapper.selectOne(any(Wrapper.class)))
                .thenReturn(member(WS_ID, OWNER_ID, WorkspaceRole.OWNER),
                        member(WS_ID, MEMBER_ID, WorkspaceRole.MEMBER));
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(OWNER_ID, 0L, 5 * GB));

        workspaceService.updateMemberRole(WS_ID, OWNER_ID, MEMBER_ID, WorkspaceRole.ADMIN.getValue());

        ArgumentCaptor<WorkspaceMember> captor = ArgumentCaptor.forClass(WorkspaceMember.class);
        verify(memberMapper).updateById(captor.capture());
        assertEquals(WorkspaceRole.ADMIN.getValue(), captor.getValue().getRole());
    }

    @Test
    @DisplayName("管理员不能任免管理员（涉及 ADMIN 只有 owner 能操作）")
    void adminCannotManageAdminRoles() {
        when(memberMapper.selectOne(any(Wrapper.class)))
                .thenReturn(member(WS_ID, ADMIN_ID, WorkspaceRole.ADMIN),
                        member(WS_ID, MEMBER_ID, WorkspaceRole.ADMIN));
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(OWNER_ID, 0L, 5 * GB));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.updateMemberRole(WS_ID, ADMIN_ID, MEMBER_ID,
                        WorkspaceRole.MEMBER.getValue()));
        assertEquals(403, ex.getCode());
        verify(memberMapper, never()).updateById(any(WorkspaceMember.class));
    }

    @Test
    @DisplayName("owner 角色不可被修改")
    void ownerRoleCannotBeChanged() {
        when(memberMapper.selectOne(any(Wrapper.class)))
                .thenReturn(member(WS_ID, ADMIN_ID, WorkspaceRole.ADMIN));
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(OWNER_ID, 0L, 5 * GB));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.updateMemberRole(WS_ID, ADMIN_ID, OWNER_ID,
                        WorkspaceRole.MEMBER.getValue()));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("不能修改自己的角色")
    void cannotChangeOwnRole() {
        when(memberMapper.selectOne(any(Wrapper.class)))
                .thenReturn(member(WS_ID, ADMIN_ID, WorkspaceRole.ADMIN));
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(OWNER_ID, 0L, 5 * GB));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.updateMemberRole(WS_ID, ADMIN_ID, ADMIN_ID,
                        WorkspaceRole.MEMBER.getValue()));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("目标用户不是成员 → 404")
    void targetMustBeMember() {
        when(memberMapper.selectOne(any(Wrapper.class)))
                .thenReturn(member(WS_ID, OWNER_ID, WorkspaceRole.OWNER), null);
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(OWNER_ID, 0L, 5 * GB));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.updateMemberRole(WS_ID, OWNER_ID, MEMBER_ID,
                        WorkspaceRole.ADMIN.getValue()));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("不能把任何人提升为 owner（0 不可指派）")
    void ownerIsNotAssignable() {
        when(memberMapper.selectOne(any(Wrapper.class)))
                .thenReturn(member(WS_ID, OWNER_ID, WorkspaceRole.OWNER));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.updateMemberRole(WS_ID, OWNER_ID, MEMBER_ID, 0));
        assertEquals(400, ex.getCode());
    }

    // ── 移除成员 ──

    @Test
    @DisplayName("owner 不可被移除（删空间请用 DELETE /api/workspaces/{id}）")
    void ownerCannotBeRemoved() {
        when(memberMapper.selectOne(any(Wrapper.class)))
                .thenReturn(member(WS_ID, ADMIN_ID, WorkspaceRole.ADMIN));
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(OWNER_ID, 0L, 5 * GB));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.removeMember(WS_ID, ADMIN_ID, OWNER_ID));
        assertEquals(400, ex.getCode());
        verify(memberMapper, never()).delete(any(Wrapper.class));
    }

    // ── 配额上限 ──

    @Test
    @DisplayName("配额上限不能超过 owner 档位空间额度")
    void quotaCannotExceedOwnerPlan() {
        when(memberMapper.selectOne(any(Wrapper.class)))
                .thenReturn(member(WS_ID, OWNER_ID, WorkspaceRole.OWNER));
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(OWNER_ID, 0L, 5 * GB));
        when(entitlementResolver.resolve(OWNER_ID)).thenReturn(entitlements(GB));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.updateWorkspace(WS_ID, OWNER_ID, null, null, 2 * GB));
        assertEquals(400, ex.getCode());
        verify(workspaceMapper, never()).updateById(any(Workspace.class));
    }

    @Test
    @DisplayName("配额上限不能低于已用空间")
    void quotaCannotBeBelowUsedSize() {
        when(memberMapper.selectOne(any(Wrapper.class)))
                .thenReturn(member(WS_ID, OWNER_ID, WorkspaceRole.OWNER));
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(OWNER_ID, 1000L, 5 * GB));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.updateWorkspace(WS_ID, OWNER_ID, null, null, 999L));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("合法配额上限生效（PATCH/PUT 同名/描述/配额）")
    void quotaWithinPlanIsApplied() {
        Workspace ws = workspace(OWNER_ID, 0L, 5 * GB);
        when(memberMapper.selectOne(any(Wrapper.class)))
                .thenReturn(member(WS_ID, OWNER_ID, WorkspaceRole.OWNER));
        when(workspaceMapper.selectById(WS_ID)).thenReturn(ws);
        when(entitlementResolver.resolve(OWNER_ID)).thenReturn(entitlements(5 * GB));
        when(workspaceMapper.selectStatsByIds(any())).thenReturn(List.of());

        workspaceService.updateWorkspace(WS_ID, OWNER_ID, "新名字", null, GB);

        assertEquals(GB, ws.getMaxSize());
        assertEquals("新名字", ws.getName());
        verify(workspaceMapper).updateById(ws);
    }

    // ── helpers ──

    private static Workspace workspace(Long ownerId, long usedSize, long maxSize) {
        Workspace ws = new Workspace();
        ws.setId(WS_ID);
        ws.setName("团队空间");
        ws.setOwnerId(ownerId);
        ws.setUsedSize(usedSize);
        ws.setMaxSize(maxSize);
        ws.setType(WorkspaceType.TEAM.getValue());
        return ws;
    }

    private static WorkspaceMember member(Long workspaceId, Long userId, WorkspaceRole role) {
        WorkspaceMember member = new WorkspaceMember();
        member.setId(userId + 1000);
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(role.getValue());
        return member;
    }

    private static Entitlements entitlements(long storageBytes) {
        return new Entitlements("free", new PlanLimits(
                10_737_418_240L, storageBytes, 2_147_483_648L,
                2, 1, 3, 0L, 7, 3, 30, 5, 30), null);
    }
}
