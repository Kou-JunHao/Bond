package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.dto.InvitationDTO;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceInvitation;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.enums.WorkspaceRole;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.SnowflakeId;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceInvitationMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkspaceInvitationService {

    private final WorkspaceInvitationMapper invitationMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final WorkspaceMapper workspaceMapper;
    private final UserMapper userMapper;
    private final ConfigService configService;

    public String generateInviteLink(Long workspaceId, Long inviterId) {
        checkMemberRole(workspaceId, inviterId);

        int maxJoin = configService.getInt("workspace.max_join", 10);
        long joinCount = memberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getUserId, inviterId));
        // we only check target user limit on accept, not on link generation

        String token = UUID.randomUUID().toString().replace("-", "");
        int expireDays = configService.getInt("workspace.invite_expire_days", 7);

        WorkspaceInvitation inv = new WorkspaceInvitation();
        inv.setId(SnowflakeId.nextId());
        inv.setWorkspaceId(workspaceId);
        inv.setInviterId(inviterId);
        inv.setInviteeId(null); // generic link, no specific invitee
        inv.setInviteToken(token);
        inv.setRole(WorkspaceRole.MEMBER.getValue());
        inv.setStatus(0);
        inv.setExpiresAt(LocalDateTime.now().plusDays(expireDays));
        invitationMapper.insert(inv);

        return token;
    }

    public InvitationDTO verifyInviteLink(String token) {
        WorkspaceInvitation inv = invitationMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceInvitation>()
                        .eq(WorkspaceInvitation::getInviteToken, token)
                        .eq(WorkspaceInvitation::getStatus, 0));
        if (inv == null) throw new BusinessException(404, "邀请链接无效或已过期");
        if (inv.getExpiresAt().isBefore(LocalDateTime.now())) {
            inv.setStatus(3); // expired
            invitationMapper.updateById(inv);
            throw new BusinessException(400, "邀请链接已过期");
        }

        Workspace ws = workspaceMapper.selectById(inv.getWorkspaceId());
        var inviter = userMapper.selectById(inv.getInviterId());
        InvitationDTO dto = new InvitationDTO();
        dto.setId(inv.getId());
        dto.setWorkspaceId(inv.getWorkspaceId());
        dto.setWorkspaceName(ws != null ? ws.getName() : "");
        dto.setInviterId(inv.getInviterId());
        dto.setInviterNickname(inviter != null ? inviter.getNickname() : "");
        dto.setInviteToken(token);
        dto.setRole(inv.getRole());
        dto.setStatus(inv.getStatus());
        dto.setExpiresAt(inv.getExpiresAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        return dto;
    }

    public void acceptInviteLink(String token, Long userId) {
        WorkspaceInvitation inv = invitationMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceInvitation>()
                        .eq(WorkspaceInvitation::getInviteToken, token)
                        .eq(WorkspaceInvitation::getStatus, 0));
        if (inv == null) throw new BusinessException(404, "邀请链接无效");
        if (inv.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(400, "邀请链接已过期");
        }

        joinWorkspace(inv.getWorkspaceId(), userId, inv.getRole());

        inv.setStatus(1);
        invitationMapper.updateById(inv);
    }

    public void inviteFriend(Long workspaceId, Long inviterId, Long inviteeId, int role) {
        checkMemberRole(workspaceId, inviterId);

        // Check if already member
        WorkspaceMember existing = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, inviteeId));
        if (existing != null) throw new BusinessException(400, "该用户已是工作区成员");

        int expireDays = configService.getInt("workspace.invite_expire_days", 7);

        // Check for existing pending invitation
        WorkspaceInvitation pending = invitationMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceInvitation>()
                        .eq(WorkspaceInvitation::getWorkspaceId, workspaceId)
                        .eq(WorkspaceInvitation::getInviteeId, inviteeId)
                        .eq(WorkspaceInvitation::getStatus, 0));
        if (pending != null) throw new BusinessException(400, "已发送过邀请，请等待对方处理");

        WorkspaceInvitation inv = new WorkspaceInvitation();
        inv.setId(SnowflakeId.nextId());
        inv.setWorkspaceId(workspaceId);
        inv.setInviterId(inviterId);
        inv.setInviteeId(inviteeId);
        inv.setInviteToken(UUID.randomUUID().toString().replace("-", ""));
        inv.setRole(role);
        inv.setStatus(0);
        inv.setExpiresAt(LocalDateTime.now().plusDays(expireDays));
        invitationMapper.insert(inv);
    }

    public List<InvitationDTO> getPendingInvitations(Long userId) {
        List<WorkspaceInvitation> list = invitationMapper.selectList(
                new LambdaQueryWrapper<WorkspaceInvitation>()
                        .eq(WorkspaceInvitation::getInviteeId, userId)
                        .eq(WorkspaceInvitation::getStatus, 0)
                        .orderByDesc(WorkspaceInvitation::getCreatedAt));

        return list.stream().map(inv -> {
            Workspace ws = workspaceMapper.selectById(inv.getWorkspaceId());
            var inviter = userMapper.selectById(inv.getInviterId());
            InvitationDTO dto = new InvitationDTO();
            dto.setId(inv.getId());
            dto.setWorkspaceId(inv.getWorkspaceId());
            dto.setWorkspaceName(ws != null ? ws.getName() : "");
            dto.setInviterId(inv.getInviterId());
            dto.setInviterNickname(inviter != null ? inviter.getNickname() : "");
            dto.setRole(inv.getRole());
            dto.setStatus(inv.getStatus());
            dto.setExpiresAt(inv.getExpiresAt() != null
                    ? inv.getExpiresAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
            dto.setCreatedAt(inv.getCreatedAt() != null
                    ? inv.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
            return dto;
        }).collect(Collectors.toList());
    }

    public void acceptInvitation(Long invitationId, Long userId) {
        WorkspaceInvitation inv = invitationMapper.selectById(invitationId);
        if (inv == null || !inv.getInviteeId().equals(userId)) {
            throw new BusinessException(404, "邀请不存在");
        }
        if (inv.getStatus() != 0) throw new BusinessException(400, "该邀请已处理");
        if (inv.getExpiresAt().isBefore(LocalDateTime.now())) {
            inv.setStatus(3);
            invitationMapper.updateById(inv);
            throw new BusinessException(400, "邀请已过期");
        }

        joinWorkspace(inv.getWorkspaceId(), userId, inv.getRole());
        inv.setStatus(1);
        invitationMapper.updateById(inv);
    }

    public void rejectInvitation(Long invitationId, Long userId) {
        WorkspaceInvitation inv = invitationMapper.selectById(invitationId);
        if (inv == null || !inv.getInviteeId().equals(userId)) {
            throw new BusinessException(404, "邀请不存在");
        }
        inv.setStatus(2);
        invitationMapper.updateById(inv);
    }

    private void joinWorkspace(Long workspaceId, Long userId, int role) {
        // Check join limit
        int maxJoin = configService.getInt("workspace.max_join", 10);
        long joinCount = memberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getUserId, userId));
        if (joinCount >= maxJoin) {
            throw new BusinessException(400, "已达到最大加入工作区数量(" + maxJoin + ")");
        }

        // Check member limit
        int maxMembers = configService.getInt("workspace.max_members", 20);
        long memberCount = memberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getWorkspaceId, workspaceId));
        if (memberCount >= maxMembers) {
            throw new BusinessException(400, "工作区成员已满(" + maxMembers + ")");
        }

        // Check not already member
        WorkspaceMember existing = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, userId));
        if (existing != null) return; // already a member, silently skip

        WorkspaceMember member = new WorkspaceMember();
        member.setId(SnowflakeId.nextId());
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(role);
        memberMapper.insert(member);
    }

    private void checkMemberRole(Long workspaceId, Long userId) {
        WorkspaceMember member = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, userId));
        if (member == null) throw new BusinessException(403, "不是工作区成员");
        if (member.getRole() > WorkspaceRole.ADMIN.getValue()) {
            throw new BusinessException(403, "需要管理员权限");
        }
    }
}
