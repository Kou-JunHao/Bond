package com.bond.transfer.controller;

import com.bond.common.dto.Result;
import com.bond.common.dto.WebDavPasswordCreatedDTO;
import com.bond.common.dto.WebDavPasswordDTO;
import com.bond.transfer.webdav.WebDavPasswordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * WebDAV 应用专用密码管理（task-48）。
 *
 * <p>走网关 {@code /api/**}（JWT，{@code X-User-Id} 由网关注入）；与 {@code /dav} 的
 * Basic 认证分离：这里管理凭据，{@code /dav} 使用凭据。列表永不回传哈希。</p>
 */
@RestController
@RequestMapping("/api/webdav/passwords")
@RequiredArgsConstructor
public class WebDavPasswordController {

    private final WebDavPasswordService passwordService;

    @GetMapping
    public Result<List<WebDavPasswordDTO>> list(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(passwordService.list(userId));
    }

    @PostMapping
    public Result<WebDavPasswordCreatedDTO> create(@RequestHeader("X-User-Id") Long userId,
                                                   @RequestBody(required = false) CreateWebDavPasswordRequest request) {
        String name = request == null ? null : request.name();
        return Result.ok(passwordService.create(userId, name));
    }

    @DeleteMapping("/{id}")
    public Result<Void> revoke(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        passwordService.revoke(userId, id);
        return Result.ok();
    }

    /** 请求体：{@code {"name":"Windows 笔记本"}}。 */
    public record CreateWebDavPasswordRequest(String name) {
    }
}
