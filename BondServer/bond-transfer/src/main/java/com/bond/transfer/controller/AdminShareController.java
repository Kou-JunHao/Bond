package com.bond.transfer.controller;

import com.bond.common.dto.AdminShareDTO;
import com.bond.common.dto.PageResult;
import com.bond.common.dto.Result;
import com.bond.transfer.share.AdminShareService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端分享链接治理（task-89）：列表 + 强制撤销。
 *
 * <p>管理员守卫由 {@code AdminInterceptor}（/api/admin/**）统一负责，
 * 身份来自网关 HMAC 签名后的 X-User-Id；本控制器不做二次鉴权。</p>
 */
@RestController
@RequestMapping("/api/admin/shares")
@RequiredArgsConstructor
public class AdminShareController {

    private final AdminShareService adminShareService;

    @GetMapping
    public Result<PageResult<AdminShareDTO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String ownerKeyword,
            @RequestParam(required = false) String tokenPrefix) {
        return Result.ok(adminShareService.list(page, size, status, ownerKeyword, tokenPrefix));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Long id,
                                       @RequestHeader("X-User-Id") Long actorId,
                                       HttpServletRequest request) {
        adminShareService.revoke(id, actorId, clientIp(request));
        return ResponseEntity.noContent().build();
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
