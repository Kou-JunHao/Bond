package com.bond.transfer.controller;

import com.bond.common.dto.ChunkDTO;
import com.bond.common.dto.Result;
import com.bond.common.dto.TransferTaskDTO;
import com.bond.transfer.service.TransferService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/transfer")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

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
    public Result<TransferTaskDTO> getTask(@PathVariable Long id) {
        return Result.ok(transferService.getTask(id));
    }

    @DeleteMapping("/tasks/{id}")
    public Result<Void> deleteTask(@PathVariable Long id) {
        transferService.deleteTask(id);
        return Result.ok();
    }

    @PostMapping("/tasks/{id}/complete")
    public Result<Void> completeTask(@PathVariable Long id) throws Exception {
        transferService.completeTask(id);
        return Result.ok();
    }

    @PostMapping("/chunks/{taskId}/init")
    public Result<Map<String, String>> initUpload(@PathVariable Long taskId) throws Exception {
        String uploadId = transferService.initMultipartUpload(taskId);
        return Result.ok(Map.of("uploadId", uploadId));
    }

    @PutMapping("/chunks/{taskId}/{index}")
    public Result<ChunkDTO> uploadChunk(@PathVariable Long taskId,
                                        @PathVariable int index,
                                        @RequestParam String uploadId,
                                        @RequestParam MultipartFile file) throws Exception {
        return Result.ok(transferService.uploadChunk(taskId, index, uploadId, file.getInputStream(), file.getSize()));
    }

    @GetMapping("/chunks/{taskId}/{index}")
    public ResponseEntity<InputStreamResource> downloadChunk(@PathVariable Long taskId,
                                                              @PathVariable int index) throws Exception {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .body(new InputStreamResource(transferService.downloadChunk(taskId, index)));
    }

    @GetMapping("/chunks/{taskId}/status")
    public Result<List<ChunkDTO>> chunkStatus(@PathVariable Long taskId) {
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
