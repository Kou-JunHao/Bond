package com.bond.transfer.controller;

import com.bond.common.dto.ChunkDTO;
import com.bond.common.dto.Result;
import com.bond.common.dto.TransferTaskDTO;
import com.bond.transfer.service.MinioService;
import com.bond.transfer.service.TransferService;
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
    private final MinioService minioService;

    @PostMapping("/avatar")
    public Result<Map<String, String>> uploadAvatar(@RequestHeader("X-User-Id") Long userId,
                                                    @RequestParam MultipartFile file) throws Exception {
        String ext = file.getOriginalFilename();
        if (ext != null && ext.contains(".")) ext = ext.substring(ext.lastIndexOf("."));
        else ext = ".png";
        String objectName = "avatars/" + userId + ext;
        minioService.uploadObject(objectName, file.getInputStream(), file.getSize(), file.getContentType());
        String url = "/api/transfer/avatar/" + userId;
        return Result.ok(Map.of("url", url));
    }

    @GetMapping("/avatar/{userId}")
    public ResponseEntity<InputStreamResource> getAvatar(@PathVariable Long userId) throws Exception {
        try {
            String objectName = "avatars/" + userId + ".png";
            InputStream is = minioService.downloadObject(objectName);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE)
                    .body(new InputStreamResource(is));
        } catch (Exception e) {
            try {
                String objectName = "avatars/" + userId + ".jpg";
                InputStream is = minioService.downloadObject(objectName);
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                        .body(new InputStreamResource(is));
            } catch (Exception e2) {
                return ResponseEntity.notFound().build();
            }
        }
    }

    @PostMapping("/tasks")
    public Result<TransferTaskDTO> createTask(@RequestHeader("X-User-Id") Long userId,
                                              @RequestBody CreateTaskRequest req) {
        return Result.ok(transferService.createTask(
                userId, req.getReceiverId(), req.getSenderDeviceId(),
                req.getReceiverDeviceId(), req.getFileName(), req.getFileSize(),
                req.getChunkSize(), req.getEncryptedKey()));
    }

    @GetMapping("/tasks")
    public Result<List<TransferTaskDTO>> listTasks(@RequestHeader("X-User-Id") Long userId,
                                                   @RequestParam(required = false) String direction) {
        return Result.ok(transferService.listTasks(userId, direction));
    }

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

    @PostMapping("/tasks/{id}/complete")
    public Result<Void> completeTask(@RequestHeader("X-User-Id") Long userId,
                                     @PathVariable Long id) throws Exception {
        transferService.completeTask(id, userId);
        return Result.ok();
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
                .body(new InputStreamResource(transferService.downloadChunk(taskId, index)));
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
    }
}
