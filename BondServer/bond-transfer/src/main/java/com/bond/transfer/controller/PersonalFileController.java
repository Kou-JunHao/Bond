package com.bond.transfer.controller;

import com.bond.common.dto.FileSearchResult;
import com.bond.common.dto.InstantUploadDTO;
import com.bond.common.dto.Result;
import com.bond.common.dto.StorageQuotaDTO;
import com.bond.common.dto.WorkspaceFileDTO;
import com.bond.transfer.preview.EtagMatch;
import com.bond.transfer.preview.OfficePreviewService;
import com.bond.transfer.preview.PreviewService;
import com.bond.transfer.service.FileSearchService;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 个人空间文件 API（客户端「文件页」专用）。
 *
 * <p>语义：所有操作都作用于当前登录用户的个人空间，客户端**不需要也不应该**传 workspaceId。
 * 控制器先通过 {@link PersonalSpaceService} 解析（必要时懒创建）个人空间 id，
 * 再委托给 {@link WorkspaceService} 的现有方法，存储/配额/MinIO 逻辑只有一套。</p>
 *
 * <p>鉴权：网关注入 X-User-Id（JWT 校验在 AuthFilter），/api/files/** 不在白名单内。</p>
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class PersonalFileController {

    private final PersonalSpaceService personalSpaceService;
    private final WorkspaceService workspaceService;
    private final PreviewService previewService;
    private final FileSearchService fileSearchService;

    // ── 文件列表 ──

    @GetMapping
    public Result<List<WorkspaceFileDTO>> list(@RequestHeader("X-User-Id") Long userId,
                                               @RequestParam(required = false) String path) {
        return Result.ok(workspaceService.listFiles(personalWorkspaceId(userId), userId, path));
    }

    // ── 文件搜索（task-117）──
    // 不传 workspaceId：搜「个人空间 + 我是成员的工作区」；传了则要求是成员（403）。
    // 契约见 docs/API-CONTRACT.md §4：items/name/size/path/isDirectory/updatedAtMillis、total、nextCursor、hasMore。

    @GetMapping("/search")
    public Result<FileSearchResult> search(@RequestHeader("X-User-Id") Long userId,
                                           @RequestParam String q,
                                           @RequestParam(required = false) Long workspaceId,
                                           @RequestParam(required = false) Integer limit,
                                           @RequestParam(required = false) String cursor) {
        return Result.ok(fileSearchService.search(userId, q, workspaceId, limit, cursor));
    }

    // ── 配额（个人空间页展示 已用/配额） ──

    @GetMapping("/quota")
    public Result<StorageQuotaDTO> quota(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(workspaceService.getQuota(personalWorkspaceId(userId), userId));
    }

    // ── 建目录（task-118：REST 暴露，供客户端「新建文件夹」与拖拽上传） ──

    /**
     * 建目录：父目录必须已存在（404，不静默逐级建）；重名 409；非法名 400；目录不占配额。
     */
    @PostMapping("/folders")
    public Result<WorkspaceFileDTO> createFolder(@RequestHeader("X-User-Id") Long userId,
                                                 @RequestBody CreateFolderRequest req) {
        return Result.ok(workspaceService.createFolder(
                personalWorkspaceId(userId), userId, req.getParentPath(), req.getName()));
    }

    @Data
    public static class CreateFolderRequest {
        /** 父路径；空/null 表示根目录。 */
        private String parentPath;
        /** 目录名（不含路径分隔符）。 */
        private String name;
    }

    // ── 简单上传 ──

    @PostMapping("/upload")
    public Result<WorkspaceFileDTO> upload(@RequestHeader("X-User-Id") Long userId,
                                           @RequestParam(required = false) String parentPath,
                                           @RequestParam(required = false) String fileHash,
                                           @RequestParam MultipartFile file) throws Exception {
        return Result.ok(workspaceService.uploadFile(personalWorkspaceId(userId), userId, parentPath, file, fileHash));
    }

    // ── 删除 / 重命名 ──

    @DeleteMapping("/{fileId}")
    public Result<Void> delete(@RequestHeader("X-User-Id") Long userId,
                               @PathVariable Long fileId) {
        workspaceService.deleteFile(personalWorkspaceId(userId), userId, fileId);
        return Result.ok();
    }

    @PostMapping("/rename")
    public Result<WorkspaceFileDTO> rename(@RequestHeader("X-User-Id") Long userId,
                                           @RequestBody RenameRequest req) {
        return Result.ok(workspaceService.renameFile(
                personalWorkspaceId(userId), userId, req.getFileId(), req.getNewName()));
    }

    // ── 转存（服务端 S3 复制，不计流量；task-102） ──

    /**
     * 把个人空间（或指定源工作区）的文件转存到目标目录：源 = 个人空间；
     * 不传 targetWorkspaceId 时目标也是个人空间。配额不足 413、同名 400、目录 400。
     */
    @PostMapping("/{fileId}/copy")
    public Result<WorkspaceFileDTO> copy(@RequestHeader("X-User-Id") Long userId,
                                         @PathVariable Long fileId,
                                         @RequestBody(required = false) CopyFileRequest req) {
        Long personalId = personalWorkspaceId(userId);
        Long targetId = req != null && req.getTargetWorkspaceId() != null
                ? req.getTargetWorkspaceId() : personalId;
        return Result.ok(workspaceService.copyFile(userId, personalId, fileId, targetId,
                req == null ? null : req.getTargetParentPath(),
                req == null ? null : req.getNewName()));
    }

    /**
     * 移动文件/文件夹（task-132）：源 = 个人空间；不传 targetWorkspaceId 时目标也是个人空间。
     * 同空间只改路径/名字（不动对象、不动配额）；跨空间原子改两侧 used_size，目标配额不足 413；
     * 目标同名 409、目标目录不存在 404、目录移进自身子孙 400、非法路径 400、已删 404。
     */
    @PostMapping("/{fileId}/move")
    public Result<WorkspaceFileDTO> move(@RequestHeader("X-User-Id") Long userId,
                                         @PathVariable Long fileId,
                                         @RequestBody(required = false) CopyFileRequest req) {
        Long personalId = personalWorkspaceId(userId);
        Long targetId = req != null && req.getTargetWorkspaceId() != null
                ? req.getTargetWorkspaceId() : personalId;
        return Result.ok(workspaceService.moveFile(userId, personalId, fileId, targetId,
                req == null ? null : req.getTargetParentPath(),
                req == null ? null : req.getNewName()));
    }

    // ── 秒传 / 分片上传 ──

    @PostMapping("/check-hash")
    public Result<WorkspaceFileDTO> checkHash(@RequestHeader("X-User-Id") Long userId,
                                              @RequestBody CheckHashRequest req) {
        return Result.ok(workspaceService.checkHash(
                personalWorkspaceId(userId), userId, req.getHash(), req.getFileName(),
                req.getFileSize(), req.getParentPath()));
    }

    // ── 秒传（instant upload，task-41） ──

    /**
     * 秒传：调用者在自己本来就有权限的范围里按 (hash, size) 命中已有文件，
     * 对象经 S3 server-side CopyObject 复制到目标目录，原子占配额并建行。
     *
     * <p>未命中返回 404，客户端回退到普通上传/分片上传；参数非法 400；空间不足 413。
     * 秒传不计下行流量（没有外网字节流动），但照常占用空间。</p>
     */
    @PostMapping("/instant-upload")
    public Result<InstantUploadDTO> instantUpload(@RequestHeader("X-User-Id") Long userId,
                                                  @RequestBody InstantUploadRequest req) {
        Long workspaceId = req.getWorkspaceId() != null ? req.getWorkspaceId() : personalWorkspaceId(userId);
        WorkspaceFileDTO file = workspaceService.instantUpload(workspaceId, userId, req.getParentPath(),
                req.getFileName(), req.getFileHash(), req.getFileSize(), req.getContentType());
        InstantUploadDTO dto = new InstantUploadDTO();
        BeanUtils.copyProperties(file, dto);
        return Result.ok(dto);
    }

    @Data
    public static class InstantUploadRequest {
        /** 不传则秒传到个人空间（与 /upload 一致）。 */
        private Long workspaceId;
        private String parentPath;
        private String fileName;
        /** 客户端已算好的整文件 SHA-256（64 位小写 hex），必填。 */
        private String fileHash;
        private Long fileSize;
        private String contentType;
    }

    @PostMapping("/chunks/init")
    public Result<Map<String, String>> initChunkUpload(@RequestHeader("X-User-Id") Long userId,
                                                       @RequestBody InitChunkUploadRequest req) throws Exception {
        String uploadId = workspaceService.initChunkUpload(
                personalWorkspaceId(userId), userId, req.getFileName(), req.getFileSize(),
                req.getParentPath(), req.getFileHash(), req.getContentType(),
                req.getChunkSize(), req.getUploadId());
        return Result.ok(Map.of("uploadId", uploadId));
    }

    @PostMapping("/chunks/{uploadId}/{chunkIndex}")
    public Result<Void> uploadChunk(@RequestHeader("X-User-Id") Long userId,
                                    @PathVariable String uploadId,
                                    @PathVariable int chunkIndex,
                                    @RequestParam MultipartFile file) throws Exception {
        workspaceService.uploadChunk(
                personalWorkspaceId(userId), userId, uploadId, chunkIndex,
                file.getInputStream(), file.getSize());
        return Result.ok();
    }

    @PostMapping("/chunks/{uploadId}/complete")
    public Result<WorkspaceFileDTO> completeChunkUpload(@RequestHeader("X-User-Id") Long userId,
                                                        @PathVariable String uploadId) throws Exception {
        return Result.ok(workspaceService.completeChunkUpload(personalWorkspaceId(userId), userId, uploadId));
    }

    @PostMapping("/chunks/{uploadId}/status")
    public Result<Map<String, Object>> chunkStatus(@RequestHeader("X-User-Id") Long userId,
                                                   @PathVariable String uploadId) {
        return Result.ok(workspaceService.chunkStatus(personalWorkspaceId(userId), userId, uploadId));
    }

    // ── 下载 ──

    @GetMapping("/{fileId}/download")
    public ResponseEntity<InputStreamResource> download(@RequestHeader("X-User-Id") Long userId,
                                                        @PathVariable Long fileId) throws Exception {
        WorkspaceService.DownloadResult stream =
                workspaceService.downloadFile(personalWorkspaceId(userId), userId, fileId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .contentLength(stream.fileSize())
                .body(new InputStreamResource(stream.inputStream()));
    }

    // ── 预览：短期令牌 URL / 缩略图 / 内容流（token 在 query，播放器无需 Authorization） ──

    @GetMapping("/{fileId}/preview-url")
    public Result<PreviewService.PreviewUrl> previewUrl(@RequestHeader("X-User-Id") Long userId,
                                                        @PathVariable Long fileId) {
        return Result.ok(previewService.personalPreviewUrls(userId, fileId));
    }

    @GetMapping("/{fileId}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable Long fileId,
                                            @RequestParam(required = false) String w,
                                            @RequestParam(required = false) String token,
                                            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
                                            String ifNoneMatch) {
        if (token == null || token.isBlank()) {
            // 缺少令牌与无效令牌语义一致：404，不泄露文件是否存在
            return ResponseEntity.notFound().build();
        }
        try {
            PreviewService.Thumbnail thumb = previewService.personalThumbnail(token, fileId, w);
            if (thumb == null) {
                return ResponseEntity.noContent().build();
            }
            if (EtagMatch.matches(thumb.etag(), ifNoneMatch)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(thumb.etag()).build();
            }
            return ResponseEntity.ok()
                    .eTag(thumb.etag())
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(thumb.jpeg());
        } catch (PreviewService.InvalidPreviewTokenException e) {
            return ResponseEntity.notFound().build();
        } catch (PreviewService.ThumbnailRateLimitedException e) {
            // 软限流：客户端不渲染缩略图即可，降级为类型图标；429 而非 204，便于客户端区分"被限流"与"无缩略图"
            return tooManyThumbnails(e.getRetryAfterSeconds());
        } catch (Exception e) {
            return ResponseEntity.noContent().build();
        }
    }

    /** PDF 单页预览：page 从 1 开始；非法值回退 1；越界 416 + X-Total-Pages；非 PDF 204。 */
    @GetMapping("/{fileId}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable Long fileId,
                                          @RequestParam(required = false) String page,
                                          @RequestParam(required = false) String w,
                                          @RequestParam(required = false) String token) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        try {
            PreviewService.Page result = previewService.personalPreviewPage(token, fileId, page, w);
            if (result == null) {
                return ResponseEntity.noContent().build();
            }
            ResponseEntity.BodyBuilder builder = result.jpeg() == null
                    ? ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    : ResponseEntity.ok();
            // 越界响应同样带总页数，客户端才能把用户拉回有效页
            builder.header("X-Total-Pages", String.valueOf(result.totalPages()))
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400");
            if (result.jpeg() == null) {
                return builder.build();
            }
            return builder.eTag(result.etag())
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(result.jpeg());
        } catch (PreviewService.InvalidPreviewTokenException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.noContent().build();
        }
    }

    @GetMapping("/{fileId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable Long fileId,
                                                       @RequestParam(required = false) String token,
                                                       @RequestHeader(value = HttpHeaders.RANGE, required = false)
                                                       String range) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        try {
            PreviewService.Content content = previewService.openPersonalContent(token, fileId, range);
            ResponseEntity.BodyBuilder builder = content.partial()
                    ? ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    : ResponseEntity.ok();
            builder.header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentLength(content.length())
                    .contentType(MediaType.parseMediaType(content.contentType()));
            if (content.partial()) {
                builder.header(HttpHeaders.CONTENT_RANGE, "bytes " + content.offset() + "-"
                        + (content.offset() + content.length() - 1) + "/" + content.totalSize());
            }
            InputStream stream = content.stream();
            return builder.body(new InputStreamResource(stream));
        } catch (PreviewService.InvalidPreviewTokenException e) {
            return ResponseEntity.notFound().build();
        } catch (PreviewService.UnsatisfiableRangeException e) {
            return ResponseEntity.<InputStreamResource>status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + e.getTotalSize())
                    .build();
        }
    }

    /**
     * Office 文档 → PDF 预览（task-59）：首次请求触发 Gotenberg 转换并缓存，支持 Range/206。
     *
     * <p>失败态为真实状态码 + JSON：503 转换服务不可用 / 415 坏文档 / 413 超上限 / 404 令牌无效。
     * <b>不计量</b>：转换产物是服务端生成的预览，不按原文件体积计用户下行流量。</p>
     */
    @GetMapping("/{fileId}/office-pdf")
    public ResponseEntity<?> officePdf(@PathVariable Long fileId,
                                       @RequestParam(required = false) String token,
                                       @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        try {
            PreviewService.Content content = previewService.openPersonalOfficePdf(token, fileId, range);
            ResponseEntity.BodyBuilder builder = content.partial()
                    ? ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    : ResponseEntity.ok();
            builder.header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentLength(content.length())
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename*=UTF-8''" + encodeFileName(content.fileName()));
            if (content.partial()) {
                builder.header(HttpHeaders.CONTENT_RANGE, "bytes " + content.offset() + "-"
                        + (content.offset() + content.length() - 1) + "/" + content.totalSize());
            }
            return builder.body(new InputStreamResource(content.stream()));
        } catch (PreviewService.InvalidPreviewTokenException e) {
            return ResponseEntity.notFound().build();
        } catch (PreviewService.UnsatisfiableRangeException e) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + e.getTotalSize())
                    .build();
        } catch (OfficePreviewService.OfficePreviewException e) {
            return officePreviewError(e.getStatus(), e.getMessage());
        }
    }

    /** Office 预览错误响应：真实状态码 + 统一 JSON 信封（不吞错，客户端可区分 415/503）。 */
    private static ResponseEntity<byte[]> officePreviewError(int status, String message) {
        String safe = message == null ? "Office 预览失败" : message;
        safe = safe.replace("\\", "\\\\").replace("\"", "\\\"");
        String json = "{\"code\":" + status + ",\"message\":\"" + safe + "\",\"data\":null}";
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeFileName(String name) {
        return java.net.URLEncoder.encode(name == null ? "preview.pdf" : name, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private Long personalWorkspaceId(Long userId) {
        return personalSpaceService.resolveWorkspaceId(userId);
    }

    /** 缩略图软限流响应：429 + Retry-After，客户端应降级为类型图标（不阻塞列表）。 */
    private static ResponseEntity<byte[]> tooManyThumbnails(long retryAfterSeconds) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1L, retryAfterSeconds)))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":429,\"message\":\"缩略图请求过于频繁，请稍后重试\",\"data\":null}"
                        .getBytes(StandardCharsets.UTF_8));
    }

    // ── 请求体 ──

    @Data
    static class RenameRequest {
        private Long fileId;
        private String newName;
    }

    @Data
    static class CopyFileRequest {
        /** 目标工作区；null = 个人空间（来源也是个人空间时即同空间复制）。 */
        private Long targetWorkspaceId;
        /** 目标目录；null = 源文件所在目录。 */
        private String targetParentPath;
        /** 新文件名；null = 沿用源文件名（目标同名则 400）。 */
        private String newName;
    }

    @Data
    static class CheckHashRequest {
        private String hash;
        private String fileName;
        private Long fileSize;
        private String parentPath;
    }

    @Data
    static class InitChunkUploadRequest {
        private String fileName;
        private Long fileSize;
        private String parentPath;
        private String fileHash;
        private String contentType;
        private Integer chunkSize;
        private String uploadId;
    }
}
