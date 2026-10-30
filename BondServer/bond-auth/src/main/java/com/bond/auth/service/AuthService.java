package com.bond.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bond.auth.avatar.AvatarService;
import com.bond.auth.mapper.UserMapper;
import com.bond.common.dto.UserDTO;
import com.bond.common.email.EmailAddresses;
import com.bond.common.email.EmailGateway;
import com.bond.common.email.EmailPurpose;
import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.ConflictException;
import com.bond.common.utils.JwtUtils;
import com.bond.common.utils.SnowflakeId;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MIN_PASSWORD_LENGTH = 6;

    private final UserMapper userMapper;
    private final ConfigService configService;
    private final PersonalSpaceInitializer personalSpaceInitializer;
    private final VerificationCodeService verificationCodeService;
    private final EmailGateway emailGateway;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 兼容旧调用（无邮箱，仅当邮件通道关闭时允许，例如本地/测试环境）。 */
    @Transactional
    public UserDTO register(String username, String password, String nickname) {
        return register(username, password, nickname, null, null);
    }

    @Transactional
    public UserDTO register(String username, String password, String nickname, String email, String code) {
        if (!configService.getBoolean("user.allow_register", true)) {
            throw new BusinessException(403, "暂不允许注册");
        }
        // 注册以邮箱为登录凭证：邮件通道启用时必须带邮箱 + 验证码；关闭时（本地/测试）允许无邮箱
        String normalizedEmail = null;
        if (email != null && !email.isBlank()) {
            normalizedEmail = VerificationCodeService.normalizeEmail(email);
        } else if (emailGateway.isEnabled()) {
            throw new BusinessException(400, "邮箱为必填项");
        }

        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (existing != null) {
            throw new BusinessException(400, "用户名已存在");
        }
        if (normalizedEmail != null && findByEmail(normalizedEmail) != null) {
            throw new ConflictException("该邮箱已被注册", Map.of("field", "email"));
        }
        // 先做可用性检查再消费验证码：注册名冲突/邮箱冲突时不浪费用户刚收到的码
        if (normalizedEmail != null && emailGateway.isEnabled()) {
            verificationCodeService.verifyAndConsume(
                    normalizedEmail, VerificationCodeService.PURPOSE_REGISTER, code);
        }

        User user = new User();
        user.setId(SnowflakeId.nextId());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname != null ? nickname : username);
        user.setEmail(normalizedEmail);
        user.setStatus(1);
        userMapper.insert(user);

        // 注册后立即创建个人空间（与 team workspace 同一张表，type='personal'），
        // 同一事务内完成，避免“有用户没个人空间”的中间态。
        personalSpaceInitializer.createForUser(user.getId());

        // task-76：注册欢迎邮件（best-effort；发信失败只记 warn，不影响注册结果）
        sendWelcomeSafely(user);

        return buildDTO(user);
    }

    private void sendWelcomeSafely(User user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        try {
            String name = user.getNickname() == null ? user.getUsername() : user.getNickname();
            // task-79：welcome 模板（V16 seed）声明了 email，必须显式提供，否则 {{email}} 渲染为空
            emailGateway.sendByPurpose(user.getEmail(), EmailPurpose.WELCOME,
                    Map.of("username", name, "email", user.getEmail()));
        } catch (RuntimeException e) {
            // 欢迎邮件属增值通知：失败不回滚注册，也不向客户端暴露 provider 细节
            log.warn("welcome email failed for userId={}, provider-issue={}",
                    user.getId(), e.getClass().getSimpleName());
        }
    }

    public UserDTO login(String username, String password) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        return buildDTO(user);
    }

    public UserDTO refreshToken(String refreshToken) {
        Claims claims;
        try {
            claims = JwtUtils.parseToken(refreshToken);
            if (!"refresh".equals(claims.get("type"))) {
                throw new BusinessException(401, "Refresh Token无效");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(401, "Refresh Token已过期");
        }
        Long userId = Long.parseLong(claims.getSubject());
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        // 改密/管理员重置后，早于 tokens_valid_after 签发的 refresh token 全部失效（无需额外表）
        OffsetDateTime validAfter = user.getTokensValidAfter();
        Instant issuedAt = claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant();
        if (validAfter != null && (issuedAt == null || issuedAt.isBefore(validAfter.toInstant()))) {
            throw new BusinessException(401, "登录状态已失效，请重新登录");
        }
        return buildDTO(user);
    }

    public UserDTO getCurrentUser(String token) {
        Long userId = JwtUtils.getUserId(token);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return buildDTO(user);
    }

    public UserDTO updateProfile(String token, String nickname, String avatarUrl) {
        Long userId = JwtUtils.getUserId(token);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        // 头像只接受「清空」或本人自托管路径（task-152 加固：拒绝外部 http(s) 追踪像素）
        String safeAvatarUrl = AvatarService.normalizeSelfHostedAvatarUrl(userId, avatarUrl);
        // updateById 会跳过 null 字段，清空头像必须走显式 set
        UpdateWrapper<User> update = new UpdateWrapper<User>().eq("id", userId);
        boolean changed = false;
        if (nickname != null) {
            update.set("nickname", nickname);
            changed = true;
        }
        if (avatarUrl != null) {
            update.set("avatar_url", safeAvatarUrl);
            changed = true;
        }
        if (changed) {
            userMapper.update(null, update);
        }
        return getCurrentUser(token);
    }

    public void changePassword(String token, String oldPassword, String newPassword) {
        Long userId = JwtUtils.getUserId(token);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException(400, "原密码错误");
        }
        validateNewPassword(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    // ── 邮箱验证码流程（task-57） ──

    /**
     * 请求邮箱验证码。注册/找回可匿名调用；改邮箱需登录后走专用接口。
     *
     * <p>找回密码对「邮箱是否存在」保持防枚举：无论是否存在都返回同样的冷却时间，
     * 只有存在且通道可用时才真正发信。</p>
     */
    public long requestEmailCode(String email, String purpose, String ip) {
        String normalized = VerificationCodeService.normalizeEmail(email);
        if (VerificationCodeService.PURPOSE_CHANGE_EMAIL.equals(purpose)) {
            // 改邮箱必须先验证当前密码，走 /email/change/request（登录态接口）
            throw new BusinessException(400, "改绑邮箱请先登录后使用专用接口");
        }
        if (VerificationCodeService.PURPOSE_REGISTER.equals(purpose)) {
            if (findByEmail(normalized) != null) {
                throw new ConflictException("该邮箱已被注册", Map.of("field", "email"));
            }
            return verificationCodeService.send(normalized, purpose, null, ip);
        }
        if (VerificationCodeService.PURPOSE_RESET_PASSWORD.equals(purpose)) {
            User user = findByEmail(normalized);
            if (user == null || !emailGateway.isEnabled()) {
                // 防枚举：不暴露邮箱是否注册，也不真正发送
                return verificationCodeService.cooldownSeconds();
            }
            return verificationCodeService.send(normalized, purpose, user.getId(), ip);
        }
        throw new BusinessException(400, "不支持的验证码用途");
    }

    /** 忘记密码：邮箱 + 验证码重置；成功后旧 refresh token 全部失效。 */
    @Transactional
    public void resetPassword(String email, String code, String newPassword) {
        validateNewPassword(newPassword);
        String normalized = VerificationCodeService.normalizeEmail(email);
        User user = findByEmail(normalized);
        if (user == null) {
            // 与「验证码无效」同一文案，避免暴露邮箱是否注册
            throw new BusinessException(400, "验证码无效或已过期");
        }
        verificationCodeService.verifyAndConsume(
                normalized, VerificationCodeService.PURPOSE_RESET_PASSWORD, code);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokensValidAfter(OffsetDateTime.now());
        userMapper.updateById(user);
    }

    /** 已登录用户改邮箱第一步：二次认证（当前密码）+ 给新邮箱发验证码。 */
    public void requestChangeEmail(String token, String password, String newEmail, String ip) {
        Long userId = JwtUtils.getUserId(token);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (!passwordEncoder.matches(password == null ? "" : password, user.getPasswordHash())) {
            throw new BusinessException(400, "密码错误");
        }
        String normalized = VerificationCodeService.normalizeEmail(newEmail);
        if (normalized.equalsIgnoreCase(user.getEmail() == null ? "" : user.getEmail())) {
            throw new BusinessException(400, "新邮箱与当前邮箱相同");
        }
        if (findByEmail(normalized) != null) {
            throw new ConflictException("该邮箱已被其他账号绑定", Map.of("field", "email"));
        }
        verificationCodeService.send(
                normalized, VerificationCodeService.PURPOSE_CHANGE_EMAIL, userId, ip);
    }

    /** 改邮箱第二步：验证码校验通过后写入新邮箱；旧 refresh token 不失效（邮箱非登录凭证）。 */
    @Transactional
    public UserDTO confirmChangeEmail(String token, String code) {
        Long userId = JwtUtils.getUserId(token);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        String email = verificationCodeService.verifyChangeEmail(userId, code);
        // 二次确认前再查一次唯一性：从 requestChangeEmail 到 confirm 之间，
        // 该邮箱可能已被注册或被管理员改给他人；不预校验会撞 DB 唯一索引变成 500（task-66）。
        User other = findByEmail(EmailAddresses.normalize(email));
        if (other != null && !other.getId().equals(userId)) {
            throw new ConflictException("该邮箱已被其他账号使用", Map.of("field", "email"));
        }
        user.setEmail(email);
        userMapper.updateById(user);
        return buildDTO(user);
    }

    // ── 内部工具 ──

    private User findByEmail(String email) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .apply("lower(email) = {0}", email.toLowerCase(Locale.ROOT)));
    }

    private static void validateNewPassword(String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(400, "新密码长度不能少于6位");
        }
    }

    private UserDTO buildDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setNickname(user.getNickname());
        dto.setAvatarUrl(user.getAvatarUrl());
        dto.setEmail(user.getEmail());
        dto.setEmailVerified(user.getEmail() != null);
        dto.setAccessToken(JwtUtils.generateAccessToken(user.getId(), user.getUsername()));
        dto.setRefreshToken(JwtUtils.generateRefreshToken(user.getId()));
        return dto;
    }
}
