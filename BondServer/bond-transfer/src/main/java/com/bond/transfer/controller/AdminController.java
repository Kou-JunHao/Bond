package com.bond.transfer.controller;

import com.bond.common.dto.*;
import com.bond.common.entity.SystemConfig;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.audit.AdminAudit;
import com.bond.transfer.service.AdminService;
import com.bond.transfer.service.AdminUserEmailService;
import com.bond.transfer.service.AnnouncementService;
import com.bond.transfer.service.AuditService;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.EntitlementAdminService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final ConfigService configService;
    private final EntitlementAdminService entitlementAdminService;
    private final AnnouncementService announcementService;
    private final AuditService auditService;
    private final AdminUserEmailService adminUserEmailService;

    // ── Entitlements（task-7：档位 / 加量 / 兑换码） ──

    @AdminAudit(action = "user.plan.set", targetType = "user")
    @PostMapping("/users/{id}/plan")
    public Result<Void> setUserPlan(@RequestHeader("X-User-Id") Long adminUserId,
                                    @PathVariable Long id,
                                    @RequestBody SetPlanRequest req) {
        entitlementAdminService.setUserPlan(adminUserId, id, req.getPlanCode(), req.getMonths(), req.getExpiresAt());
        return Result.ok();
    }

    @AdminAudit(action = "user.grant", targetType = "user")
    @PostMapping("/grant")
    public Result<Void> grantEntitlement(@RequestHeader("X-User-Id") Long adminUserId,
                                         @RequestBody GrantRequest req) {
        if (req.getValue() == null) {
            return Result.fail(400, "value is required");
        }
        entitlementAdminService.grant(adminUserId, req.getUserId(), req.getKey(), req.getValue(),
                req.getExpiresAt(), req.getNote());
        return Result.ok();
    }

    @AdminAudit(action = "redeem_codes.create", targetType = "redeem_code")
    @PostMapping("/redeem-codes")
    public Result<List<String>> createRedeemCodes(@RequestHeader("X-User-Id") Long adminUserId,
                                                  @RequestBody RedeemCodesRequest req) {
        return Result.ok(entitlementAdminService.createRedeemCodes(
                adminUserId, req.getPlanCode(), req.getDurationDays(), req.getCount(), req.getExpiresAt()));
    }

    // ── Dashboard ──

    @GetMapping("/dashboard")
    public Result<DashboardDTO> dashboard() {
        return Result.ok(adminService.getDashboard());
    }

    @GetMapping("/dashboard/transfer-trend")
    public Result<Map<String, Long>> transferTrend(@RequestParam(defaultValue = "7") int days) {
        return Result.ok(adminService.getTransferTrend(days));
    }

    // ── Config ──

    @GetMapping("/config")
    public Result<List<SystemConfig>> listConfig() {
        return Result.ok(configService.listAll());
    }

    @GetMapping("/config/{key}")
    public Result<String> getConfig(@PathVariable String key) {
        String val = configService.get(key);
        if (val == null) return Result.fail(404, "配置项不存在");
        return Result.ok(val);
    }

    @AdminAudit(action = "config.update", targetType = "config")
    @PutMapping("/config/{key}")
    public Result<Void> updateConfig(@PathVariable String key,
                                     @RequestHeader("X-User-Id") Long userId,
                                     @RequestBody UpdateConfigRequest req) {
        configService.update(key, req.getValue(), userId);
        return Result.ok();
    }

    @AdminAudit(action = "config.batch_update", targetType = "config")
    @PutMapping("/config")
    public Result<Void> batchUpdateConfig(@RequestHeader("X-User-Id") Long userId,
                                          @RequestBody Map<String, String> updates) {
        configService.batchUpdate(updates, userId);
        return Result.ok();
    }

    @AdminAudit(action = "config.reset", targetType = "config")
    @PostMapping("/config/reset")
    public Result<Void> resetConfig() {
        configService.resetToDefaults();
        return Result.ok();
    }

    // ── Users ──

    @GetMapping("/users")
    public Result<List<AdminUserDTO>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return Result.ok(adminService.listUsers(page, size, keyword));
    }

    @AdminAudit(action = "user.status.set", targetType = "user")
    @PutMapping("/users/{id}/status")
    public Result<Void> setUserStatus(@PathVariable Long id, @RequestBody StatusRequest req) {
        adminService.setUserStatus(id, req.getStatus());
        return Result.ok();
    }

    @AdminAudit(action = "user.admin.set", targetType = "user")
    @PutMapping("/users/{id}/admin")
    public Result<Void> setAdmin(@PathVariable Long id, @RequestBody AdminRequest req) {
        adminService.setAdmin(id, req.getIsAdmin());
        return Result.ok();
    }

    @AdminAudit(action = "user.delete", targetType = "user")
    @DeleteMapping("/users/{id}")
    public Result<Void> deleteUser(@PathVariable Long id) {
        adminService.deleteUser(id);
        return Result.ok();
    }

    @GetMapping("/users/{id}")
    public Result<AdminUserDTO> getUserDetail(@PathVariable Long id) {
        return Result.ok(adminService.getUserDetail(id));
    }

    @GetMapping("/users/stats")
    public Result<Map<String, Long>> userStats() {
        return Result.ok(adminService.getUserStats());
    }

    // ── Users：邮箱 / 密码重置（task-57） ──

    /**
     * 管理员编辑用户邮箱（task-57）。任务书要求 PUT；同时保留 PATCH 兼容旧客户端。
     * 注意：不能把 @PutMapping 与 @PatchMapping 叠在同一个方法上（Spring 只注册其中一个，
     * 另一个会 500）——必须用 @RequestMapping 显式声明两种 method，见 pitfalls #13。
     */
    @AdminAudit(action = "user.email.update", targetType = "user")
    @RequestMapping(value = "/users/{id}/email", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public Result<AdminUserDTO> updateUserEmail(@PathVariable Long id, @RequestBody UpdateEmailRequest req) {
        return Result.ok(adminUserEmailService.updateEmail(id, req.getEmail()));
    }

    /** 重置密码：临时密码只发用户邮箱，接口不回显（返回 {delivered, emailMasked}）。 */
    @AdminAudit(action = "user.password.reset", targetType = "user")
    @PostMapping("/users/{id}/reset-password")
    public Result<Map<String, Object>> resetUserPassword(@PathVariable Long id) {
        return Result.ok(adminUserEmailService.resetPassword(id));
    }

    // ── Transfers ──

    @GetMapping("/transfers")
    public Result<List<TransferTaskDTO>> listTransfers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer status) {
        return Result.ok(adminService.listTransfers(page, size, status));
    }

    @AdminAudit(action = "transfer.delete", targetType = "transfer")
    @DeleteMapping("/transfers/{id}")
    public Result<Void> deleteTransfer(@PathVariable Long id) {
        adminService.deleteTransfer(id);
        return Result.ok();
    }

    @AdminAudit(action = "transfer.cleanup", targetType = "transfer")
    @PostMapping("/transfers/cleanup")
    public Result<Integer> cleanupTransfers() {
        return Result.ok(adminService.cleanupExpired());
    }

    @GetMapping("/transfers/stats")
    public Result<Map<String, Long>> transferStats() {
        return Result.ok(adminService.getTransferStats());
    }

    // ── Workspaces ──

    @GetMapping("/workspaces")
    public Result<List<WorkspaceDTO>> listWorkspaces(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(adminService.listWorkspaces(page, size));
    }

    @AdminAudit(action = "workspace.delete", targetType = "workspace")
    @DeleteMapping("/workspaces/{id}")
    public Result<Void> deleteWorkspace(@PathVariable Long id) {
        adminService.deleteWorkspace(id);
        return Result.ok();
    }

    @GetMapping("/workspaces/{id}")
    public Result<Map<String, Object>> getWorkspaceDetail(@PathVariable Long id) {
        return Result.ok(adminService.getWorkspaceDetail(id));
    }

    // ── Announcements（任务单 #19） ──

    @GetMapping("/announcements")
    public Result<PageResult<AnnouncementDTO>> listAnnouncements(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(announcementService.listForAdmin(page, size));
    }

    @AdminAudit(action = "announcement.create", targetType = "announcement")
    @PostMapping("/announcements")
    public Result<AnnouncementDTO> createAnnouncement(@RequestHeader("X-User-Id") Long adminUserId,
                                                      @RequestBody AnnouncementUpsertRequest req) {
        return Result.ok(announcementService.create(adminUserId, req));
    }

    @AdminAudit(action = "announcement.update", targetType = "announcement")
    @PutMapping("/announcements/{id}")
    public Result<AnnouncementDTO> updateAnnouncement(@RequestHeader("X-User-Id") Long adminUserId,
                                                      @PathVariable Long id,
                                                      @RequestBody AnnouncementUpsertRequest req) {
        return Result.ok(announcementService.update(adminUserId, id, req));
    }

    @AdminAudit(action = "announcement.delete", targetType = "announcement")
    @DeleteMapping("/announcements/{id}")
    public Result<Void> deleteAnnouncement(@RequestHeader("X-User-Id") Long adminUserId,
                                           @PathVariable Long id) {
        announcementService.delete(adminUserId, id);
        return Result.ok();
    }

    // ── Audit logs（任务单 #19） ──

    @GetMapping("/audit-logs")
    public Result<PageResult<AuditLogDTO>> listAuditLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return Result.ok(auditService.list(page, size, action, actorId, parseTime(from), parseTime(to)));
    }

    @GetMapping("/audit-actions")
    public Result<List<String>> auditActions() {
        return Result.ok(auditService.actions());
    }

    private static OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException(400, "时间格式必须为 ISO-8601（带时区偏移）");
        }
    }

    @Data
    static class UpdateConfigRequest {
        private String value;
    }

    @Data
    static class StatusRequest {
        private int status;
    }

    @Data
    static class AdminRequest {
        private Boolean isAdmin;
    }

    @Data
    static class UpdateEmailRequest {
        private String email;
    }

    @Data
    static class SetPlanRequest {
        private String planCode;
        private Integer months;
        private OffsetDateTime expiresAt;
    }

    @Data
    static class GrantRequest {
        private Long userId;
        private String key;
        private Long value;
        private OffsetDateTime expiresAt;
        private String note;
    }

    @Data
    static class RedeemCodesRequest {
        private String planCode;
        private int durationDays;
        private int count;
        private OffsetDateTime expiresAt;
    }
}
