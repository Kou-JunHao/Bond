package com.bond.transfer.controller;

import com.bond.common.dto.AdminLoginBlockDTO;
import com.bond.common.dto.AdminLoginGuardDTO;
import com.bond.common.dto.AdminLoginLogDTO;
import com.bond.common.dto.AdminLoginLogStatsDTO;
import com.bond.common.dto.PageResult;
import com.bond.common.dto.Result;
import com.bond.transfer.audit.AdminAudit;
import com.bond.transfer.service.AdminLoginAuditAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端登录审计 + fail2ban 管控（task-144）。
 *
 * <p>权限由 {@code AdminInterceptor}（/api/admin/**）负责；写操作走 {@link AdminAudit}
 * 自动落 {@code admin_operation_logs}。所有响应为统一 {@link Result} 信封，错误码由
 * {@code GlobalExceptionHandler} 映射（400/403/404/429 等）。</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminLoginAuditController {

    private final AdminLoginAuditAdminService adminLoginAuditAdminService;

    /** 登录尝试分页（仅管理后台登录尝试）。from/to 支持 yyyy-MM-dd 或 ISO-8601。 */
    @GetMapping("/login-logs")
    public Result<PageResult<AdminLoginLogDTO>> logs(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "ip", required = false) String ip,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "outcome", required = false) String outcome,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        return Result.ok(adminLoginAuditAdminService.pageLogs(page, size, ip, username, outcome, from, to));
    }

    /** 登录审计统计（默认 7 天，1..90）。 */
    @GetMapping("/login-logs/stats")
    public Result<AdminLoginLogStatsDTO> stats(@RequestParam(value = "days", defaultValue = "7") int days) {
        return Result.ok(adminLoginAuditAdminService.stats(days));
    }

    /** 封禁列表：active=true 仅当前生效；active=false 已解除/已到期；缺省全部。 */
    @GetMapping("/login-blocks")
    public Result<PageResult<AdminLoginBlockDTO>> blocks(
            @RequestParam(value = "active", required = false) Boolean active,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "value", required = false) String value) {
        return Result.ok(adminLoginAuditAdminService.pageBlocks(active, page, size, scope, value));
    }

    /** 人工解除封禁（幂等失败语义：不存在/已解除 → 404）。 */
    @PostMapping("/login-blocks/{id}/lift")
    @AdminAudit(action = "admin-login.block.lift", targetType = "login-block")
    public ResponseEntity<Void> lift(@PathVariable("id") Long id,
                                     @RequestHeader("X-User-Id") Long actorId) {
        adminLoginAuditAdminService.lift(id, actorId);
        return ResponseEntity.noContent().build();
    }

    /** 当前策略。 */
    @GetMapping("/login-guard")
    public Result<AdminLoginGuardDTO> guard() {
        return Result.ok(adminLoginAuditAdminService.guard());
    }

    /** 部分更新策略（扁平字段，缺省不改；ipAllowlist 可传数组或逗号分隔字符串）。 */
    @PutMapping("/login-guard")
    @AdminAudit(action = "admin-login.guard.update", targetType = "config")
    public Result<AdminLoginGuardDTO> updateGuard(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader("X-User-Id") Long actorId) {
        return Result.ok(adminLoginAuditAdminService.updateGuard(body, actorId));
    }
}
