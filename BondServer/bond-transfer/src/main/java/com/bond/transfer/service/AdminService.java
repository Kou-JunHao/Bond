package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.dto.*;
import com.bond.common.entity.*;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserMapper userMapper;
    private final DeviceMapper deviceMapper;
    private final TransferTaskMapper transferTaskMapper;
    private final TransferChunkMapper transferChunkMapper;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final WorkspaceFileMapper workspaceFileMapper;
    private final ConfigService configService;
    private final MinioService minioService;

    public DashboardDTO getDashboard() {
        DashboardDTO dto = new DashboardDTO();
        dto.setTotalUsers(userMapper.selectCount(null));
        dto.setActiveUsers(deviceMapper.selectCount(
                new LambdaQueryWrapper<Device>().eq(Device::getIsOnline, true)));
        dto.setTotalTransfers(transferTaskMapper.selectCount(null));
        dto.setCompletedTransfers(transferTaskMapper.selectCount(
                new LambdaQueryWrapper<TransferTask>().eq(TransferTask::getStatus, 2)));
        dto.setTotalWorkspaces(workspaceMapper.selectCount(null));

        List<WorkspaceFile> files = workspaceFileMapper.selectList(null);
        dto.setTotalStorageUsed(files.stream().mapToLong(WorkspaceFile::getFileSize).sum());

        dto.setTransferTrend(getTransferTrend(7));
        return dto;
    }

    public Map<String, Long> getTransferTrend(int days) {
        Map<String, Long> trend = new HashMap<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDateTime dayStart = LocalDateTime.now().minusDays(i).toLocalDate().atStartOfDay();
            LocalDateTime dayEnd = dayStart.plusDays(1);
            Long count = transferTaskMapper.selectCount(
                    new LambdaQueryWrapper<TransferTask>()
                            .ge(TransferTask::getCreatedAt, dayStart)
                            .lt(TransferTask::getCreatedAt, dayEnd));
            trend.put(dayStart.toLocalDate().toString(), count);
        }
        return trend;
    }

    public List<AdminUserDTO> listUsers(int page, int size, String keyword) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(User::getUsername, keyword).or().like(User::getNickname, keyword));
        }
        wrapper.orderByDesc(User::getCreatedAt);
        wrapper.last("LIMIT " + size + " OFFSET " + (page - 1) * size);

        return userMapper.selectList(wrapper).stream().map(u -> {
            AdminUserDTO dto = new AdminUserDTO();
            dto.setId(u.getId());
            dto.setUsername(u.getUsername());
            dto.setNickname(u.getNickname());
            dto.setAvatarUrl(u.getAvatarUrl());
            dto.setStatus(u.getStatus());
            dto.setIsAdmin(u.getIsAdmin());
            dto.setCreatedAt(u.getCreatedAt() != null
                    ? u.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
            dto.setDeviceCount(deviceMapper.selectCount(
                    new LambdaQueryWrapper<Device>().eq(Device::getUserId, u.getId())));
            dto.setTransferCount(transferTaskMapper.selectCount(
                    new LambdaQueryWrapper<TransferTask>()
                            .and(w -> w.eq(TransferTask::getSenderId, u.getId())
                                    .or().eq(TransferTask::getReceiverId, u.getId()))));
            return dto;
        }).collect(Collectors.toList());
    }

    public void setUserStatus(Long userId, int status) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        user.setStatus(status);
        userMapper.updateById(user);
    }

    public void setAdmin(Long userId, boolean isAdmin) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        user.setIsAdmin(isAdmin);
        userMapper.updateById(user);
    }

    public void deleteUser(Long userId) {
        List<TransferTask> tasks = transferTaskMapper.selectList(
                new LambdaQueryWrapper<TransferTask>()
                        .and(w -> w.eq(TransferTask::getSenderId, userId).or().eq(TransferTask::getReceiverId, userId)));
        for (TransferTask task : tasks) {
            try { minioService.deleteObject(task.getMinioPath()); } catch (Exception ignored) {}
            transferChunkMapper.delete(new LambdaQueryWrapper<TransferChunk>().eq(TransferChunk::getTaskId, task.getId()));
        }
        transferTaskMapper.delete(new LambdaQueryWrapper<TransferTask>()
                .and(w -> w.eq(TransferTask::getSenderId, userId).or().eq(TransferTask::getReceiverId, userId)));
        userMapper.deleteById(userId);
    }

    public List<TransferTaskDTO> listTransfers(int page, int size, Integer status) {
        LambdaQueryWrapper<TransferTask> wrapper = new LambdaQueryWrapper<>();
        if (status != null) wrapper.eq(TransferTask::getStatus, status);
        wrapper.orderByDesc(TransferTask::getCreatedAt);
        wrapper.last("LIMIT " + size + " OFFSET " + (page - 1) * size);

        return transferTaskMapper.selectList(wrapper).stream().map(t -> {
            TransferTaskDTO dto = new TransferTaskDTO();
            dto.setId(t.getId());
            dto.setSenderId(t.getSenderId());
            dto.setReceiverId(t.getReceiverId());
            dto.setFileName(t.getFileName());
            dto.setFileSize(t.getFileSize());
            dto.setChunkCount(t.getChunkCount());
            dto.setUploadedChunks(t.getUploadedChunks());
            dto.setStatus(t.getStatus());
            dto.setCreatedAt(t.getCreatedAt() != null
                    ? t.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
            return dto;
        }).collect(Collectors.toList());
    }

    public void deleteTransfer(Long taskId) {
        TransferTask task = transferTaskMapper.selectById(taskId);
        if (task != null) {
            try { minioService.deleteObject(task.getMinioPath()); } catch (Exception ignored) {}
            transferChunkMapper.delete(new LambdaQueryWrapper<TransferChunk>().eq(TransferChunk::getTaskId, taskId));
            transferTaskMapper.deleteById(taskId);
        }
    }

    public int cleanupExpired() {
        List<TransferTask> expired = transferTaskMapper.selectList(
                new LambdaQueryWrapper<TransferTask>()
                        .lt(TransferTask::getExpiresAt, LocalDateTime.now())
                        .ne(TransferTask::getStatus, 2));
        for (TransferTask task : expired) {
            try { minioService.deleteObject(task.getMinioPath()); } catch (Exception ignored) {}
            transferChunkMapper.delete(new LambdaQueryWrapper<TransferChunk>().eq(TransferChunk::getTaskId, task.getId()));
        }
        return transferTaskMapper.delete(
                new LambdaQueryWrapper<TransferTask>()
                        .lt(TransferTask::getExpiresAt, LocalDateTime.now())
                        .ne(TransferTask::getStatus, 2));
    }

    public List<WorkspaceDTO> listWorkspaces(int page, int size) {
        LambdaQueryWrapper<Workspace> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Workspace::getCreatedAt);
        wrapper.last("LIMIT " + size + " OFFSET " + (page - 1) * size);

        return workspaceMapper.selectList(wrapper).stream().map(w -> {
            WorkspaceDTO dto = new WorkspaceDTO();
            dto.setId(w.getId());
            dto.setName(w.getName());
            dto.setDescription(w.getDescription());
            dto.setOwnerId(w.getOwnerId());
            dto.setMaxSize(w.getMaxSize());
            dto.setUsedSize(w.getUsedSize());
            dto.setCreatedAt(w.getCreatedAt() != null
                    ? w.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
            return dto;
        }).collect(Collectors.toList());
    }

    public void deleteWorkspace(Long workspaceId) {
        var files = workspaceFileMapper.selectList(
                new LambdaQueryWrapper<WorkspaceFile>().eq(WorkspaceFile::getWorkspaceId, workspaceId));
        for (var file : files) {
            try { minioService.deleteObject(file.getMinioPath()); } catch (Exception ignored) {}
        }
        workspaceFileMapper.delete(
                new LambdaQueryWrapper<WorkspaceFile>().eq(WorkspaceFile::getWorkspaceId, workspaceId));
        workspaceMemberMapper.delete(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getWorkspaceId, workspaceId));
        workspaceMapper.deleteById(workspaceId);
    }

    public AdminUserDTO getUserDetail(Long userId) {
        User u = userMapper.selectById(userId);
        if (u == null) throw new BusinessException(404, "用户不存在");
        AdminUserDTO dto = new AdminUserDTO();
        dto.setId(u.getId());
        dto.setUsername(u.getUsername());
        dto.setNickname(u.getNickname());
        dto.setAvatarUrl(u.getAvatarUrl());
        dto.setStatus(u.getStatus());
        dto.setIsAdmin(u.getIsAdmin());
        dto.setCreatedAt(u.getCreatedAt() != null
                ? u.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
        dto.setDeviceCount(deviceMapper.selectCount(
                new LambdaQueryWrapper<Device>().eq(Device::getUserId, userId)));
        dto.setTransferCount(transferTaskMapper.selectCount(
                new LambdaQueryWrapper<TransferTask>()
                        .and(w -> w.eq(TransferTask::getSenderId, userId).or().eq(TransferTask::getReceiverId, userId))));
        return dto;
    }

    public Map<String, Long> getUserStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", userMapper.selectCount(null));
        stats.put("active", deviceMapper.selectCount(
                new LambdaQueryWrapper<Device>().eq(Device::getIsOnline, true)));
        stats.put("disabled", userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getStatus, 0)));
        stats.put("admin", userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getIsAdmin, true)));
        return stats;
    }

    public Map<String, Long> getTransferStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", transferTaskMapper.selectCount(null));
        stats.put("pending", transferTaskMapper.selectCount(
                new LambdaQueryWrapper<TransferTask>().eq(TransferTask::getStatus, 0)));
        stats.put("uploading", transferTaskMapper.selectCount(
                new LambdaQueryWrapper<TransferTask>().eq(TransferTask::getStatus, 1)));
        stats.put("completed", transferTaskMapper.selectCount(
                new LambdaQueryWrapper<TransferTask>().eq(TransferTask::getStatus, 2)));
        stats.put("failed", transferTaskMapper.selectCount(
                new LambdaQueryWrapper<TransferTask>().eq(TransferTask::getStatus, 3)));
        return stats;
    }

    public Map<String, Object> getWorkspaceDetail(Long workspaceId) {
        Workspace ws = workspaceMapper.selectById(workspaceId);
        if (ws == null) throw new BusinessException(404, "工作区不存在");

        Map<String, Object> detail = new HashMap<>();
        detail.put("id", ws.getId());
        detail.put("name", ws.getName());
        detail.put("description", ws.getDescription());
        detail.put("ownerId", ws.getOwnerId());
        detail.put("maxSize", ws.getMaxSize());
        detail.put("usedSize", ws.getUsedSize());
        detail.put("memberCount", workspaceMemberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getWorkspaceId, workspaceId)));
        detail.put("fileCount", workspaceFileMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceFile>().eq(WorkspaceFile::getWorkspaceId, workspaceId)));
        return detail;
    }
}
