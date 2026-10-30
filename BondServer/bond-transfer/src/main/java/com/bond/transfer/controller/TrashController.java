package com.bond.transfer.controller;

import com.bond.common.dto.Result;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.acl.WorkspaceAccess;
import com.bond.transfer.acl.WorkspaceAclService;
import com.bond.transfer.trash.TrashBatchResult;
import com.bond.transfer.trash.TrashItem;
import com.bond.transfer.trash.TrashService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 回收站接口（均需网关签名；/api/** 由 InternalAuthFilter 统一校验）。
 *
 * <ul>
 *   <li>GET /api/trash?workspaceId= → 200，顶层条目列表（不传 workspaceId 取个人空间）</li>
 *   <li>POST /api/trash/restore {ids} → 200 全成功 / 207 部分失败</li>
 *   <li>POST /api/trash/purge {ids} → 200 全成功 / 207 部分失败（物理删除，不可恢复）</li>
 *   <li>DELETE /api/trash?workspaceId= → 204 清空</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/trash")
@RequiredArgsConstructor
public class TrashController {

    private final TrashService trashService;
    /** task-183B：受限成员的回收站列表过滤 + 恢复/彻底删除的写权限校验。 */
    private final WorkspaceAclService workspaceAclService;

    @GetMapping
    public Result<List<TrashItem>> list(@RequestHeader("X-User-Id") Long userId,
                                        @RequestParam(required = false) Long workspaceId) {
        List<TrashItem> items = trashService.list(userId, workspaceId);
        if (workspaceId == null) {
            // 个人空间 = 本人 owner，不受 ACL 影响
            return Result.ok(items);
        }
        WorkspaceAccess access = workspaceAclService.resolve(workspaceId, userId);
        if (access == null || !access.isRestricted()) {
            return Result.ok(items);
        }
        return Result.ok(items.stream().filter(item -> visibleTo(access, item)).toList());
    }

    /** 回收站条目对受限成员是否可见（文档不属于本期 ACL 范围，原样放行；见设计文档剩余缺口）。 */
    private static boolean visibleTo(WorkspaceAccess access, TrashItem item) {
        if ("document".equals(item.kind())) {
            return true;
        }
        String path = item.originalPath() == null || item.originalPath().isBlank() ? "/" : item.originalPath();
        return Boolean.TRUE.equals(item.isDirectory())
                ? access.canReadDirectory(path)
                : access.canReadFile(item.id(), path);
    }

    @PostMapping("/restore")
    public ResponseEntity<Result<TrashBatchResult>> restore(@RequestHeader("X-User-Id") Long userId,
                                                            @RequestBody IdsRequest request) {
        workspaceAclService.requireWritableTrashEntries(userId, request.ids());
        return batchResponse(trashService.restore(userId, request.ids()));
    }

    @PostMapping("/purge")
    public ResponseEntity<Result<TrashBatchResult>> purge(@RequestHeader("X-User-Id") Long userId,
                                                          @RequestBody IdsRequest request) {
        workspaceAclService.requireWritableTrashEntries(userId, request.ids());
        return batchResponse(trashService.purge(userId, request.ids()));
    }

    @DeleteMapping
    public ResponseEntity<Void> empty(@RequestHeader("X-User-Id") Long userId,
                                      @RequestParam(required = false) Long workspaceId) {
        // 清空是整空间操作：受限成员不能触发（否则等于越权销毁未授权内容）
        if (workspaceId != null && workspaceAclService.isRestricted(workspaceId, userId)) {
            throw new BusinessException(403, "受限成员不能清空回收站");
        }
        int purged = trashService.empty(userId, workspaceId);
        return ResponseEntity.noContent()
                .header("X-Trash-Purged", String.valueOf(purged))
                .build();
    }

    /** 部分失败返回 207，body 仍是统一 Result（code=207，data 为逐条结果）。 */
    private ResponseEntity<Result<TrashBatchResult>> batchResponse(TrashBatchResult result) {
        Result<TrashBatchResult> body = Result.ok(result);
        if (result.hasFailure()) {
            body.setCode(HttpStatus.MULTI_STATUS.value());
            body.setMessage("部分成功");
            return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(body);
        }
        return ResponseEntity.ok(body);
    }

    /** 批量操作请求体。 */
    public record IdsRequest(List<Long> ids) {
    }
}
