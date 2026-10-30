package com.bond.transfer.controller;

import com.bond.transfer.service.WorkspaceInvitationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 工作区邀请落地页（task-159）：GET /i/{token} 返回 HTML，供浏览器直接打开。
 *
 * <p>与分享落地页 {@code /s/{token}} 同一套模式：匿名可读（{@code PublicEndpoints} 精确正则放行）、
 * 页面内容全部 HTML 转义、{@code Cache-Control: no-store} 避免链接状态被缓存。
 * JSON 端点 {@code GET /api/invite/{token}} 保持不变（客户端在用）。</p>
 */
@RestController
@RequiredArgsConstructor
public class InviteLandingController {

    private static final MediaType TEXT_HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final WorkspaceInvitationService invitationService;

    @GetMapping(value = "/i/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> landing(@PathVariable String token) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Robots-Tag", "noindex,nofollow")
                .contentType(TEXT_HTML_UTF8)
                .body(invitationService.landingHtml(token));
    }
}
