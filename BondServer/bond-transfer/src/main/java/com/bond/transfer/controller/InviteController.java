package com.bond.transfer.controller;

import com.bond.common.dto.InvitationDTO;
import com.bond.common.dto.Result;
import com.bond.transfer.service.WorkspaceInvitationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/invite")
@RequiredArgsConstructor
public class InviteController {

    private static final MediaType TEXT_HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final WorkspaceInvitationService invitationService;

    /**
     * 校验邀请链接。默认返回 JSON（客户端在用）；浏览器直接访问时（{@code Accept: text/html}）
     * 返回同一份落地页 HTML（task-159），避免用户看到裸 JSON。
     */
    @GetMapping("/{token}")
    public Object verifyLink(@PathVariable String token,
                             @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        if (accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains("text/html")) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("X-Robots-Tag", "noindex,nofollow")
                    .contentType(TEXT_HTML_UTF8)
                    .body(invitationService.landingHtml(token));
        }
        return Result.ok(invitationService.verifyInviteLink(token));
    }

    @PostMapping("/{token}/accept")
    public Result<Void> acceptLink(@RequestHeader("X-User-Id") Long userId,
                                   @PathVariable String token) {
        invitationService.acceptInviteLink(token, userId);
        return Result.ok();
    }
}
