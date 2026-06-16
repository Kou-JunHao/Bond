package com.bond.transfer.controller;

import com.bond.common.dto.Result;
import com.bond.common.dto.WorkspaceDTO;
import com.bond.common.dto.WorkspaceFileDTO;
import com.bond.common.enums.WorkspaceRole;
import com.bond.transfer.service.WorkspaceService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

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

    @PutMapping("/{id}")
    public Result<WorkspaceDTO> update(@RequestHeader("X-User-Id") Long userId,
                                       @PathVariable Long id,
                                       @RequestBody UpdateWorkspaceRequest req) {
        return Result.ok(workspaceService.updateWorkspace(id, userId, req.getName(), req.getDescription()));
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

    @DeleteMapping("/{id}/members/{userId}")
    public Result<Void> removeMember(@RequestHeader("X-User-Id") Long currentUserId,
                                     @PathVariable Long id,
                                     @PathVariable Long userId) {
        workspaceService.removeMember(id, currentUserId, userId);
        return Result.ok();
    }

    @GetMapping("/{id}/files")
    public Result<List<WorkspaceFileDTO>> listFiles(@RequestHeader("X-User-Id") Long userId,
                                                    @PathVariable Long id,
                                                    @RequestParam(required = false) String parentPath) {
        return Result.ok(workspaceService.listFiles(id, userId, parentPath));
    }

    @PostMapping("/{id}/files/upload")
    public Result<WorkspaceFileDTO> uploadFile(@RequestHeader("X-User-Id") Long userId,
                                               @PathVariable Long id,
                                               @RequestParam(required = false) String parentPath,
                                               @RequestParam MultipartFile file) throws Exception {
        return Result.ok(workspaceService.uploadFile(id, userId, parentPath, file));
    }

    @GetMapping("/{id}/files/{fileId}/download")
    public ResponseEntity<InputStreamResource> downloadFile(@RequestHeader("X-User-Id") Long userId,
                                                            @PathVariable Long id,
                                                            @PathVariable Long fileId) throws Exception {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .body(new InputStreamResource(workspaceService.downloadFile(id, userId, fileId)));
    }

    @DeleteMapping("/{id}/files/{fileId}")
    public Result<Void> deleteFile(@RequestHeader("X-User-Id") Long userId,
                                   @PathVariable Long id,
                                   @PathVariable Long fileId) {
        workspaceService.deleteFile(id, userId, fileId);
        return Result.ok();
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
    }

    @Data
    static class AddMemberRequest {
        private Long userId;
        private Integer role;
    }
}
