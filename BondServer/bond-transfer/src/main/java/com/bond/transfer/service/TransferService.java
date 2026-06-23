package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.dto.ChunkDTO;
import com.bond.common.dto.TransferTaskDTO;
import com.bond.common.entity.TransferChunk;
import com.bond.common.entity.TransferTask;
import com.bond.common.entity.Friendship;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.enums.TransferStatus;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.SnowflakeId;
import com.bond.transfer.mapper.FriendshipMapper;
import com.bond.transfer.mapper.TransferChunkMapper;
import com.bond.transfer.mapper.TransferTaskMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferTaskMapper taskMapper;
    private final TransferChunkMapper chunkMapper;
    private final MinioService minioService;
    private final ConfigService configService;
    private final FriendshipMapper friendshipMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;

    @Transactional
    public TransferTaskDTO createTask(Long senderId, Long receiverId, Long senderDeviceId,
                                      Long receiverDeviceId, String fileName, long fileSize,
                                      int chunkSize, String encryptedKey) {
        // ── Config-driven limits ──
        long maxFileSize = configService.getLong("transfer.max_file_size", 5368709120L);
        if (fileSize > maxFileSize) {
            throw new BusinessException(400, "文件超过最大限制(" + formatSize(maxFileSize) + ")");
        }

        // ── Cross-network permission check ──
        boolean requireFriend = configService.getBoolean("transfer.require_friend", true);
        if (requireFriend) {
            boolean allowed = areFriends(senderId, receiverId)
                    || areInSameWorkspace(senderId, receiverId);
            if (!allowed) {
                throw new BusinessException(403, "跨网传输需要好友关系或同工作区");
            }
        }

        int chunkCount = (int) Math.ceil((double) fileSize / chunkSize);
        String minioPath = "transfers/" + SnowflakeId.nextId() + "/" + fileName;

        int expireDays = configService.getInt("transfer.task_expire_days", 7);

        TransferTask task = new TransferTask();
        task.setId(SnowflakeId.nextId());
        task.setSenderId(senderId);
        task.setReceiverId(receiverId);
        task.setSenderDeviceId(senderDeviceId);
        task.setReceiverDeviceId(receiverDeviceId);
        task.setFileName(fileName);
        task.setFileSize(fileSize);
        task.setChunkSize(chunkSize);
        task.setChunkCount(chunkCount);
        task.setMinioPath(minioPath);
        task.setEncryptedKey(encryptedKey);
        task.setStatus(TransferStatus.PENDING.getValue());
        task.setUploadedChunks(0);
        task.setExpiresAt(LocalDateTime.now().plusDays(expireDays));
        taskMapper.insert(task);

        for (int i = 0; i < chunkCount; i++) {
            TransferChunk chunk = new TransferChunk();
            chunk.setId(SnowflakeId.nextId());
            chunk.setTaskId(task.getId());
            chunk.setChunkIndex(i);
            long remaining = fileSize - (long) i * chunkSize;
            chunk.setChunkSize(Math.min(remaining, chunkSize));
            chunk.setStatus(0);
            chunkMapper.insert(chunk);
        }

        return toDTO(task);
    }

    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/bond-transfer";

    public String initMultipartUpload(Long taskId, Long userId) {
        TransferTask task = getTaskEntity(taskId);
        checkOwnership(task, userId);
        // Create temp file for chunk assembly
        Path tempPath = Path.of(TEMP_DIR, taskId + ".tmp");
        try {
            Files.createDirectories(tempPath.getParent());
            Files.write(tempPath, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException(500, "创建临时文件失败");
        }
        return taskId.toString();
    }

    public ChunkDTO uploadChunk(Long taskId, int chunkIndex, String uploadId, InputStream data, long size, Long userId) throws Exception {
        TransferTask task = getTaskEntity(taskId);
        checkOwnership(task, userId);
        int chunkSize = task.getChunkSize();
        long offset = (long) chunkIndex * chunkSize;

        Path tempPath = Path.of(TEMP_DIR, taskId + ".tmp");
        try (RandomAccessFile raf = new RandomAccessFile(tempPath.toFile(), "rw")) {
            raf.seek(offset);
            byte[] buf = new byte[81920];
            int read;
            while ((read = data.read(buf)) != -1) {
                raf.write(buf, 0, read);
            }
        }

        TransferChunk chunk = chunkMapper.selectOne(
                new LambdaQueryWrapper<TransferChunk>()
                        .eq(TransferChunk::getTaskId, taskId)
                        .eq(TransferChunk::getChunkIndex, chunkIndex));
        if (chunk == null) {
            throw new BusinessException(404, "分片不存在: chunkIndex=" + chunkIndex);
        }
        chunk.setEtag("chunk-" + chunkIndex);
        chunk.setStatus(1);
        chunkMapper.updateById(chunk);

        task.setUploadedChunks(task.getUploadedChunks() + 1);
        task.setStatus(TransferStatus.UPLOADING.getValue());
        taskMapper.updateById(task);

        return toChunkDTO(chunk);
    }

    public void completeTask(Long taskId, Long userId) throws Exception {
        TransferTask task = getTaskEntity(taskId);
        checkOwnership(task, userId);
        Path tempPath = Path.of(TEMP_DIR, taskId + ".tmp");

        try (InputStream is = Files.newInputStream(tempPath)) {
            minioService.uploadObject(task.getMinioPath(), is, task.getFileSize(), "application/octet-stream");
        }

        // Cleanup temp file
        Files.deleteIfExists(tempPath);

        task.setStatus(TransferStatus.COMPLETED.getValue());
        taskMapper.updateById(task);
    }

    public List<TransferTaskDTO> listTasks(Long userId, String direction) {
        LambdaQueryWrapper<TransferTask> wrapper = new LambdaQueryWrapper<>();
        if ("sent".equals(direction)) {
            wrapper.eq(TransferTask::getSenderId, userId);
        } else if ("received".equals(direction)) {
            wrapper.eq(TransferTask::getReceiverId, userId);
        } else {
            wrapper.and(w -> w.eq(TransferTask::getSenderId, userId).or().eq(TransferTask::getReceiverId, userId));
        }
        wrapper.orderByDesc(TransferTask::getCreatedAt);
        return taskMapper.selectList(wrapper).stream().map(this::toDTO).collect(Collectors.toList());
    }

    public TransferTaskDTO getTask(Long taskId, Long userId) {
        TransferTask task = getTaskEntity(taskId);
        checkOwnership(task, userId);
        return toDTO(task);
    }

    public List<ChunkDTO> getChunkStatus(Long taskId) {
        return chunkMapper.selectList(
                new LambdaQueryWrapper<TransferChunk>()
                        .eq(TransferChunk::getTaskId, taskId)
                        .orderByAsc(TransferChunk::getChunkIndex))
                .stream().map(this::toChunkDTO).collect(Collectors.toList());
    }

    public InputStream downloadFile(Long taskId) throws Exception {
        TransferTask task = getTaskEntity(taskId);
        return minioService.downloadObject(task.getMinioPath());
    }

    public void deleteTask(Long taskId, Long userId) {
        TransferTask task = getTaskEntity(taskId);
        checkOwnership(task, userId);
        try {
            minioService.deleteObject(task.getMinioPath());
        } catch (Exception e) {
            log.warn("Failed to delete minio object: {}", task.getMinioPath(), e);
        }
        chunkMapper.delete(new LambdaQueryWrapper<TransferChunk>().eq(TransferChunk::getTaskId, taskId));
        taskMapper.deleteById(taskId);
    }

    private TransferTask getTaskEntity(Long taskId) {
        TransferTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "传输任务不存在");
        }
        return task;
    }

    private void checkOwnership(TransferTask task, Long userId) {
        if (!task.getSenderId().equals(userId) && !task.getReceiverId().equals(userId)) {
            throw new BusinessException(403, "无权操作此传输任务");
        }
    }

    private TransferTaskDTO toDTO(TransferTask task) {
        TransferTaskDTO dto = new TransferTaskDTO();
        dto.setId(task.getId());
        dto.setSenderId(task.getSenderId());
        dto.setReceiverId(task.getReceiverId());
        dto.setSenderDeviceId(task.getSenderDeviceId());
        dto.setReceiverDeviceId(task.getReceiverDeviceId());
        dto.setFileName(task.getFileName());
        dto.setFileSize(task.getFileSize());
        dto.setChunkSize(task.getChunkSize());
        dto.setChunkCount(task.getChunkCount());
        dto.setMinioPath(task.getMinioPath());
        dto.setEncryptedKey(task.getEncryptedKey());
        dto.setStatus(task.getStatus());
        dto.setUploadedChunks(task.getUploadedChunks());
        dto.setWorkspaceId(task.getWorkspaceId());
        dto.setExpiresAt(task.getExpiresAt() != null ? task.getExpiresAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
        dto.setCreatedAt(task.getCreatedAt() != null ? task.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
        return dto;
    }

    private ChunkDTO toChunkDTO(TransferChunk chunk) {
        ChunkDTO dto = new ChunkDTO();
        dto.setId(chunk.getId());
        dto.setTaskId(chunk.getTaskId());
        dto.setChunkIndex(chunk.getChunkIndex());
        dto.setChunkSize(chunk.getChunkSize());
        dto.setEtag(chunk.getEtag());
        dto.setStatus(chunk.getStatus());
        return dto;
    }

    private boolean areFriends(Long userId1, Long userId2) {
        Friendship f = friendshipMapper.selectOne(
                new LambdaQueryWrapper<Friendship>()
                        .eq(Friendship::getRequesterId, userId1)
                        .eq(Friendship::getAddresseeId, userId2)
                        .eq(Friendship::getStatus, 1));
        if (f != null) return true;
        f = friendshipMapper.selectOne(
                new LambdaQueryWrapper<Friendship>()
                        .eq(Friendship::getRequesterId, userId2)
                        .eq(Friendship::getAddresseeId, userId1)
                        .eq(Friendship::getStatus, 1));
        return f != null;
    }

    private boolean areInSameWorkspace(Long userId1, Long userId2) {
        List<WorkspaceMember> m1 = workspaceMemberMapper.selectList(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getUserId, userId1));
        List<WorkspaceMember> m2 = workspaceMemberMapper.selectList(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getUserId, userId2));
        for (WorkspaceMember a : m1) {
            for (WorkspaceMember b : m2) {
                if (a.getWorkspaceId().equals(b.getWorkspaceId())) return true;
            }
        }
        return false;
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1073741824L) return (bytes / 1073741824L) + "GB";
        if (bytes >= 1048576L) return (bytes / 1048576L) + "MB";
        return (bytes / 1024L) + "KB";
    }
}
