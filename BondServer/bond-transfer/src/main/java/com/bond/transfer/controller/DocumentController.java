package com.bond.transfer.controller;

import com.bond.common.dto.DocumentDTO;
import com.bond.common.dto.DocumentVersionDTO;
import com.bond.common.dto.Result;
import com.bond.transfer.document.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 在线文档接口（需网关签名；/api/** 由 InternalAuthFilter 统一校验）。
 *
 * <p>权限：工作区文档 = 成员；个人空间文档 = 仅 owner（他人 404）。
 * 并发保存：409 带 {serverVersion,lastEditorName,updatedAt}；{@code force=true} 跳过版本校验覆盖。</p>
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    public record CreateRequest(Long workspaceId, String parentPath, String title, String contentMd,
                                String contentHtml, String contentType) {
    }

    public record SaveRequest(String title, String contentMd, String contentHtml, String contentType,
                              Long version, Long expectedVersion, Boolean force) {
    }

    public record AutosaveRequest(String contentMd, String contentHtml) {
    }

    @PostMapping
    public Result<DocumentDTO> create(@RequestHeader("X-User-Id") Long userId,
                                      @RequestBody CreateRequest request) {
        return Result.ok(documentService.create(userId, request.workspaceId(),
                request.parentPath(), request.title(), request.contentMd(),
                request.contentHtml(), request.contentType()));
    }

    @GetMapping
    public Result<List<DocumentDTO>> list(@RequestHeader("X-User-Id") Long userId,
                                          @RequestParam(required = false) Long workspaceId,
                                          @RequestParam(required = false) String parentPath,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) Integer limit) {
        return Result.ok(documentService.list(userId, workspaceId, parentPath, q, limit));
    }

    @GetMapping("/{id}")
    public Result<DocumentDTO> get(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        return Result.ok(documentService.get(userId, id));
    }

    @PutMapping("/{id}")
    public Result<DocumentDTO> save(@RequestHeader("X-User-Id") Long userId,
                                    @PathVariable Long id,
                                    @RequestParam(required = false) Boolean force,
                                    @RequestBody SaveRequest request) {
        boolean forceFlag = Boolean.TRUE.equals(force) || Boolean.TRUE.equals(request.force());
        Long expectedVersion = request.expectedVersion() != null ? request.expectedVersion() : request.version();
        return Result.ok(documentService.save(userId, id, request.title(), request.contentMd(),
                request.contentHtml(), request.contentType(), expectedVersion, forceFlag));
    }

    /** 自动保存（task-137）：只更新正文；同一人 60 秒内连续调用合并为同一版本号。 */
    @PatchMapping("/{id}")
    public Result<DocumentDTO> autosave(@RequestHeader("X-User-Id") Long userId,
                                        @PathVariable Long id,
                                        @RequestBody AutosaveRequest request) {
        return Result.ok(documentService.autosave(userId, id, request.contentMd(), request.contentHtml()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        documentService.delete(userId, id);
        return Result.ok();
    }

    @GetMapping("/{id}/versions")
    public Result<List<DocumentVersionDTO>> versions(@RequestHeader("X-User-Id") Long userId,
                                                     @PathVariable Long id,
                                                     @RequestParam(required = false, defaultValue = "20") Integer limit,
                                                     @RequestParam(required = false, defaultValue = "0") Integer offset) {
        return Result.ok(documentService.versions(userId, id, limit, offset));
    }

    @GetMapping("/{id}/versions/{version}")
    public Result<DocumentVersionDTO> version(@RequestHeader("X-User-Id") Long userId,
                                              @PathVariable Long id,
                                              @PathVariable Long version) {
        return Result.ok(documentService.version(userId, id, version));
    }

    @PostMapping("/{id}/restore/{version}")
    public Result<DocumentDTO> restoreVersion(@RequestHeader("X-User-Id") Long userId,
                                              @PathVariable Long id,
                                              @PathVariable Long version) {
        return Result.ok(documentService.restoreVersion(userId, id, version));
    }

    /** 导出 md/html：attachment 下载，文件名按 RFC 5987 UTF-8 编码（中文标题可用）。 */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@RequestHeader("X-User-Id") Long userId,
                                         @PathVariable Long id,
                                         @RequestParam(required = false, defaultValue = "md") String format) {
        DocumentService.ExportFile file = documentService.export(userId, id, format);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.content());
    }
}
