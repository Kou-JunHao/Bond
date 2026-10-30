package com.bond.auth.avatar;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bond.auth.mapper.UserMapper;
import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 用户头像（task-152）：上传 / 删除 / 匿名按版本读取。
 *
 * <p>{@code avatar_url} 是**本服务自托管路径** {@code /api/users/{id}/avatar?v={epochSecond}}，v 取
 * {@code users.avatar_updated_at} 的 epoch 秒；访问端点校验 v 与库里值一致才返回图片，用于防猜路径与
 * 防 CDN/浏览器缓存串号（换头像后 v 变化，旧链接自然 404，而不是返回旧图）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

    static final String AVATAR_PATH_PREFIX = "/api/users/";

    private final UserMapper userMapper;
    private final AvatarStorage storage;

    /**
     * 上传并覆盖头像。校验 → 裁 512×512 JPEG → 写对象 → 写库（自托管 URL + avatar_updated_at）。
     */
    public void upload(Long userId, MultipartFile file) {
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的图片");
        }
        if (file.getSize() > AvatarImageProcessor.MAX_BYTES) {
            throw new BusinessException(400, "头像文件不能超过 2MB");
        }
        AvatarImageProcessor.assertSupported(file.getContentType(), file.getOriginalFilename());

        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(400, "读取上传文件失败");
        }
        byte[] jpeg = AvatarImageProcessor.toSquareJpeg(raw);

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        storage.put(userId, jpeg);

        OffsetDateTime now = OffsetDateTime.now();
        String url = selfHostedUrl(userId, now.toEpochSecond());
        // updateById 会跳过 null 字段，这里必须用显式 set，保证 URL 与版本时间一起更新。
        userMapper.update(null, new UpdateWrapper<User>()
                .eq("id", userId)
                .set("avatar_url", url)
                .set("avatar_updated_at", now));
    }

    /** 删除头像：清库 + 异步删对象；对象不存在也算成功（幂等）。 */
    public void delete(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        userMapper.update(null, new UpdateWrapper<User>()
                .eq("id", userId)
                .set("avatar_url", null)
                .set("avatar_updated_at", null));
        CompletableFuture.runAsync(() -> storage.delete(userId))
                .exceptionally(e -> {
                    log.warn("avatar async delete failed: userId={} ({})", userId, e.getMessage());
                    return null;
                });
    }

    /**
     * 匿名读取头像。v 与库中 avatar_updated_at 的 epoch 秒不一致 → empty（404），防止旧 URL 拿到新图/旧图。
     */
    public Optional<byte[]> load(Long userId, Long version) {
        if (userId == null || version == null) {
            return Optional.empty();
        }
        User user = userMapper.selectById(userId);
        if (user == null || user.getAvatarUrl() == null || user.getAvatarUrl().isBlank()
                || user.getAvatarUpdatedAt() == null) {
            return Optional.empty();
        }
        long expected = user.getAvatarUpdatedAt().toEpochSecond();
        if (version != expected) {
            return Optional.empty();
        }
        return storage.get(userId);
    }

    /** 生成自托管头像路径；时间戳即版本号。 */
    public static String selfHostedUrl(long userId, long epochSecond) {
        return AVATAR_PATH_PREFIX + userId + "/avatar?v=" + epochSecond;
    }

    /**
     * task-152 加固：{@code PUT /api/auth/profile} 的 avatarUrl 只接受「清空」或「本人自托管路径」。
     * 外部 http(s)、data:、javascript:、他人路径一律拒绝，避免客户端加载第三方追踪像素/SSRF 面。
     */
    public static String normalizeSelfHostedAvatarUrl(Long userId, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        String expectedPrefix = AVATAR_PATH_PREFIX + userId + "/avatar";
        if (!value.startsWith(expectedPrefix)) {
            throw new BusinessException(400, "avatarUrl 仅支持本服务自托管路径（/api/users/{userId}/avatar?v=...）");
        }
        // 允许带查询串（v=），但不允许出现路径穿越或二次跳转。
        String tail = value.substring(expectedPrefix.length());
        if (!(tail.isEmpty() || tail.startsWith("?")) || tail.contains("..") || tail.contains(":") || tail.contains("/")) {
            throw new BusinessException(400, "avatarUrl 仅支持本服务自托管路径（/api/users/{userId}/avatar?v=...）");
        }
        return value;
    }
}
