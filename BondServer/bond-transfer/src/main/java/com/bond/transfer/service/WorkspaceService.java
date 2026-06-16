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

    @Transactional
    public WorkspaceDTO createWorkspace(Long userId, String name, String description) {
        Workspace ws = new Workspace();
        ws.setId(SnowflakeId.nextId());
        ws.setName(name);
        ws.setDescription(description);
        ws.setOwnerId(userId);
        ws.setMaxSize(10737418240L);
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

        Workspace ws = getWorkspaceEntity(workspaceId);
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
