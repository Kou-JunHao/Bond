package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bond.common.dto.WorkspaceDTO;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.enums.WorkspaceRole;
import com.bond.common.enums.WorkspaceType;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceInvitationMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.storage.ObjectStorage;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.UsageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 列表隔离：GET /api/workspaces 不能包含个人空间（否则工作区页会多出「个人空间」）。
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceListIsolationTest {

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

    @Test
    @DisplayName("listWorkspaces 排除 type=personal，只返回团队工作区")
    void listWorkspacesExcludesPersonalSpace() {
        Workspace personal = workspace(10L, "个人空间", WorkspaceType.PERSONAL);
        Workspace team = workspace(11L, "团队空间", WorkspaceType.TEAM);
        WorkspaceMember personalMember = new WorkspaceMember();
        personalMember.setWorkspaceId(10L);
        personalMember.setUserId(7L);
        personalMember.setRole(WorkspaceRole.OWNER.getValue());
        WorkspaceMember teamMember = new WorkspaceMember();
        teamMember.setWorkspaceId(11L);
        teamMember.setUserId(7L);
        teamMember.setRole(WorkspaceRole.OWNER.getValue());

        when(memberMapper.selectList(any(Wrapper.class))).thenReturn(List.of(personalMember, teamMember));
        when(workspaceMapper.selectList(any(Wrapper.class))).thenReturn(List.of(personal, team));

        List<WorkspaceDTO> result = workspaceService.listWorkspaces(7L);

        assertEquals(1, result.size(), "personal workspace must be excluded from /api/workspaces");
        assertEquals("团队空间", result.get(0).getName());
        assertEquals(11L, result.get(0).getId());
    }

    @Test
    @DisplayName("个人空间不允许通过工作区 API 删除")
    void personalSpaceCannotBeDeletedThroughWorkspaceApi() {
        Workspace personal = workspace(10L, "个人空间", WorkspaceType.PERSONAL);
        WorkspaceMember ownerMember = new WorkspaceMember();
        ownerMember.setWorkspaceId(10L);
        ownerMember.setUserId(7L);
        ownerMember.setRole(WorkspaceRole.OWNER.getValue());

        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(ownerMember);
        when(workspaceMapper.selectById(10L)).thenReturn(personal);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.deleteWorkspace(10L, 7L));
        assertEquals(400, ex.getCode());
        verify(workspaceMapper, org.mockito.Mockito.never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("GET /api/workspaces 一次聚合补齐 memberCount/fileCount（不逐工作区 COUNT）")
    void listWorkspacesIncludesAggregatedStats() {
        Workspace team = workspace(11L, "团队空间", WorkspaceType.TEAM);
        WorkspaceMember teamMember = new WorkspaceMember();
        teamMember.setWorkspaceId(11L);
        teamMember.setUserId(7L);
        teamMember.setRole(WorkspaceRole.OWNER.getValue());

        when(memberMapper.selectList(any(Wrapper.class))).thenReturn(List.of(teamMember));
        when(workspaceMapper.selectList(any(Wrapper.class))).thenReturn(List.of(team));
        when(workspaceMapper.selectStatsByIds(any()))
                .thenReturn(List.of(Map.of("workspace_id", 11L, "member_count", 3L, "file_count", 9L)));

        List<WorkspaceDTO> result = workspaceService.listWorkspaces(7L);

        assertEquals(1, result.size());
        assertEquals(3L, result.get(0).getMemberCount());
        assertEquals(9L, result.get(0).getFileCount());
        verify(workspaceMapper, org.mockito.Mockito.times(1)).selectStatsByIds(any());
    }

    @Test
    @DisplayName("getWorkspace 详情同样带 memberCount/fileCount")
    void getWorkspaceIncludesStats() {
        Workspace team = workspace(11L, "团队空间", WorkspaceType.TEAM);
        WorkspaceMember ownerMember = new WorkspaceMember();
        ownerMember.setWorkspaceId(11L);
        ownerMember.setUserId(7L);
        ownerMember.setRole(WorkspaceRole.OWNER.getValue());

        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(ownerMember);
        when(workspaceMapper.selectById(11L)).thenReturn(team);
        when(workspaceMapper.selectStatsByIds(any()))
                .thenReturn(List.of(Map.of("workspace_id", 11L, "member_count", 2L, "file_count", 5L)));

        WorkspaceDTO dto = workspaceService.getWorkspace(11L, 7L);

        assertEquals(2L, dto.getMemberCount());
        assertEquals(5L, dto.getFileCount());
    }

    private static Workspace workspace(long id, String name, WorkspaceType type) {
        Workspace ws = new Workspace();
        ws.setId(id);
        ws.setName(name);
        ws.setOwnerId(7L);
        ws.setMaxSize(5368709120L);
        ws.setUsedSize(0L);
        ws.setType(type.getValue());
        return ws;
    }
}
