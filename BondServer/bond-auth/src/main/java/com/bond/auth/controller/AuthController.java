package com.bond.auth.controller;

import com.bond.common.dto.Result;
import com.bond.common.dto.UserDTO;
import com.bond.auth.service.AuthService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<UserDTO> register(@RequestBody RegisterRequest req) {
        return Result.ok(authService.register(req.getUsername(), req.getPassword(), req.getNickname()));
    }

    @PostMapping("/login")
    public Result<UserDTO> login(@RequestBody LoginRequest req) {
        return Result.ok(authService.login(req.getUsername(), req.getPassword()));
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
