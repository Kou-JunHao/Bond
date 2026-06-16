package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.dto.ChunkDTO;
import com.bond.common.dto.TransferTaskDTO;
import com.bond.common.entity.TransferChunk;
import com.bond.common.entity.TransferTask;
import com.bond.common.enums.TransferStatus;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.SnowflakeId;
import com.bond.transfer.mapper.TransferChunkMapper;
import com.bond.transfer.mapper.TransferTaskMapper;
import io.minio.messages.Part;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
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

    @Transactional
    public TransferTaskDTO createTask(Long senderId, Long receiverId, Long senderDeviceId,
                                      Long receiverDeviceId, String fileName, long fileSize,
                                      int chunkSize, String encryptedKey) {
        int chunkCount = (int) Math.ceil((double) fileSize / chunkSize);
        String minioPath = "transfers/" + SnowflakeId.nextId() + "/" + fileName;

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
        task.setExpiresAt(LocalDateTime.now().plusDays(7));
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

    public String initMultipartUpload(Long taskId) throws Exception {
        TransferTask task = getTaskEntity(taskId);
        return minioService.initMultipartUpload(task.getMinioPath());
    }

    public ChunkDTO uploadChunk(Long taskId, int chunkIndex, String uploadId, InputStream data, long size) throws Exception {
        TransferTask task = getTaskEntity(taskId);
        String etag = minioService.uploadPart(task.getMinioPath(), uploadId, chunkIndex + 1, data, size);

        TransferChunk chunk = chunkMapper.selectOne(
                new LambdaQueryWrapper<TransferChunk>()
                        .eq(TransferChunk::getTaskId, taskId)
                        .eq(TransferChunk::getChunkIndex, chunkIndex));
        if (chunk != null) {
            chunk.setEtag(etag);
            chunk.setStatus(1);
            chunkMapper.updateById(chunk);
        }

        task.setUploadedChunks(task.getUploadedChunks() + 1);
        task.setStatus(TransferStatus.UPLOADING.getValue());
        taskMapper.updateById(task);

        return toChunkDTO(chunk);
    }

    public void completeTask(Long taskId) throws Exception {
        TransferTask task = getTaskEntity(taskId);

        List<TransferChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<TransferChunk>()
                        .eq(TransferChunk::getTaskId, taskId)
                        .orderByAsc(TransferChunk::getChunkIndex));

        Part[] parts = chunks.stream()
                .filter(c -> c.getEtag() != null)
                .map(c -> Part.builder()
                        .partNumber(c.getChunkIndex() + 1)
                        .etag(c.getEtag())
                        .build())
                .toArray(Part[]::new);

        String uploadId = minioService.initMultipartUpload(task.getMinioPath());
        minioService.completeMultipartUpload(task.getMinioPath(), uploadId, parts);

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

    public TransferTaskDTO getTask(Long taskId) {
        return toDTO(getTaskEntity(taskId));
    }

    public List<ChunkDTO> getChunkStatus(Long taskId) {
        return chunkMapper.selectList(
                new LambdaQueryWrapper<TransferChunk>()
                        .eq(TransferChunk::getTaskId, taskId)
                        .orderByAsc(TransferChunk::getChunkIndex))
                .stream().map(this::toChunkDTO).collect(Collectors.toList());
    }

    public InputStream downloadChunk(Long taskId, int chunkIndex) throws Exception {
        TransferTask task = getTaskEntity(taskId);
        return minioService.downloadObject(task.getMinioPath() + ".part" + chunkIndex);
    }

    public void deleteTask(Long taskId) {
        TransferTask task = getTaskEntity(taskId);
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
}
