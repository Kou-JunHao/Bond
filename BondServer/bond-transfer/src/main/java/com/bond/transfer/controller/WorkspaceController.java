package com.bond.transfer.controller;

import com.bond.common.dto.FileSearchResult;
import com.bond.common.dto.Result;
import com.bond.common.dto.WorkspaceDTO;
import com.bond.common.dto.WorkspaceFileDTO;
import com.bond.common.dto.WorkspaceMemberDTO;
import com.bond.common.dto.InvitationDTO;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.enums.WorkspaceRole;
import com.bond.transfer.acl.WorkspaceAccess;
import com.bond.transfer.acl.WorkspaceAclDtos;
import com.bond.transfer.acl.WorkspaceAclService;
import com.bond.transfer.preview.EtagMatch;
import com.bond.transfer.preview.OfficePreviewService;
import com.bond.transfer.preview.PreviewService;
import com.bond.transfer.service.FileSearchService;
import com.bond.transfer.service.WorkspaceService;
import com.bond.transfer.service.WorkspaceInvitationService;
import com.bond.transfer.trash.FileTree;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WorkspaceInvitationService invitationService;
    private final PreviewService previewService;
    private final FileSearchService fileSearchService;
    /** task-183B：工作区文件级 ACL（强制点 + 授权管理）。 */
    private final WorkspaceAclService workspaceAclService;

    @PostMapping
    public Result<WorkspaceDTO> create(@RequestHeader("X-User-Id") Long userId,
                                       @RequestBody CreateWorkspaceRequest req) {
        return Result.ok(workspaceService.createWorkspace(userId, req.getName(), req.getDescription()));
    }

    @GetMapping
    public Result<List<WorkspaceDTO>> list(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(workspaceService.listWorkspaces(userId));
    }

    @GetMapping("/{id}")
    public Result<WorkspaceDTO> get(@RequestHeader("X-User-Id") Long userId,
                                    @PathVariable Long id) {
        return Result.ok(workspaceService.getWorkspace(id, userId));
    }

    /**
     * 更新工作区（名称/描述/配额），同时接受 PUT 与 PATCH。
     *
     * <p>必须用显式的 {@code @RequestMapping(method = {...})}：Spring 不支持在同一方法上叠加两个
     * 快捷注解（{@code @PutMapping} + {@code @PatchMapping}），只会注册其中一个，另一个会得到
     * 500 {@code Request method 'PATCH' is not supported}（2026-10-22 生产实测踩坑）。</p>
     */
    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public Result<WorkspaceDTO> update(@RequestHeader("X-User-Id") Long userId,
                                       @PathVariable Long id,
                                       @RequestBody UpdateWorkspaceRequest req) {
        return Result.ok(workspaceService.updateWorkspace(id, userId, req.getName(), req.getDescription(), req.getMaxSize()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestHeader("X-User-Id") Long userId,
                               @PathVariable Long id) {
        workspaceService.deleteWorkspace(id, userId);
        return Result.ok();
    }

    @PostMapping("/{id}/members")
    public Result<Void> addMember(@RequestHeader("X-User-Id") Long userId,
                                  @PathVariable Long id,
                                  @RequestBody AddMemberRequest req) {
        workspaceService.addMember(id, userId, req.getUserId(),
                req.getRole() != null ? WorkspaceRole.values()[req.getRole()] : WorkspaceRole.MEMBER);
        return Result.ok();
    }

    @PutMapping("/{id}/members/{userId}")
    public Result<Void> updateMemberRole(@RequestHeader("X-User-Id") Long currentUserId,
                                         @PathVariable Long id,
                                         @PathVariable Long userId,
                                         @RequestBody UpdateMemberRoleRequest req) {
        workspaceService.updateMemberRole(id, currentUserId, userId, req.getRole());
        return Result.ok();
    }

    @DeleteMapping("/{id}/members/{userId}")
    public Result<Void> removeMember(@RequestHeader("X-User-Id") Long currentUserId,
                                     @PathVariable Long id,
                                     @PathVariable Long userId) {
        workspaceService.removeMember(id, currentUserId, userId);
        return Result.ok();
    }

    @GetMapping("/{id}/members")
    public Result<List<WorkspaceMemberDTO>> listMembers(@RequestHeader("X-User-Id") Long userId,
                                                        @PathVariable Long id) {
        return Result.ok(workspaceService.listMembers(id, userId));
    }

    /**
     * 目录列表（裸数组）。task-183B：restricted 成员先做目录可读校验（未授权 → 404），
     * 再按 ACL 过滤子项 —— 只返回被授权的文件 / 可以继续下钻的目录。
     */
    @GetMapping("/{id}/files")
    public Result<List<WorkspaceFileDTO>> listFiles(@RequestHeader("X-User-Id") Long userId,
                                                    @PathVariable Long id,
                                                    @RequestParam(required = false) String parentPath) {
        List<WorkspaceFileDTO> files = workspaceService.listFiles(id, userId, parentPath);
        WorkspaceAccess access = workspaceAclService.resolve(id, userId);
        if (access == null || !access.isRestricted()) {
            return Result.ok(files);
        }
        String dirPath = FileTree.normalizePath(parentPath);
        if (!access.canReadDirectory(dirPath)) {
            // 与「目录不存在」同口径，不泄露未授权目录的存在性
            throw new com.bond.common.exception.BusinessException(404, "目录不存在");
        }
        return Result.ok(files.stream()
                .filter(file -> Boolean.TRUE.equals(file.getIsDirectory())
                        ? access.canReadDirectory(FileTree.joinPath(file.getParentPath(), file.getFileName()))
                        : access.canReadFile(file.getId(), FileTree.joinPath(file.getParentPath(), file.getFileName())))
                .toList());
    }

    // ── task-183B：成员级文件 ACL（owner/admin 管理；成员本人可读自己的有效范围） ──

    /**
     * 读取某成员的 ACL（owner/admin）：{@code {workspaceId,userId,accessMode,grants[]}}；
     * {@code accessMode=all} 时 grants 恒为空。
     */
    @GetMapping("/{id}/members/{memberUserId}/acl")
    public Result<WorkspaceAclDtos.View> memberAcl(@RequestHeader("X-User-Id") Long userId,
                                                   @PathVariable Long id,
                                                   @PathVariable Long memberUserId) {
        return Result.ok(workspaceAclService.memberAcl(userId, id, memberUserId));
    }

    /**
     * 整包替换某成员的 ACL（owner/admin）。body：{@code {accessMode:"all"|"restricted", grants:[{scopeType,
     * scopePath,fileId,canRead,canWrite}]}}；{@code restricted} 时未出现在请求里的授权会被删除
     * （授权目录/文件必须真实存在；canWrite 会强制 canRead）。目标为 owner/admin → 400。
     */
    @PutMapping("/{id}/members/{memberUserId}/acl")
    public Result<WorkspaceAclDtos.View> replaceMemberAcl(@RequestHeader("X-User-Id") Long userId,
                                                          @PathVariable Long id,
                                                          @PathVariable Long memberUserId,
                                                          @RequestBody(required = false) WorkspaceAclDtos.PutRequest request,
                                                          HttpServletRequest httpRequest) {
        return Result.ok(workspaceAclService.replaceAcl(userId, id, memberUserId, request,
                clientIp(httpRequest)));
    }

    /** 本人有效范围（客户端据此渲染「只读/可写」与隐藏未授权目录）。 */
    @GetMapping("/{id}/acl/me")
    public Result<WorkspaceAclDtos.View> myAcl(@RequestHeader("X-User-Id") Long userId,
                                               @PathVariable Long id) {
        return Result.ok(workspaceAclService.myAcl(userId, id));
    }

    @GetMapping("/{id}/files/search")
    public Result<FileSearchResult> searchFiles(@RequestHeader("X-User-Id") Long userId,
                                                @PathVariable Long id,
                                                @RequestParam String q,
                                                @RequestParam(required = false) Integer limit,
                                                @RequestParam(required = false) String cursor) {
        return Result.ok(fileSearchService.search(userId, q, id, limit, cursor));
    }

    @PostMapping("/{id}/files/upload")
    public Result<WorkspaceFileDTO> uploadFile(@RequestHeader("X-User-Id") Long userId,
                                               @PathVariable Long id,
                                               @RequestParam(required = false) String parentPath,
                                               @RequestParam(required = false) String fileHash,
                                               @RequestParam MultipartFile file) throws Exception {
        // task-183B：上传目标目录必须有写权限（restricted 成员未授权 → 403）
        workspaceAclService.requireWritableDirectory(id, userId, parentPath);
        return Result.ok(workspaceService.uploadFile(id, userId, parentPath, file, fileHash));
    }

    @PostMapping("/{id}/files/folders")
    public Result<WorkspaceFileDTO> createFolder(@RequestHeader("X-User-Id") Long userId,
                                                 @PathVariable Long id,
                                                 @RequestBody CreateFolderRequest req) {
        workspaceAclService.requireWritableDirectory(id, userId, req.getParentPath());
        return Result.ok(workspaceService.createFolder(id, userId, req.getParentPath(), req.getName()));
    }

    @PostMapping("/{id}/files/chunks/init")
    public Result<Map<String, String>> initChunkUpload(@RequestHeader("X-User-Id") Long userId,
                                                       @PathVariable Long id,
                                                       @RequestBody InitChunkUploadRequest req) throws Exception {
        workspaceAclService.requireWritableDirectory(id, userId, req.getParentPath());
        String uploadId = workspaceService.initChunkUpload(
                id, userId, req.getFileName(), req.getFileSize(), req.getParentPath(),
                req.getFileHash(), req.getContentType(), req.getChunkSize(), req.getUploadId());
        return Result.ok(Map.of("uploadId", uploadId));
    }

    @PutMapping("/{id}/files/chunks/{uploadId}/{index}")
    public Result<Void> uploadChunk(@RequestHeader("X-User-Id") Long userId,
                                    @PathVariable Long id,
                                    @PathVariable String uploadId,
                                    @PathVariable int index,
                                    @RequestParam MultipartFile file) throws Exception {
        workspaceService.uploadChunk(id, userId, uploadId, index, file.getInputStream(), file.getSize());
        return Result.ok();
    }

    @GetMapping("/{id}/files/chunks/{uploadId}/status")
    public Result<Map<String, Object>> chunkStatus(@RequestHeader("X-User-Id") Long userId,
                                                   @PathVariable Long id,
                                                   @PathVariable String uploadId) {
        return Result.ok(workspaceService.chunkStatus(id, userId, uploadId));
    }

    @PostMapping("/{id}/files/chunks/{uploadId}/complete")
    public Result<WorkspaceFileDTO> completeChunkUpload(@RequestHeader("X-User-Id") Long userId,
                                                       @PathVariable Long id,
                                                       @PathVariable String uploadId) throws Exception {
        // task-183B：秒传（instant:<fileId>）完成会直接返回已存在的文件行，必须校验读权限，
        // 否则受限成员可以借秒传探测/拿到未授权文件的 DTO。
        if (uploadId != null && uploadId.startsWith("instant:")) {
            try {
                Long existingId = Long.parseLong(uploadId.substring("instant:".length()));
                workspaceAclService.requireReadable(id, userId, workspaceService.requireActiveFile(id, existingId));
            } catch (NumberFormatException ignored) {
                // 畸形 uploadId 交给 service 按原有语义处理
            }
        }
        return Result.ok(workspaceService.completeChunkUpload(id, userId, uploadId));
    }

    @PostMapping("/{id}/files/check-hash")
    public Result<WorkspaceFileDTO> checkHash(@RequestHeader("X-User-Id") Long userId,
                                              @PathVariable Long id,
                                              @RequestBody CheckHashRequest req) {
        workspaceAclService.requireWritableDirectory(id, userId, req.getParentPath());
        return Result.ok(workspaceService.checkHash(id, userId, req.getHash(), req.getFileName(), req.getFileSize(), req.getParentPath()));
    }

    @GetMapping("/{id}/files/{fileId}/download")
    public ResponseEntity<InputStreamResource> downloadFile(@RequestHeader("X-User-Id") Long userId,
                                                            @PathVariable Long id,
                                                            @PathVariable Long fileId) throws Exception {
        WorkspaceFile target = workspaceService.requireActiveFile(id, fileId);
        workspaceAclService.requireReadable(id, userId, target);
        var stream = workspaceService.downloadFile(id, userId, fileId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .contentLength(stream.fileSize())
                .body(new InputStreamResource(stream.inputStream()));
    }

    // ── 预览：短期令牌 URL / 缩略图 / 内容流（token 在 query，播放器无需 Authorization） ──

    @GetMapping("/{id}/files/{fileId}/preview-url")
    public Result<PreviewService.PreviewUrl> previewUrl(@RequestHeader("X-User-Id") Long userId,
                                                        @PathVariable Long id,
                                                        @PathVariable Long fileId) {
        // task-183B：令牌签发前校验读权限；content/thumbnail/preview/office-pdf 都以此 token 为前提
        workspaceAclService.requireReadable(id, userId, workspaceService.requireActiveFile(id, fileId));
        return Result.ok(previewService.workspacePreviewUrls(userId, id, fileId));
    }

    @GetMapping("/{id}/files/{fileId}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable Long id,
                                            @PathVariable Long fileId,
                                            @RequestParam(required = false) String w,
                                            @RequestParam(required = false) String token,
                                            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
                                            String ifNoneMatch) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        try {
            PreviewService.Thumbnail thumb = previewService.workspaceThumbnail(token, id, fileId, w);
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
            // 软限流：客户端降级为类型图标；429 而非 204，便于区分"被限流"与"无缩略图"
            return tooManyThumbnails(e.getRetryAfterSeconds());
        } catch (Exception e) {
            return ResponseEntity.noContent().build();
        }
    }

    /** PDF 单页预览：page 从 1 开始；非法值回退 1；越界 416 + X-Total-Pages；非 PDF 204。 */
    @GetMapping("/{id}/files/{fileId}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable Long id,
                                          @PathVariable Long fileId,
                                          @RequestParam(required = false) String page,
                                          @RequestParam(required = false) String w,
                                          @RequestParam(required = false) String token) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        try {
            PreviewService.Page result = previewService.workspacePreviewPage(token, id, fileId, page, w);
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

    @GetMapping("/{id}/files/{fileId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable Long id,
                                                       @PathVariable Long fileId,
                                                       @RequestParam(required = false) String token,
                                                       @RequestHeader(value = HttpHeaders.RANGE, required = false)
                                                       String range) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        try {
            PreviewService.Content content = previewService.openWorkspaceContent(token, id, fileId, range);
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
    @GetMapping("/{id}/files/{fileId}/office-pdf")
    public ResponseEntity<?> officePdf(@PathVariable Long id,
                                       @PathVariable Long fileId,
                                       @RequestParam(required = false) String token,
                                       @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        try {
            PreviewService.Content content = previewService.openWorkspaceOfficePdf(token, id, fileId, range);
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

    @DeleteMapping("/{id}/files/{fileId}")
    public Result<Void> deleteFile(@RequestHeader("X-User-Id") Long userId,
                                   @PathVariable Long id,
                                   @PathVariable Long fileId) {
        workspaceAclService.requireWritable(id, userId, workspaceService.requireActiveFile(id, fileId));
        workspaceService.deleteFile(id, userId, fileId);
        return Result.ok();
    }

    @PutMapping("/{id}/files/{fileId}")
    public Result<WorkspaceFileDTO> renameFile(@RequestHeader("X-User-Id") Long userId,
                                               @PathVariable Long id,
                                               @PathVariable Long fileId,
                                               @RequestBody RenameFileRequest req) {
        workspaceAclService.requireWritable(id, userId, workspaceService.requireActiveFile(id, fileId));
        return Result.ok(workspaceService.renameFile(id, userId, fileId, req.getNewName()));
    }

    // ── 转存（服务端 S3 复制，不计流量；task-102） ──

    /**
     * 把工作区文件转存到目标目录（可跨工作区：来源需可读、目标需为成员）。
     * 不传 targetWorkspaceId 时目标 = 来源工作区。配额不足 413、同名 400、目录 400。
     */
    @PostMapping("/{id}/files/{fileId}/copy")
    public Result<WorkspaceFileDTO> copyFile(@RequestHeader("X-User-Id") Long userId,
                                             @PathVariable Long id,
                                             @PathVariable Long fileId,
                                             @RequestBody(required = false) CopyFileRequest req) {
        Long targetId = req != null && req.getTargetWorkspaceId() != null ? req.getTargetWorkspaceId() : id;
        workspaceAclService.requireReadable(id, userId, workspaceService.requireActiveFile(id, fileId));
        workspaceAclService.requireWritableDirectory(targetId, userId,
                req == null ? null : req.getTargetParentPath());
        return Result.ok(workspaceService.copyFile(userId, id, fileId, targetId,
                req == null ? null : req.getTargetParentPath(),
                req == null ? null : req.getNewName()));
    }

    // ── 移动（task-132：同空间不动配额；跨空间原子改两侧 used_size） ──

    /**
     * 移动工作区文件/文件夹到目标目录（可跨工作区：来源需可读、目标需为成员）。
     * 不传 targetWorkspaceId 时目标 = 来源工作区（同空间移动不复制对象、不改 used_size）。
     */
    @PostMapping("/{id}/files/{fileId}/move")
    public Result<WorkspaceFileDTO> moveFile(@RequestHeader("X-User-Id") Long userId,
                                             @PathVariable Long id,
                                             @PathVariable Long fileId,
                                             @RequestBody(required = false) CopyFileRequest req) {
        Long targetId = req != null && req.getTargetWorkspaceId() != null ? req.getTargetWorkspaceId() : id;
        workspaceAclService.requireWritable(id, userId, workspaceService.requireActiveFile(id, fileId));
        workspaceAclService.requireWritableDirectory(targetId, userId,
                req == null ? null : req.getTargetParentPath());
        return Result.ok(workspaceService.moveFile(userId, id, fileId, targetId,
                req == null ? null : req.getTargetParentPath(),
                req == null ? null : req.getNewName()));
    }

    // ── Invitation APIs ──

    @PostMapping("/{id}/invite/link")
    public Result<String> generateInviteLink(@RequestHeader("X-User-Id") Long userId,
                                             @PathVariable Long id) {
        return Result.ok(invitationService.generateInviteLink(id, userId));
    }

    @PostMapping("/{id}/invite/friend")
    public Result<Void> inviteFriend(@RequestHeader("X-User-Id") Long userId,
                                     @PathVariable Long id,
                                     @RequestBody InviteFriendRequest req) {
        invitationService.inviteFriend(id, userId, req.getUserId(),
                req.getRole() != null ? req.getRole() : WorkspaceRole.MEMBER.getValue());
        return Result.ok();
    }

    @GetMapping("/invitations")
    public Result<List<InvitationDTO>> getInvitations(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(invitationService.getPendingInvitations(userId));
    }

    @PostMapping("/invitations/{invId}/accept")
    public Result<Void> acceptInvitation(@RequestHeader("X-User-Id") Long userId,
                                         @PathVariable Long invId) {
        invitationService.acceptInvitation(invId, userId);
        return Result.ok();
    }

    @PostMapping("/invitations/{invId}/reject")
    public Result<Void> rejectInvitation(@RequestHeader("X-User-Id") Long userId,
                                         @PathVariable Long invId) {
        invitationService.rejectInvitation(invId, userId);
        return Result.ok();
    }

    /** 缩略图软限流响应：429 + Retry-After，客户端应降级为类型图标（不阻塞列表）。 */
    private static ResponseEntity<byte[]> tooManyThumbnails(long retryAfterSeconds) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1L, retryAfterSeconds)))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":429,\"message\":\"缩略图请求过于频繁，请稍后重试\",\"data\":null}"
                        .getBytes(StandardCharsets.UTF_8));
    }

    /** 审计用客户端 IP（与 ShareController 同口径：优先 X-Forwarded-For 第一个）。 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    @Data
    static class CreateFolderRequest {
        /** 父路径；空/null 表示工作区根目录。 */
        private String parentPath;
        /** 目录名（不含路径分隔符）。 */
        private String name;
    }

    @Data
    static class CreateWorkspaceRequest {
        private String name;
        private String description;
    }

    @Data
    static class UpdateWorkspaceRequest {
        private String name;
        private String description;
        /** 配额上限（字节）；null 表示不修改，受 owner 档位空间额度约束 */
        private Long maxSize;
    }

    @Data
    static class UpdateMemberRoleRequest {
        /** 1=ADMIN, 2=MEMBER（0=OWNER 不可指派） */
        private Integer role;
    }

    @Data
    static class AddMemberRequest {
        private Long userId;
        private Integer role;
    }

    @Data
    static class InviteFriendRequest {
        private Long userId;
        private Integer role;
    }

    @Data
    static class RenameFileRequest {
        private String newName;
    }

    @Data
    static class CopyFileRequest {
        /** 目标工作区；null = 来源工作区（同空间复制）。 */
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
