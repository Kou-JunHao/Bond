package com.bond.auth.controller;

import com.bond.auth.avatar.AvatarService;
import com.bond.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Optional;

/**
 * 头像访问端点（task-152）。
 *
 * <p>匿名可读：头像本身会出现在聊天列表、好友列表、工作区成员等公开可见的位置；用 URL 里的
 * {@code v}（= avatar_updated_at 的 epoch 秒）做版本校验，避免猜路径和缓存串号。网关
 * {@code PublicEndpoints} 对该 GET 路径放行，由 bond-auth 的 {@code @MapperScan} 直接查库。</p>
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserAvatarController {

    private final AvatarService avatarService;

    @GetMapping("/{userId}/avatar")
    public ResponseEntity<byte[]> avatar(@PathVariable Long userId,
                                         @RequestParam(value = "v", required = false) Long version) {
        Optional<byte[]> bytes = avatarService.load(userId, version);
        if (bytes.isEmpty()) {
            throw new BusinessException(404, "头像不存在");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic().immutable())
                .body(bytes.get());
    }
}
