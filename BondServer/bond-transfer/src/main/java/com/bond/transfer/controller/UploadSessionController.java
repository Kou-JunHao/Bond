package com.bond.transfer.controller;

import com.bond.common.dto.PageResult;
import com.bond.common.dto.Result;
import com.bond.common.dto.UploadSessionDTO;
import com.bond.common.dto.WorkspaceFileDTO;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.service.UploadSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 网盘大文件分片 / 断点续传上传（task-40，V7）。
 *
 * <p>使用 S3 Multipart Upload：init 建会话，PUT 分片写对象存储，
 * 会话记录在 file_upload_sessions；complete 成功后才创建 workspace_files 行。</p>
 *
 * <p>PUT 分片为原始请求体（非 multipart），必须带准确的 Content-Length；
 * 服务端会按该分片期望字节数严格校验。</p>
 */
@RestController
@RequestMapping("/api/files/uploads")
@RequiredArgsConstructor
public class UploadSessionController {

    private final UploadSessionService uploadSessionService;

    @Data
    public static class InitUploadRequest {
        /** 不传则默认个人空间。 */
        private Long workspaceId;
        /** 目标目录路径，默认根目录 "/"。 */
        private String parentPath;
        private String fileName;
        private Long fileSize;
        private String contentType;
        /** task-41：可选，客户端计算的整文件 SHA-256（64 位小写 hex），complete 时落库。 */
        private String fileHash;
        /** task-41：可选，逐分片 SHA-256 列表（顺序与 index 一致）；提供后服务端上传时逐个校验。 */
        private java.util.List<String> chunkHashes;
    }

    @PostMapping
    public Result<UploadSessionService.InitResult> init(@RequestHeader("X-User-Id") Long userId,
                                                        @RequestBody InitUploadRequest request) {
        return Result.ok(uploadSessionService.init(userId, request.getWorkspaceId(),
                request.getParentPath(), request.getFileName(), request.getFileSize(),
                request.getContentType(), request.getFileHash(), request.getChunkHashes()));
    }

    /** 上传单个分片：成功固定 204（幂等）。 */
    @PutMapping("/{uploadId}/{index}")
    public ResponseEntity<Void> putPart(@RequestHeader("X-User-Id") Long userId,
                                        @PathVariable String uploadId,
                                        @PathVariable int index,
                                        HttpServletRequest request) throws IOException {
        long size = request.getContentLengthLong();
        if (size < 0) {
            throw new BusinessException(411, "缺少 Content-Length");
        }
        uploadSessionService.putPart(userId, uploadId, index, request.getInputStream(), size);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{uploadId}")
    public Result<UploadSessionService.StatusResult> status(@RequestHeader("X-User-Id") Long userId,
                                                            @PathVariable String uploadId) {
        return Result.ok(uploadSessionService.status(userId, uploadId));
    }

    /**
     * task-54：当前用户未完成上传会话分页列表（只读，不产生任何写）。
     *
     * <p>page 从 1 开始、默认 1；size 默认 50、上限 200。按 createdAt 倒序。</p>
     */
    @GetMapping
    public Result<PageResult<UploadSessionDTO>> list(@RequestHeader("X-User-Id") Long userId,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size) {
        return Result.ok(uploadSessionService.list(userId, page, size));
    }

    @PostMapping("/{uploadId}/complete")
    public Result<WorkspaceFileDTO> complete(@RequestHeader("X-User-Id") Long userId,
                                             @PathVariable String uploadId) {
        return Result.ok(uploadSessionService.complete(userId, uploadId));
    }

    @DeleteMapping("/{uploadId}")
    public Result<Void> abort(@RequestHeader("X-User-Id") Long userId, @PathVariable String uploadId) {
        uploadSessionService.abort(userId, uploadId);
        return Result.ok(null);
    }
}
