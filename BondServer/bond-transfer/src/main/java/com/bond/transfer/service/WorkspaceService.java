package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.dto.WorkspaceDTO;
import com.bond.common.dto.WorkspaceFileDTO;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.enums.WorkspaceRole;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.SnowflakeId;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final WorkspaceFileMapper fileMapper;
    private final MinioService minioService;
    private final ConfigService configService;

    @Transactional
    public WorkspaceDTO createWorkspace(Long userId, String name, String description) {
        // ── Creation limit ──
        int maxCreate = configService.getInt("workspace.max_create", 3);
        long createdCount = workspaceMapper.selectCount(
                new LambdaQueryWrapper<Workspace>().eq(Workspace::getOwnerId, userId));
        if (createdCount >= maxCreate) {
            throw new BusinessException(400, "已达到最大创建工作区数量(" + maxCreate + ")");
        }

        // ── Join limit ──
        int maxJoin = configService.getInt("workspace.max_join", 10);
        long joinCount = memberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getUserId, userId));
        if (joinCount >= maxJoin) {
            throw new BusinessException(400, "已达到最大加入工作区数量(" + maxJoin + ")");
        }

        long maxSize = configService.getLong("workspace.max_size", 5368709120L);

        Workspace ws = new Workspace();
        ws.setId(SnowflakeId.nextId());
        ws.setName(name);
        ws.setDescription(description);
        ws.setOwnerId(userId);
        ws.setMaxSize(maxSize);
        ws.setUsedSize(0L);
        workspaceMapper.insert(ws);

        WorkspaceMember member = new WorkspaceMember();
        member.setId(SnowflakeId.nextId());
        member.setWorkspaceId(ws.getId());
        member.setUserId(userId);
        member.setRole(WorkspaceRole.OWNER.getValue());
        memberMapper.insert(member);

        return toDTO(ws);
    }

    public List<WorkspaceDTO> listWorkspaces(Long userId) {
        List<WorkspaceMember> memberships = memberMapper.selectList(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getUserId, userId));
        List<Long> wsIds = memberships.stream().map(WorkspaceMember::getWorkspaceId).collect(Collectors.toList());
        if (wsIds.isEmpty()) return List.of();
        return workspaceMapper.selectBatchIds(wsIds).stream().map(this::toDTO).collect(Collectors.toList());
    }

    public WorkspaceDTO getWorkspace(Long workspaceId, Long userId) {
        checkMember(workspaceId, userId);
        return toDTO(getWorkspaceEntity(workspaceId));
    }

    public WorkspaceDTO updateWorkspace(Long workspaceId, Long userId, String name, String description) {
        checkRole(workspaceId, userId, WorkspaceRole.OWNER, WorkspaceRole.ADMIN);
        Workspace ws = getWorkspaceEntity(workspaceId);
        if (name != null) ws.setName(name);
        if (description != null) ws.setDescription(description);
        workspaceMapper.updateById(ws);
        return toDTO(ws);
    }

    public void deleteWorkspace(Long workspaceId, Long userId) {
        checkRole(workspaceId, userId, WorkspaceRole.OWNER);
        List<WorkspaceFile> files = fileMapper.selectList(
                new LambdaQueryWrapper<WorkspaceFile>().eq(WorkspaceFile::getWorkspaceId, workspaceId));
        for (WorkspaceFile file : files) {
            try { minioService.deleteObject(file.getMinioPath()); } catch (Exception ignored) {}
        }
        fileMapper.delete(new LambdaQueryWrapper<WorkspaceFile>().eq(WorkspaceFile::getWorkspaceId, workspaceId));
        memberMapper.delete(new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getWorkspaceId, workspaceId));
        workspaceMapper.deleteById(workspaceId);
    }

    public void addMember(Long workspaceId, Long userId, Long targetUserId, WorkspaceRole role) {
        checkRole(workspaceId, userId, WorkspaceRole.OWNER, WorkspaceRole.ADMIN);

        // ── Member limit ──
        int maxMembers = configService.getInt("workspace.max_members", 20);
        long memberCount = memberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getWorkspaceId, workspaceId));
        if (memberCount >= maxMembers) {
            throw new BusinessException(400, "工作区成员已满(" + maxMembers + ")");
        }

        WorkspaceMember existing = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, targetUserId));
        if (existing != null) return;

        WorkspaceMember member = new WorkspaceMember();
        member.setId(SnowflakeId.nextId());
        member.setWorkspaceId(workspaceId);
        member.setUserId(targetUserId);
        member.setRole(role.getValue());
        memberMapper.insert(member);
    }

    public void removeMember(Long workspaceId, Long userId, Long targetUserId) {
        checkRole(workspaceId, userId, WorkspaceRole.OWNER, WorkspaceRole.ADMIN);
        memberMapper.delete(new LambdaQueryWrapper<WorkspaceMember>()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, targetUserId));
    }

    public List<WorkspaceFileDTO> listFiles(Long workspaceId, Long userId, String parentPath) {
        checkMember(workspaceId, userId);
        String path = parentPath != null ? parentPath : "/";
        return fileMapper.selectList(
                new LambdaQueryWrapper<WorkspaceFile>()
                        .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                        .eq(WorkspaceFile::getParentPath, path)
                        .orderByAsc(WorkspaceFile::getIsDirectory).orderByAsc(WorkspaceFile::getFileName))
                .stream().map(this::toFileDTO).collect(Collectors.toList());
    }

    public WorkspaceFileDTO uploadFile(Long workspaceId, Long userId, String parentPath, MultipartFile file) throws Exception {
        checkMember(workspaceId, userId);

        // ── File size limit ──
        long maxFileSize = configService.getLong("workspace.max_file_size", 2147483648L);
        if (file.getSize() > maxFileSize) {
            throw new BusinessException(400, "文件超过最大限制(" + maxFileSize / 1073741824L + "GB)");
        }

        // ── Storage limit ──
        Workspace ws = getWorkspaceEntity(workspaceId);
        if (ws.getUsedSize() + file.getSize() > ws.getMaxSize()) {
            throw new BusinessException(400, "工作区存储空间不足");
        }

        String minioPath = "workspaces/" + workspaceId + "/" + UUID.randomUUID() + "/" + file.getOriginalFilename();

        minioService.uploadObject(minioPath, file.getInputStream(), file.getSize(), file.getContentType());

        WorkspaceFile wf = new WorkspaceFile();
        wf.setId(SnowflakeId.nextId());
        wf.setWorkspaceId(workspaceId);
        wf.setFileName(file.getOriginalFilename());
        wf.setFileSize(file.getSize());
        wf.setMinioPath(minioPath);
        wf.setContentType(file.getContentType());
        wf.setUploadedBy(userId);
        wf.setParentPath(parentPath != null ? parentPath : "/");
        wf.setIsDirectory(false);
        fileMapper.insert(wf);

        ws.setUsedSize(ws.getUsedSize() + file.getSize());
        workspaceMapper.updateById(ws);

        return toFileDTO(wf);
    }

    public InputStream downloadFile(Long workspaceId, Long userId, Long fileId) throws Exception {
        checkMember(workspaceId, userId);
        WorkspaceFile wf = fileMapper.selectById(fileId);
        if (wf == null || !wf.getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(404, "文件不存在");
        }
        return minioService.downloadObject(wf.getMinioPath());
    }

    public void deleteFile(Long workspaceId, Long userId, Long fileId) {
        checkMember(workspaceId, userId);
        WorkspaceFile wf = fileMapper.selectById(fileId);
        if (wf == null || !wf.getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(404, "文件不存在");
        }
        try { minioService.deleteObject(wf.getMinioPath()); } catch (Exception ignored) {}
        Workspace ws = getWorkspaceEntity(workspaceId);
        ws.setUsedSize(Math.max(0, ws.getUsedSize() - wf.getFileSize()));
        workspaceMapper.updateById(ws);
        fileMapper.deleteById(fileId);
    }

    private void checkMember(Long workspaceId, Long userId) {
        WorkspaceMember member = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, userId));
        if (member == null) {
            throw new BusinessException(403, "无权访问该工作区");
        }
    }

    private void checkRole(Long workspaceId, Long userId, WorkspaceRole... roles) {
        WorkspaceMember member = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, userId));
        if (member == null) {
            throw new BusinessException(403, "无权访问该工作区");
        }
        for (WorkspaceRole role : roles) {
            if (member.getRole().equals(role.getValue())) return;
        }
        throw new BusinessException(403, "权限不足");
    }

    private Workspace getWorkspaceEntity(Long id) {
        Workspace ws = workspaceMapper.selectById(id);
        if (ws == null) throw new BusinessException(404, "工作区不存在");
        return ws;
    }

    private WorkspaceDTO toDTO(Workspace ws) {
        WorkspaceDTO dto = new WorkspaceDTO();
        dto.setId(ws.getId());
        dto.setName(ws.getName());
        dto.setDescription(ws.getDescription());
        dto.setOwnerId(ws.getOwnerId());
        dto.setMaxSize(ws.getMaxSize());
        dto.setUsedSize(ws.getUsedSize());
        dto.setCreatedAt(ws.getCreatedAt() != null ? ws.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
        return dto;
    }

    private WorkspaceFileDTO toFileDTO(WorkspaceFile wf) {
        WorkspaceFileDTO dto = new WorkspaceFileDTO();
        dto.setId(wf.getId());
        dto.setWorkspaceId(wf.getWorkspaceId());
        dto.setFileName(wf.getFileName());
        dto.setFileSize(wf.getFileSize());
        dto.setMinioPath(wf.getMinioPath());
        dto.setContentType(wf.getContentType());
        dto.setUploadedBy(wf.getUploadedBy());
        dto.setParentPath(wf.getParentPath());
        dto.setIsDirectory(wf.getIsDirectory());
        dto.setCreatedAt(wf.getCreatedAt() != null ? wf.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
        return dto;
    }
}
