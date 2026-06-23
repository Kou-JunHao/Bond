package com.bond.auth.controller;

import com.bond.common.dto.Result;
import com.bond.common.dto.UserDTO;
import com.bond.auth.service.AuthService;
import com.bond.auth.service.CaptchaService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;

    @PostMapping("/register")
    public Result<UserDTO> register(@RequestBody RegisterRequest req) {
        return Result.ok(authService.register(req.getUsername(), req.getPassword(), req.getNickname()));
    }

    @PostMapping("/login")
    public Result<UserDTO> login(@RequestBody LoginRequest req,
                                 jakarta.servlet.http.HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();

        if (captchaService.needsCaptcha(ip)) {
            if (req.getCaptchaId() == null || req.getCaptchaCode() == null) {
                captchaService.recordFail(ip);
                throw new com.bond.common.exception.BusinessException(400, "需要验证码");
            }
            if (!captchaService.verify(req.getCaptchaId(), req.getCaptchaCode())) {
                captchaService.recordFail(ip);
                throw new com.bond.common.exception.BusinessException(400, "验证码错误");
            }
        }

        try {
            UserDTO result = authService.login(req.getUsername(), req.getPassword());
            captchaService.clearFails(ip);
            return Result.ok(result);
        } catch (Exception e) {
            captchaService.recordFail(ip);
            throw e;
        }
    }

    @GetMapping("/captcha")
    public Result<Map<String, String>> captcha() {
        CaptchaService.CaptchaResult cr = captchaService.generate();
        return Result.ok(Map.of("id", cr.id, "image", cr.image));
    }

    @GetMapping("/captcha/check")
    public Result<Map<String, Boolean>> checkCaptcha(jakarta.servlet.http.HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        return Result.ok(Map.of("needsCaptcha", captchaService.needsCaptcha(ip)));
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

    @Data
    static class RegisterRequest {
        private String username;
        private String password;
        private String nickname;
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
}
