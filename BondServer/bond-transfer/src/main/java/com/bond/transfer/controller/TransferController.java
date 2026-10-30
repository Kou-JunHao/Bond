package com.bond.transfer.controller;

import com.bond.common.dto.ChunkDTO;
import com.bond.common.dto.Result;
import com.bond.common.dto.TransferTaskDTO;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.TransferService;
import com.bond.transfer.storage.ObjectStorage;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/transfer")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;
    private final ObjectStorage objectStorage;
    private final ConfigService configService;

    /** task-99：客户端可省略 chunkSize，服务端默认 4 MiB（与客户端默认一致且为 2 的幂）。 */
    static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024;

    @PostMapping("/avatar")
    public Result<Map<String, String>> uploadAvatar(@RequestHeader("X-User-Id") Long userId,
                                                    @RequestParam MultipartFile file) throws Exception {
        String ext = file.getOriginalFilename();
        if (ext != null && ext.contains(".")) ext = ext.substring(ext.lastIndexOf("."));
        else ext = ".png";
        String objectName = "avatars/" + userId + ext;
        objectStorage.putObject(objectName, file.getInputStream(), file.getSize(), file.getContentType());
        String url = "/api/transfer/avatar/" + userId;
        return Result.ok(Map.of("url", url));
    }

    @GetMapping("/avatar/{userId}")
    public ResponseEntity<InputStreamResource> getAvatar(@PathVariable Long userId) throws Exception {
        String[] exts = {".png", ".jpg", ".jpeg", ".webp", ".gif"};
        for (String ext : exts) {
            try {
                String objectName = "avatars/" + userId + ext;
                InputStream is = objectStorage.getObject(objectName);
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, contentTypeForAvatar(ext))
                        .body(new InputStreamResource(is));
            } catch (Exception ignored) {}
        }
        return ResponseEntity.notFound().build();
    }

    private static String contentTypeForAvatar(String ext) {
        return switch (ext) {
            case ".jpg", ".jpeg" -> MediaType.IMAGE_JPEG_VALUE;
            case ".webp" -> "image/webp";
            case ".gif" -> MediaType.IMAGE_GIF_VALUE;
            default -> MediaType.IMAGE_PNG_VALUE;
        };
    }

    @GetMapping("/config")
    public Result<Map<String, Object>> transferConfig() {
        return Result.ok(Map.of(
                "chunkSize", configService.getInt("transfer.chunk_size", 5242880),
                "largeFileThreshold", configService.getLong("transfer.large_file_threshold", 1073741824L),
                "largeChunkSize", configService.getInt("transfer.large_chunk_size", 10485760),
                "uploadConcurrency", configService.getInt("transfer.upload_concurrency", 4),
                "maxFileSize", configService.getLong("transfer.max_file_size", 5368709120L)
        ));
    }

    @PostMapping("/tasks")
    public Result<TransferTaskDTO> createTask(@RequestHeader("X-User-Id") Long userId,
                                              @RequestBody CreateTaskRequest req) {
        // task-99：这些字段在 service 里是 long/int 原语，必须在控制器显式判空，
        // 否则 Integer/Long 拆箱 NPE 会被兜底成 500 并泄漏实现细节。
        if (req.getFileName() == null || req.getFileName().isBlank()) {
            throw new BusinessException(400, "文件名不能为空");
        }
        if (req.getFileSize() == null) {
            throw new BusinessException(400, "文件大小不能为空");
        }
        if (req.getReceiverId() == null && req.getReceiverDeviceId() == null) {
            throw new BusinessException(400, "接收方不能为空");
        }
        int chunkSize = req.getChunkSize() == null ? DEFAULT_CHUNK_SIZE : req.getChunkSize();
        if (chunkSize <= 0 || (chunkSize & (chunkSize - 1)) != 0) {
            throw new BusinessException(400, "分片大小必须是 2 的幂且大于 0");
        }
        return Result.ok(transferService.createTask(
                userId, req.getReceiverId(), req.getSenderDeviceId(),
                req.getReceiverDeviceId(), req.getFileName(), req.getFileSize(),
                chunkSize, req.getEncryptedKey(), req.getTransferType()));
    }

    /**
     * 传输任务列表。
     *
     * <p>兼容策略（task-54）：<b>不传 page</b> 时保持旧行为（data 为数组，老客户端不炸）；
     * 传 page 时返回分页信封 {@code {items,total,page,size}}。direction 支持 up/down 与旧 sent/received。</p>
     */
    @GetMapping("/tasks")
    public Result<Object> listTasks(@RequestHeader("X-User-Id") Long userId,
                                    @RequestParam(required = false) String direction,
                                    @RequestParam(required = false) Integer status,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        if (page == null) {
            return Result.<Object>ok(transferService.listTasks(userId, direction));
        }
        return Result.<Object>ok(transferService.listTasksPaged(userId, direction, status, page, size));
    }

    /** 单任务详情：额外带分片进度与逐片状态（chunks）。 */
    @GetMapping("/tasks/{id}")
    public Result<TransferTaskDTO> getTask(@RequestHeader("X-User-Id") Long userId,
                                           @PathVariable Long id) {
        return Result.ok(transferService.getTask(id, userId));
    }

    @DeleteMapping("/tasks/{id}")
    public Result<Void> deleteTask(@RequestHeader("X-User-Id") Long userId,
                                   @PathVariable Long id) {
        transferService.deleteTask(id, userId);
        return Result.ok();
    }

    @PostMapping("/tasks/{id}/cancel")
    public Result<TransferTaskDTO> cancelTask(@RequestHeader("X-User-Id") Long userId,
                                              @PathVariable Long id) {
        return Result.ok(transferService.cancelTask(id, userId));
    }

    @PostMapping("/tasks/{id}/retry")
    public Result<TransferTaskDTO> retryTask(@RequestHeader("X-User-Id") Long userId,
                                             @PathVariable Long id) {
        return Result.ok(transferService.retryTask(id, userId));
    }

    /** task-94：接收方接受传输（PENDING→UPLOADING；重复接受幂等 200，其它状态 409）。 */
    @PostMapping("/tasks/{id}/accept")
    public Result<TransferTaskDTO> acceptTask(@RequestHeader("X-User-Id") Long userId,
                                              @PathVariable Long id) {
        return Result.ok(transferService.acceptTask(id, userId));
    }

    /** task-94：接收方拒绝传输（PENDING→REJECTED(6)；重复拒绝幂等 200，其它状态 409）。 */
    @PostMapping("/tasks/{id}/reject")
    public Result<TransferTaskDTO> rejectTask(@RequestHeader("X-User-Id") Long userId,
                                              @PathVariable Long id) {
        return Result.ok(transferService.rejectTask(id, userId));
    }

    @PostMapping("/tasks/{id}/complete")
    public Result<TransferTaskDTO> completeTask(@RequestHeader("X-User-Id") Long userId,
                                                @PathVariable Long id) throws Exception {
        transferService.completeTask(id, userId);
        // task-107：complete 返回最新任务快照（含 uploadedChunks/chunkCount/status），
        // 与列表接口保持同一契约。原实现返回 Result<Void>（body 里没有进度字段），
        // 客户端若从 complete 响应读 uploadedChunks 会读空；此处给兼容超集，客户端可忽略。
        return Result.ok(transferService.getTask(id, userId));
    }

    @PostMapping("/chunks/{taskId}/init")
    public Result<Map<String, String>> initUpload(@RequestHeader("X-User-Id") Long userId,
                                                  @PathVariable Long taskId) throws Exception {
        String uploadId = transferService.initMultipartUpload(taskId, userId);
        return Result.ok(Map.of("uploadId", uploadId));
    }

    @PutMapping("/chunks/{taskId}/{index}")
    public Result<ChunkDTO> uploadChunk(@RequestHeader("X-User-Id") Long userId,
                                        @PathVariable Long taskId,
                                        @PathVariable int index,
                                        @RequestParam(required = false) String uploadId,
                                        @RequestParam MultipartFile file) throws Exception {
        return Result.ok(transferService.uploadChunk(taskId, index, uploadId, file.getInputStream(), file.getSize(), userId));
    }

    @GetMapping("/chunks/{taskId}/{index}")
    public ResponseEntity<InputStreamResource> downloadChunk(@RequestHeader("X-User-Id") Long userId,
                                                              @PathVariable Long taskId,
                                                              @PathVariable int index) throws Exception {
        transferService.checkTaskAccess(taskId, userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .body(new InputStreamResource(transferService.downloadChunk(taskId, userId, index)));
    }

    @GetMapping("/chunks/{taskId}/status")
    public Result<List<ChunkDTO>> chunkStatus(@RequestHeader("X-User-Id") Long userId,
                                               @PathVariable Long taskId) {
        transferService.checkTaskAccess(taskId, userId);
        return Result.ok(transferService.getChunkStatus(taskId));
    }

    @Data
    static class CreateTaskRequest {
        private Long receiverId;
        private Long senderDeviceId;
        private Long receiverDeviceId;
        private String fileName;
        private Long fileSize;
        private Integer chunkSize;
        private String encryptedKey;
        private String transferType;
    }
}
