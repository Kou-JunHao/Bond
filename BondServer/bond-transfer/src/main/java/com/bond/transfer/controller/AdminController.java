package com.bond.transfer.controller;

import com.bond.common.dto.*;
import com.bond.common.entity.SystemConfig;
import com.bond.transfer.service.AdminService;
import com.bond.transfer.service.ConfigService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final ConfigService configService;

    // ── Dashboard ──

    @GetMapping("/dashboard")
    public Result<DashboardDTO> dashboard() {
        return Result.ok(adminService.getDashboard());
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

    @PutMapping("/config/{key}")
    public Result<Void> updateConfig(@PathVariable String key,
                                     @RequestHeader("X-User-Id") Long userId,
                                     @RequestBody UpdateConfigRequest req) {
        configService.update(key, req.getValue(), userId);
        return Result.ok();
    }

    @PutMapping("/config")
    public Result<Void> batchUpdateConfig(@RequestHeader("X-User-Id") Long userId,
                                          @RequestBody Map<String, String> updates) {
        configService.batchUpdate(updates, userId);
        return Result.ok();
    }

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

    @PutMapping("/users/{id}/status")
    public Result<Void> setUserStatus(@PathVariable Long id, @RequestBody StatusRequest req) {
        adminService.setUserStatus(id, req.getStatus());
        return Result.ok();
    }

    @PutMapping("/users/{id}/admin")
    public Result<Void> setAdmin(@PathVariable Long id, @RequestBody AdminRequest req) {
        adminService.setAdmin(id, req.getIsAdmin());
        return Result.ok();
    }

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

    // ── Transfers ──

    @GetMapping("/transfers")
    public Result<List<TransferTaskDTO>> listTransfers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer status) {
        return Result.ok(adminService.listTransfers(page, size, status));
    }

    @DeleteMapping("/transfers/{id}")
    public Result<Void> deleteTransfer(@PathVariable Long id) {
        adminService.deleteTransfer(id);
        return Result.ok();
    }

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

    @DeleteMapping("/workspaces/{id}")
    public Result<Void> deleteWorkspace(@PathVariable Long id) {
        adminService.deleteWorkspace(id);
        return Result.ok();
    }

    @GetMapping("/workspaces/{id}")
    public Result<Map<String, Object>> getWorkspaceDetail(@PathVariable Long id) {
        return Result.ok(adminService.getWorkspaceDetail(id));
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
}
