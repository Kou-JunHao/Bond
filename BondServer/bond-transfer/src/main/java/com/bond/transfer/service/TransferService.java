package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.dto.ChunkDTO;
import com.bond.common.dto.PageResult;
import com.bond.common.dto.TransferTaskDTO;
import com.bond.common.entity.Device;
import com.bond.common.entity.Friendship;
import com.bond.common.entity.TransferChunk;
import com.bond.common.entity.TransferTask;
import com.bond.common.entity.User;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.enums.TransferStatus;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.SnowflakeId;
import com.bond.transfer.mapper.DeviceMapper;
import com.bond.transfer.mapper.FriendshipMapper;
import com.bond.transfer.mapper.TransferChunkMapper;
import com.bond.transfer.mapper.TransferTaskMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.storage.ObjectStorage;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.Entitlements;
import com.bond.transfer.entitlement.UsageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/bond-transfer";

    private final TransferTaskMapper taskMapper;
    private final TransferChunkMapper chunkMapper;
    private final ObjectStorage objectStorage;
    private final ConfigService configService;
    private final FriendshipMapper friendshipMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final DeviceMapper deviceMapper;
    private final UserMapper userMapper;
    private final EntitlementResolver entitlementResolver;
    private final UsageService usageService;
    /** 分片级写锁：允许不同分片并发上传，但同一分片的重传/并发写必须串行。 */
    private final Map<String, Object> chunkLocks = new ConcurrentHashMap<>();

    /** task-54：任务列表分页默认值/上限。 */
    static final int DEFAULT_TASK_PAGE_SIZE = 20;
    static final int MAX_TASK_PAGE_SIZE = 100;

    @Transactional
    public TransferTaskDTO createTask(Long senderId, Long receiverId, Long senderDeviceId,
                                      Long receiverDeviceId, String fileName, long fileSize,
                                      int chunkSize, String encryptedKey, String transferType) {
        // 单文件上限由 sender 档位决定；档位未给（0=不限）时回退配置
        Entitlements entitlements = entitlementResolver.resolve(senderId);
        long planMaxFileSize = entitlements.limits().maxFileSize();
        long maxFileSize = planMaxFileSize > 0
                ? planMaxFileSize
                : configService.getLong("transfer.max_file_size", 5368709120L);
        if (fileSize > maxFileSize) {
            throw new BusinessException(400, "文件超过最大限制(" + formatSize(maxFileSize) + ")");
        }
        // 分片参数校验：分片大小/分片数异常会在上传阶段造成错位或资源打满
        if (fileSize <= 0) {
            throw new BusinessException(400, "文件大小必须大于 0");
        }
        if (chunkSize <= 0) {
            throw new BusinessException(400, "分片大小必须大于 0");
        }
        long maxChunkSize = configService.getLong("transfer.max_chunk_size", 64L * 1024 * 1024);
        if (chunkSize > maxChunkSize) {
            throw new BusinessException(400, "分片大小超过上限(" + formatSize(maxChunkSize) + ")");
        }
        long totalChunks = (fileSize + chunkSize - 1L) / chunkSize;
        long maxChunks = configService.getLong("transfer.max_chunks", 200_000L);
        if (totalChunks <= 0 || totalChunks > maxChunks) {
            throw new BusinessException(400, "分片数量超过上限(" + maxChunks + ")");
        }

        // task-94：历史/客户端请求可能只带 receiverDeviceId（接收方设备），不带 receiverId。
        // 入库前把设备归属用户补成 receiverId，既保证接收方在列表/详情可见，也让下载计次、取消等
        // 既有按 receiverId 判断的路径继续成立（无需改状态机与既有数值）。
        if (receiverId == null && receiverDeviceId != null) {
            Device receiverDevice = deviceMapper.selectById(receiverDeviceId);
            if (receiverDevice != null) {
                receiverId = receiverDevice.getUserId();
            }
        }

        boolean requireFriend = configService.getBoolean("transfer.require_friend", true);
        if (requireFriend) {
            boolean allowed = areFriends(senderId, receiverId) || areInSameWorkspace(senderId, receiverId);
            if (!allowed) {
                throw new BusinessException(403, "跨网传输需要好友关系或同工作区");
            }
        }

        int chunkCount = (int) totalChunks;
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
        task.setTransferType(transferType != null ? transferType : "inbox");
        task.setExpiresAt(expireDays > 0 ? LocalDateTime.now().plusDays(expireDays) : null);
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

    public String initMultipartUpload(Long taskId, Long userId) {
        TransferTask task = getTaskEntity(taskId);
        checkOwnership(task, userId);

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

        // chunk_index 合法性：越界/负数不得触碰临时文件
        if (chunkIndex < 0 || chunkIndex >= task.getChunkCount()) {
            throw new BusinessException(400, "chunk index out of range: " + chunkIndex
                    + ", chunkCount=" + task.getChunkCount());
        }
        Path tempPath = Path.of(TEMP_DIR, taskId + ".tmp");
        if (!Files.exists(tempPath)) {
            throw new BusinessException(400, "上传未初始化或临时文件已失效，请重新 init");
        }

        // 同一分片的重传/并发写串行化；不同分片仍可并发
        Object lock = chunkLocks.computeIfAbsent(taskId + ":" + chunkIndex, key -> new Object());
        synchronized (lock) {
            TransferChunk chunk = getChunk(taskId, chunkIndex);
            long offset = chunkOffset(task, chunkIndex);
            long expected = Math.min((long) task.getChunkSize(), task.getFileSize() - offset);
            if (chunk.getChunkSize() != null && chunk.getChunkSize() > 0 && chunk.getChunkSize() != expected) {
                // 分片元数据与任务声明不一致：拒绝写入，避免错位覆盖
                throw new BusinessException(400, "chunk metadata inconsistent: chunkIndex=" + chunkIndex
                        + ", expected=" + expected + ", metadata=" + chunk.getChunkSize());
            }
            // 客户端 HTTP 声明大小必须与分片期望大小一致（快速失败，不必读到 EOF）
            if (size > 0 && size != expected) {
                throw new BusinessException(400, "chunk size mismatch: declared " + size
                        + " bytes, expected " + expected);
            }
            if (chunk.getStatus() != null && chunk.getStatus() == 1) {
                // 幂等重传：该分片已完整写入，直接成功，不重复写临时文件
                return toChunkDTO(chunk);
            }
            // 累计已收字节必须仍不超过声明的文件大小
            Long uploaded = chunkMapper.sumUploadedBytes(taskId);
            long alreadyUploaded = uploaded == null ? 0L : uploaded;
            if (alreadyUploaded + expected > task.getFileSize()) {
                throw new BusinessException(400, "累计上传字节数超过声明的文件大小: " + task.getFileSize());
            }

            // 单分片上限：以任务声明的 chunkSize 为准（末片按剩余大小），忽略客户端声明；
            // 实写超过上限立即拒绝，且实际字节数必须精确等于期望值。
            long written = 0L;
            try (RandomAccessFile raf = new RandomAccessFile(tempPath.toFile(), "rw")) {
                raf.seek(offset);
                byte[] buf = new byte[81920];
                int read;
                while ((read = data.read(buf)) != -1) {
                    written += read;
                    if (written > expected) {
                        throw new BusinessException(400, "chunk too large: expected " + expected
                                + " bytes (chunkSize=" + task.getChunkSize() + ")");
                    }
                    raf.write(buf, 0, read);
                }
            }
            if (written != expected) {
                throw new BusinessException(400, "chunk size mismatch: expected " + expected
                        + " bytes, got " + written);
            }

            chunk.setEtag("chunk-" + chunkIndex);
            chunk.setChunkSize(written);
            chunk.setStatus(1);
            chunkMapper.updateById(chunk);

            int uploadedChunks = task.getUploadedChunks() == null ? 0 : task.getUploadedChunks();
            task.setUploadedChunks(Math.min(uploadedChunks + 1, task.getChunkCount()));
            task.setStatus(TransferStatus.UPLOADING.getValue());
            taskMapper.updateById(task);

            return toChunkDTO(chunk);
        }
    }

    public void completeTask(Long taskId, Long userId) throws Exception {
        TransferTask task = getTaskEntity(taskId);
        checkOwnership(task, userId);

        // 分片元数据必须与任务声明完全一致：缺失/多余都视为异常
        List<TransferChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<TransferChunk>()
                        .eq(TransferChunk::getTaskId, taskId)
                        .orderByAsc(TransferChunk::getChunkIndex));
        if (chunks.size() != task.getChunkCount()) {
            throw new BusinessException(400, "分片元数据与任务不一致: expected " + task.getChunkCount()
                    + ", got " + chunks.size());
        }
        long uploadedBytes = 0L;
        int uploadedCount = 0;
        for (TransferChunk chunk : chunks) {
            if (chunk.getStatus() != null && chunk.getStatus() == 1) {
                uploadedCount++;
                uploadedBytes += chunk.getChunkSize() == null ? 0L : chunk.getChunkSize();
            }
        }
        if (uploadedCount != task.getChunkCount()) {
            throw new BusinessException(400, "文件分片尚未全部上传: " + uploadedCount + "/" + task.getChunkCount());
        }

        Path tempPath = Path.of(TEMP_DIR, taskId + ".tmp");
        if (!Files.exists(tempPath)) {
            resetIncompleteUpload(task, chunks);
            throw new BusinessException(400, "上传临时文件不存在，请重新上传");
        }
        long objectSize = Files.size(tempPath);
        if (uploadedBytes != task.getFileSize() || objectSize != task.getFileSize()) {
            // 收完总字节数 != 声明大小：拒绝入库，清理临时文件与已标记分片
            resetIncompleteUpload(task, chunks);
            throw new BusinessException(400, "上传字节数不符: declared " + task.getFileSize()
                    + ", chunksSum=" + uploadedBytes + ", temp=" + objectSize);
        }

        try (InputStream is = Files.newInputStream(tempPath)) {
            objectStorage.putObject(task.getMinioPath(), is, objectSize, "application/octet-stream");
        }

        Files.deleteIfExists(tempPath);
        task.setStatus(TransferStatus.COMPLETED.getValue());
        taskMapper.updateById(task);
    }

    /** 取消传输任务：发送方与接收方均可取消；已完成/已过期任务不可取消，重复取消幂等。 */
    @Transactional
    public TransferTaskDTO cancelTask(Long taskId, Long userId) {
        TransferTask task = getTaskEntity(taskId);
        checkOwnership(task, userId);
        int status = task.getStatus() == null ? TransferStatus.PENDING.getValue() : task.getStatus();
        if (status == TransferStatus.COMPLETED.getValue()) {
            throw new BusinessException(400, "已完成的任务不可取消");
        }
        if (status == TransferStatus.EXPIRED.getValue()) {
            throw new BusinessException(400, "已过期的任务不可取消");
        }
        if (status == TransferStatus.CANCELLED.getValue()) {
            return toDTO(task);
        }
        List<TransferChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<TransferChunk>().eq(TransferChunk::getTaskId, taskId));
        resetIncompleteUpload(task, chunks);
        task.setStatus(TransferStatus.CANCELLED.getValue());
        taskMapper.updateById(task);
        return toDTO(task);
    }

    /** 重试失败/已取消的任务：复位分片与临时文件后回到 PENDING，由发送方重新上传。 */
    @Transactional
    public TransferTaskDTO retryTask(Long taskId, Long userId) {
        TransferTask task = getTaskEntity(taskId);
        checkOwnership(task, userId);
        int status = task.getStatus() == null ? TransferStatus.PENDING.getValue() : task.getStatus();
        if (status != TransferStatus.FAILED.getValue() && status != TransferStatus.CANCELLED.getValue()) {
            throw new BusinessException(400, "只有失败或已取消的任务可以重试");
        }
        List<TransferChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<TransferChunk>().eq(TransferChunk::getTaskId, taskId));
        resetIncompleteUpload(task, chunks);
        return toDTO(task);
    }

    /**
     * 校验失败后的清理：删除临时文件、重置分片状态与任务进度，允许客户端从头重传。
     * 对象存储只在全部校验通过后才写入，因此这里不会留下半成品对象。
     */
    private void resetIncompleteUpload(TransferTask task, List<TransferChunk> chunks) {
        try {
            Files.deleteIfExists(Path.of(TEMP_DIR, task.getId() + ".tmp"));
        } catch (IOException e) {
            log.warn("failed to delete incomplete upload temp file for task {}", task.getId(), e);
        }
        // task-109：不能用 updateById 逐片重置——MyBatis-Plus 默认跳过 null 字段，
        // setEtag(null) 不落库会残留旧 etag；用显式 UPDATE 清零。
        chunkMapper.resetByTaskId(task.getId());
        task.setUploadedChunks(0);
        task.setStatus(TransferStatus.PENDING.getValue());
        taskMapper.updateById(task);
    }

    public List<TransferTaskDTO> listTasks(Long userId, String direction) {
        LambdaQueryWrapper<TransferTask> wrapper = taskScope(userId, direction, null);
        wrapper.orderByDesc(TransferTask::getCreatedAt).orderByDesc(TransferTask::getId);
        return toTaskDtos(taskMapper.selectList(wrapper), userId);
    }

    /**
     * task-54：传输任务分页 + 过滤（只读）。
     *
     * <p>分页一律 LIMIT/OFFSET + 独立 count，不把全表读进内存。direction 兼容
     * 旧值 `sent`/`received` 与新的 `up`/`down`（同义）。</p>
     */
    public PageResult<TransferTaskDTO> listTasksPaged(Long userId, String direction, Integer status,
                                                      Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? DEFAULT_TASK_PAGE_SIZE : Math.min(size, MAX_TASK_PAGE_SIZE);
        long total = taskMapper.selectCount(taskScope(userId, direction, status));
        if (total == 0L) {
            return PageResult.of(List.of(), 0L, p, s);
        }
        long offset = (long) (p - 1) * s;
        LambdaQueryWrapper<TransferTask> wrapper = taskScope(userId, direction, status);
        wrapper.orderByDesc(TransferTask::getCreatedAt).orderByDesc(TransferTask::getId)
                .last("LIMIT " + s + " OFFSET " + offset);
        List<TransferTaskDTO> items = toTaskDtos(taskMapper.selectList(wrapper), userId);
        return PageResult.of(items, total, p, s);
    }

    public TransferTaskDTO getTask(Long taskId, Long userId) {
        TransferTask task = getTaskEntity(taskId);
        checkOwnership(task, userId);
        TransferTaskDTO dto = toTaskDtos(List.of(task), userId).get(0);
        // 详情面板需要分片进度：已完成分片数传 uploadedChunks/chunkCount，逐片状态放 chunks
        dto.setChunks(getChunkStatus(taskId));
        return dto;
    }

    /**
     * 任务可见范围：本人发送，或发到本人账号/本人设备的任务；direction 兼容 up/down 与旧 sent/received。
     *
     * <p>历史任务可能只有 receiverDeviceId（receiverId 为空），所以接收侧按
     * 「账号匹配 或 接收设备属于本人」双条件判定。</p>
     */
    private LambdaQueryWrapper<TransferTask> taskScope(Long userId, String direction, Integer status) {
        LambdaQueryWrapper<TransferTask> wrapper = new LambdaQueryWrapper<>();
        String normalized = normalizeDirection(direction);
        List<Long> deviceIds = ownedDeviceIds(userId);
        if ("sent".equals(normalized)) {
            wrapper.eq(TransferTask::getSenderId, userId);
        } else if ("received".equals(normalized)) {
            wrapper.and(w -> {
                w.eq(TransferTask::getReceiverId, userId);
                if (!deviceIds.isEmpty()) {
                    w.or().in(TransferTask::getReceiverDeviceId, deviceIds);
                }
            });
        } else {
            wrapper.and(w -> {
                w.eq(TransferTask::getSenderId, userId).or().eq(TransferTask::getReceiverId, userId);
                if (!deviceIds.isEmpty()) {
                    w.or().in(TransferTask::getReceiverDeviceId, deviceIds);
                }
            });
        }
        if (status != null) {
            wrapper.eq(TransferTask::getStatus, status);
        }
        return wrapper;
    }

    /** 本人名下设备 id；用于把「设备级接收」折算成用户可见范围。 */
    private List<Long> ownedDeviceIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return deviceMapper.selectList(new LambdaQueryWrapper<Device>().eq(Device::getUserId, userId))
                .stream()
                .map(Device::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private static String normalizeDirection(String direction) {
        if (direction == null) {
            return null;
        }
        return switch (direction.trim().toLowerCase(Locale.ROOT)) {
            case "up", "sent", "out", "send", "upload" -> "sent";
            case "down", "received", "in", "recv", "download" -> "received";
            default -> null;
        };
    }

    /** 批量补对端用户名与进度，避免逐行查库（N+1）。 */
    private List<TransferTaskDTO> toTaskDtos(List<TransferTask> tasks, Long userId) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        Set<Long> peerIds = tasks.stream()
                .map(task -> peerId(task, userId))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> peerNames = peerIds.isEmpty()
                ? Map.of()
                : userMapper.selectBatchIds(peerIds).stream()
                        .collect(Collectors.toMap(User::getId, TransferService::displayName, (a, b) -> a));
        return tasks.stream().map(task -> {
            TransferTaskDTO dto = toDTO(task);
            enrichTask(dto, task, userId, peerNames);
            return dto;
        }).collect(Collectors.toList());
    }

    private static Long peerId(TransferTask task, Long viewerId) {
        if (viewerId.equals(task.getSenderId())) {
            return task.getReceiverId();
        }
        // 可见范围内非发送方即接收方（含「设备级接收」，此时 receiverId 可能为空），对端恒为发送方
        return task.getSenderId();
    }

    private static String displayName(User user) {
        if (user == null) {
            return null;
        }
        String nickname = user.getNickname();
        return nickname != null && !nickname.isBlank() ? nickname : user.getUsername();
    }

    private static void enrichTask(TransferTaskDTO dto, TransferTask task, Long userId, Map<Long, String> peerNames) {
        long totalBytes = task.getFileSize() == null ? 0L : task.getFileSize();
        long chunkSize = task.getChunkSize() == null ? 0L : task.getChunkSize();
        long uploadedChunks = task.getUploadedChunks() == null ? 0L : task.getUploadedChunks();
        dto.setTotalBytes(totalBytes);
        dto.setProgressBytes(Math.min(totalBytes, chunkSize * uploadedChunks));
        // 服务端无逐任务测速历史，本期固定 null（客户端用两次轮询的 progressBytes 差值自算）
        dto.setSpeedBytesPerSec(null);
        Long peer = peerId(task, userId);
        if (peer != null) {
            dto.setPeerName(peerNames.get(peer));
        }
    }

    public List<ChunkDTO> getChunkStatus(Long taskId) {
        return chunkMapper.selectList(
                        new LambdaQueryWrapper<TransferChunk>()
                                .eq(TransferChunk::getTaskId, taskId)
                                .orderByAsc(TransferChunk::getChunkIndex))
                .stream().map(this::toChunkDTO).collect(Collectors.toList());
    }

    /** 整文件下载（预留接口）：计量下载者下行；超额降速不断线。 */
    public InputStream downloadFile(Long taskId, Long userId) throws Exception {
        TransferTask task = getTaskEntity(taskId);
        checkOwnership(task, userId);
        // 只计下行：按实际送出字节在流 close 时落账（客户端中断也按已送出量计）
        return usageService.meterAndThrottleDownload(
                userId, objectStorage.getObject(task.getMinioPath()));
    }

    /** 分片下载：直接透传 S3 Range 流，按实际送出字节计量（断点续传只计新增分片）。 */
    public InputStream downloadChunk(Long taskId, Long userId, int chunkIndex) throws Exception {
        TransferTask task = getTaskEntity(taskId);
        checkOwnership(task, userId);
        if (chunkIndex < 0 || chunkIndex >= task.getChunkCount()) {
            throw new BusinessException(404, "分片不存在或尚未上传: chunkIndex=" + chunkIndex);
        }
        TransferChunk chunk = getChunk(taskId, chunkIndex);
        if (chunk.getStatus() == null || chunk.getStatus() != 1) {
            throw new BusinessException(404, "分片不存在或尚未上传: chunkIndex=" + chunkIndex);
        }
        long offset = chunkOffset(task, chunkIndex);
        long length = chunk.getChunkSize() == null ? 0L : chunk.getChunkSize();
        if (length <= 0) {
            throw new BusinessException(400, "分片大小无效: chunkIndex=" + chunkIndex);
        }

        // S3 range 读取并流式透传，不把分片读进内存；只计下行，按实际送出字节落账
        InputStream stream = usageService.meterAndThrottleDownload(
                userId, objectStorage.getObject(task.getMinioPath(), offset, length));
        // V13：接收方的下载进度打点（仅 receiver；发送方重新下载不改变接收方进度）。
        // 打点失败不影响下载本身，只记日志。
        if (userId.equals(task.getReceiverId())) {
            try {
                markChunkDownloaded(task, chunk);
            } catch (Exception e) {
                log.warn("Failed to mark chunk download progress: taskId={}, chunkIndex={}", taskId, chunkIndex, e);
            }
        }
        return stream;
    }

    /**
     * V13：下载进度打点——分片维度幂等标记 + 任务维度原子自增。
     *
     * <p>只有 {@code markDownloaded} 返回 1（该片首次下载成功）才累加
     * {@code download_chunks}，因此前端重试/断点续传重复拉同一片不会重复计数。</p>
     */
    private void markChunkDownloaded(TransferTask task, TransferChunk chunk) {
        if (chunk.getId() == null) {
            return;
        }
        int transitioned = chunkMapper.markDownloaded(chunk.getId());
        if (transitioned > 0) {
            taskMapper.incrementDownloadedChunks(task.getId());
        }
    }

    public void deleteTask(Long taskId, Long userId) {
        TransferTask task = getTaskEntity(taskId);
        checkOwnership(task, userId);
        try {
            objectStorage.deleteObject(task.getMinioPath());
        } catch (Exception e) {
            log.warn("Failed to delete storage object: {}", task.getMinioPath(), e);
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

    private TransferChunk getChunk(Long taskId, int chunkIndex) {
        TransferChunk chunk = chunkMapper.selectOne(
                new LambdaQueryWrapper<TransferChunk>()
                        .eq(TransferChunk::getTaskId, taskId)
                        .eq(TransferChunk::getChunkIndex, chunkIndex));
        if (chunk == null) {
            throw new BusinessException(404, "分片不存在: chunkIndex=" + chunkIndex);
        }
        return chunk;
    }

    /**
     * 任务操作权限：发送方或接收方；接收方支持「账号』与「接收设备归属」两种（task-94）。
     * 注：receiverId 可能为空（历史数据），因此不能用 equals 直接串联。
     */
    private void checkOwnership(TransferTask task, Long userId) {
        boolean sender = userId != null && userId.equals(task.getSenderId());
        if (!sender && !isReceiver(task, userId)) {
            throw new BusinessException(403, "无权操作此传输任务");
        }
    }

    /** 是否接收方：账号匹配，或 receiverId 缺失但接机设备属于该用户。 */
    private boolean isReceiver(TransferTask task, Long userId) {
        if (userId == null) {
            return false;
        }
        if (userId.equals(task.getReceiverId())) {
            return true;
        }
        Long receiverDeviceId = task.getReceiverDeviceId();
        if (receiverDeviceId == null) {
            return false;
        }
        Device device = deviceMapper.selectById(receiverDeviceId);
        return device != null && userId.equals(device.getUserId());
    }

    /** 仅接收方可以接受/拒绝；发送方与无关账号一律 403。 */
    private void requireReceiver(TransferTask task, Long userId) {
        if (!isReceiver(task, userId)) {
            throw new BusinessException(403, "只有接收方可以执行该操作");
        }
    }

    /** 接收方接受传输：PENDING → UPLOADING；重复接受幂等 200，其它状态 409。 */
    @Transactional
    public TransferTaskDTO acceptTask(Long taskId, Long userId) {
        TransferTask task = getTaskEntity(taskId);
        requireReceiver(task, userId);
        int status = task.getStatus() == null ? TransferStatus.PENDING.getValue() : task.getStatus();
        if (status == TransferStatus.UPLOADING.getValue()) {
            return toDTO(task);
        }
        if (status != TransferStatus.PENDING.getValue()) {
            throw new BusinessException(409, "当前状态不可接受: " + status);
        }
        task.setStatus(TransferStatus.UPLOADING.getValue());
        taskMapper.updateById(task);
        return toDTO(task);
    }

    /** 接收方拒绝传输：PENDING → REJECTED(6)；重复拒绝幂等 200，其它状态 409。 */
    @Transactional
    public TransferTaskDTO rejectTask(Long taskId, Long userId) {
        TransferTask task = getTaskEntity(taskId);
        requireReceiver(task, userId);
        int status = task.getStatus() == null ? TransferStatus.PENDING.getValue() : task.getStatus();
        if (status == TransferStatus.REJECTED.getValue()) {
            return toDTO(task);
        }
        if (status != TransferStatus.PENDING.getValue()) {
            throw new BusinessException(409, "当前状态不可拒绝: " + status);
        }
        task.setStatus(TransferStatus.REJECTED.getValue());
        taskMapper.updateById(task);
        return toDTO(task);
    }

    public void checkTaskAccess(Long taskId, Long userId) {
        TransferTask task = getTaskEntity(taskId);
        checkOwnership(task, userId);
    }

    /** 分片在临时文件中的偏移：按声明 chunkSize 连续排列，乱序上传也不会留下空洞。 */
    private static long chunkOffset(TransferTask task, int chunkIndex) {
        return (long) chunkIndex * task.getChunkSize();
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
        dto.setDownloadedChunks(task.getDownloadedChunks());
        dto.setWorkspaceId(task.getWorkspaceId());
        dto.setTransferType(task.getTransferType());
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
