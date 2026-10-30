package com.bond.auth.controller;

import com.bond.common.dto.Result;
import com.bond.common.dto.UserDTO;
import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.common.security.ClientIpResolver;
import com.bond.auth.avatar.AvatarService;
import com.bond.auth.service.AdminLoginGuardService;
import com.bond.auth.service.AuthService;
import com.bond.auth.service.CaptchaService;
import com.bond.common.utils.JwtUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private static final long INTERNAL_WARN_INTERVAL_MS = 60_000L;

    private final AuthService authService;
    private final CaptchaService captchaService;
    private final ClientIpResolver clientIpResolver;
    private final AdminLoginGuardService adminLoginGuard;
    private final AvatarService avatarService;
    private final AtomicLong lastInternalIpWarn = new AtomicLong(0L);

    @PostMapping("/register")
    public Result<UserDTO> register(@RequestBody RegisterRequest req) {
        return Result.ok(authService.register(req.getUsername(), req.getPassword(), req.getNickname(),
                req.getEmail(), req.getCode()));
    }

    @PostMapping("/login")
    public Result<UserDTO> login(@RequestBody LoginRequest req,
                                 @RequestHeader(value = AdminLoginGuardService.CLIENT_APP_HEADER,
                                         required = false) String clientApp,
                                 @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                 jakarta.servlet.http.HttpServletRequest httpRequest) {
        String ip = clientIp(httpRequest);
        String username = req.getUsername();

        // 管理端登录审计 + fail2ban（task-144）：只有被判定为"管理后台登录"的尝试才记录/封禁。
        // 判定依据：X-Client-App: bond-admin（优先）或目标账号 is_admin=true（兜底）。
        // 普通用户登录不做任何额外查询与写入。
        boolean adminClient = adminLoginGuard.isAdminClient(clientApp);
        User auditUser = adminClient ? null : adminLoginGuard.findUser(username);
        boolean adminAttempt = adminLoginGuard.isAdminAttempt(adminClient, auditUser);
        Long auditUserId = auditUser == null ? null : auditUser.getId();
        if (adminAttempt) {
            adminLoginGuard.enforceBlockIfActive(ip, username);
        }

        if (captchaService.needsCaptcha(ip, username)) {
            if (req.getCaptchaId() == null || req.getCaptchaCode() == null) {
                captchaService.recordFail(ip, username);
                if (adminAttempt) {
                    adminLoginGuard.recordCaptchaRequired(ip, username, auditUserId, userAgent, "需要验证码");
                }
                throw new BusinessException(400, "需要验证码");
            }
            if (!captchaService.verify(req.getCaptchaId(), req.getCaptchaCode())) {
                captchaService.recordFail(ip, username);
                if (adminAttempt) {
                    adminLoginGuard.recordCaptchaRequired(ip, username, auditUserId, userAgent, "验证码错误");
                }
                throw new BusinessException(400, "验证码错误");
            }
        }

        try {
            UserDTO result = authService.login(username, req.getPassword());
            captchaService.clearFails(ip, username);
            if (adminAttempt) {
                adminLoginGuard.recordSuccess(ip, username, result.getId(), userAgent);
            }
            return Result.ok(result);
        } catch (RuntimeException e) {
            captchaService.recordFail(ip, username);
            if (adminAttempt) {
                String outcome = "error";
                String reason = e.getClass().getSimpleName();
                if (e instanceof BusinessException be) {
                    if (be.getCode() == 401) {
                        outcome = (auditUser != null && auditUser.getStatus() != null && auditUser.getStatus() == 0)
                                ? "user_disabled" : "bad_credentials";
                    }
                    reason = be.getMessage();
                }
                adminLoginGuard.recordFailure(outcome, ip, username, auditUserId, userAgent, reason);
            }
            throw e;
        }
    }

    @GetMapping("/captcha")
    public Result<Map<String, String>> captcha() {
        CaptchaService.CaptchaResult cr = captchaService.generate();
        return Result.ok(Map.of("id", cr.id, "image", cr.image));
    }

    @GetMapping("/captcha/check")
    public Result<Map<String, Boolean>> checkCaptcha(jakarta.servlet.http.HttpServletRequest httpRequest,
                                                     @RequestParam(required = false) String username) {
        String ip = clientIp(httpRequest);
        return Result.ok(Map.of("needsCaptcha", captchaService.needsCaptcha(ip, username)));
    }

    private String clientIp(jakarta.servlet.http.HttpServletRequest httpRequest) {
        String ip = clientIpResolver.resolve(httpRequest.getRemoteAddr(),
                httpRequest.getHeader(ClientIpResolver.forwardedForHeader()),
                httpRequest.getHeader(ClientIpResolver.realIpHeader()));
        // task-146：外部请求却解析成内部地址（网关/nginx 自身）说明 IP 透传链路退化，
        // 审计与 fail2ban 都会失真；每 60s 最多 WARN 一条便于发现，不刷屏。
        if (ClientIpResolver.isInternalIp(ip)) {
            long now = System.currentTimeMillis();
            long last = lastInternalIpWarn.get();
            if (now - last >= INTERNAL_WARN_INTERVAL_MS && lastInternalIpWarn.compareAndSet(last, now)) {
                log.warn("auth client ip degraded: resolved internal ip={} remoteAddr={} hasXRealIp={} hasXff={} uri={}",
                        ip, httpRequest.getRemoteAddr(),
                        httpRequest.getHeader(ClientIpResolver.realIpHeader()) != null,
                        httpRequest.getHeader(ClientIpResolver.forwardedForHeader()) != null,
                        httpRequest.getRequestURI());
            }
        }
        return ip;
    }

    @PostMapping("/refresh")
    public Result<UserDTO> refresh(@RequestBody RefreshRequest req) {
        return Result.ok(authService.refreshToken(req.getRefreshToken()));
    }

    @GetMapping("/me")
    public Result<UserDTO> me(@RequestHeader("Authorization") String auth) {
        String token = auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        return Result.ok(authService.getCurrentUser(token));
    }

    @PutMapping("/profile")
    public Result<UserDTO> updateProfile(@RequestHeader("Authorization") String auth,
                                         @RequestBody UpdateProfileRequest req) {
        String token = auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        return Result.ok(authService.updateProfile(token, req.getNickname(), req.getAvatarUrl()));
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@RequestHeader("Authorization") String auth,
                                       @RequestBody ChangePasswordRequest req) {
        String token = auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        authService.changePassword(token, req.getOldPassword(), req.getNewPassword());
        return Result.ok();
    }

    // ── 头像（task-152） ──

    /** 上传头像：multipart `file`，≤2MB，PNG/JPEG/WebP；服务端居中裁 512×512 JPEG 后写 RustFS。 */
    @PostMapping("/avatar")
    public Result<UserDTO> uploadAvatar(@RequestHeader("Authorization") String auth,
                                        @RequestParam("file") MultipartFile file) {
        String token = bearerToken(auth);
        avatarService.upload(JwtUtils.getUserId(token), file);
        return Result.ok(authService.getCurrentUser(token));
    }

    /** 删除头像（幂等）：清 avatar_url 并异步删对象，始终 204。 */
    @DeleteMapping("/avatar")
    public ResponseEntity<Void> deleteAvatar(@RequestHeader("Authorization") String auth) {
        String token = bearerToken(auth);
        avatarService.delete(JwtUtils.getUserId(token));
        return ResponseEntity.noContent().build();
    }

    private static String bearerToken(String auth) {
        if (auth == null || auth.isBlank()) {
            throw new BusinessException(401, "未登录");
        }
        return auth.startsWith("Bearer ") ? auth.substring(7) : auth;
    }

    // ── 邮箱验证码 / 忘记密码（task-57） ──

    /** 发送验证码：purpose=register（注册前）或 reset_password（忘记密码）。 */
    @PostMapping("/email/code")
    public Result<Map<String, Long>> sendEmailCode(@RequestBody EmailCodeRequest req,
                                                   jakarta.servlet.http.HttpServletRequest httpRequest) {
        long cooldown = authService.requestEmailCode(req.getEmail(), req.getPurpose(), httpRequest.getRemoteAddr());
        return Result.ok(Map.of("cooldownSeconds", cooldown));
    }

    /** 忘记密码：邮箱 + 验证码重置；成功后所有旧 refresh token 失效。 */
    @PostMapping("/password/reset")
    public Result<Void> resetPassword(@RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req.getEmail(), req.getCode(), req.getNewPassword());
        return Result.ok();
    }

    /** 改邮箱第一步：当前密码二次认证，并给新邮箱发验证码（登录态）。 */
    @PostMapping("/email/change/request")
    public Result<Void> requestChangeEmail(@RequestHeader("Authorization") String auth,
                                           @RequestBody ChangeEmailRequest req,
                                           jakarta.servlet.http.HttpServletRequest httpRequest) {
        String token = auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        authService.requestChangeEmail(token, req.getPassword(), req.getNewEmail(), httpRequest.getRemoteAddr());
        return Result.ok();
    }

    /** 改邮箱第二步：验证码校验通过后写入新邮箱（登录态）。 */
    @PostMapping("/email/change/confirm")
    public Result<UserDTO> confirmChangeEmail(@RequestHeader("Authorization") String auth,
                                              @RequestBody ConfirmEmailRequest req) {
        String token = auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        return Result.ok(authService.confirmChangeEmail(token, req.getCode()));
    }

    @Data
    static class RegisterRequest {
        private String username;
        private String password;
        private String nickname;
        /** task-57：邮箱 + 验证码（邮件通道启用时必填）。 */
        private String email;
        private String code;
    }

    @Data
    static class LoginRequest {
        private String username;
        private String password;
        private String captchaId;
        private String captchaCode;
    }

    @Data
    static class RefreshRequest {
        private String refreshToken;
    }

    @Data
    static class UpdateProfileRequest {
        private String nickname;
        private String avatarUrl;
    }

    @Data
    static class ChangePasswordRequest {
        private String oldPassword;
        private String newPassword;
    }

    @Data
    static class EmailCodeRequest {
        private String email;
        /** register / reset_password */
        private String purpose;
    }

    @Data
    static class ResetPasswordRequest {
        private String email;
        private String code;
        private String newPassword;
    }

    @Data
    static class ChangeEmailRequest {
        private String password;
        private String newEmail;
    }

    @Data
    static class ConfirmEmailRequest {
        private String code;
    }
}
