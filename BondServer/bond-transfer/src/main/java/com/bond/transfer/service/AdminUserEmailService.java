package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.dto.AdminUserDTO;
import com.bond.common.email.EmailAddresses;
import com.bond.common.email.EmailGateway;
import com.bond.common.email.EmailPurpose;
import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.ConflictException;
import com.bond.transfer.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 管理台用户邮箱相关操作（task-57）：
 * <ul>
 *   <li>重置密码：生成一次性临时密码（不回显给管理员），BCrypt 落库，作废旧 refresh token，邮件发送给用户；
 *       只允许「管理员定义临时密码」口径，不存明文。</li>
 *   <li>编辑邮箱：管理员直接改（不要求原邮箱验证），需唯一性校验；新邮箱视为“管理员确认可用”，
 *       但仍保留 email_verified 语义（当前用 email 非空表示已绑定）。</li>
 * </ul>
 * 注意：本类依赖 bond-common 的 {@link EmailGateway}，发信失败只记日志不阻断（见网关实现）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdminUserEmailService {

    /** 临时密码用无歧义字符集（去掉 0/O/1/l/I），方便用户手输。 */
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();
    private static final int TEMP_PASSWORD_LENGTH = 16;

    private final UserMapper userMapper;
    private final EmailGateway emailGateway;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 管理员重置用户密码：生成临时密码并邮件发送；返回值只含投递结果与收件人掩码，不含明文密码。
     *
     * <p>顺序：先改库再发信；发信失败（503）会因 {@code @Transactional} 回滚密码变更，
     * 避免出现「密码已改、用户却没收到临时密码」的死局。</p>
     *
     * @throws BusinessException 404 用户不存在；400 用户未绑定邮箱（无法发信）
     */
    @Transactional
    public Map<String, Object> resetPassword(Long userId) {
        User user = requireUser(userId);
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new BusinessException(400, "该用户未绑定邮箱，无法发送临时密码邮件");
        }
        String tempPassword = generateTempPassword();
        user.setPasswordHash(passwordEncoder.encode(tempPassword));
        // 作废此前签发的 refresh token：改密后必须重新登录（access token 仍可用至 24h，属已知取舍）
        user.setTokensValidAfter(OffsetDateTime.now());
        userMapper.updateById(user);

        // task-76：后台重置密码走「管理员重置」目的模板（可在后台改文案/样式；未绑定回退内置）。
        java.util.Map<String, String> variables = new java.util.LinkedHashMap<>();
        variables.put("code", tempPassword);
        variables.put("username", safeName(user));
        emailGateway.sendByPurpose(user.getEmail(), EmailPurpose.ADMIN_RESET_PASSWORD, variables);
        log.info("admin reset password for userId={}, mail queued to {}", userId, maskEmail(user.getEmail()));
        return Map.of("delivered", true, "emailMasked", maskEmail(user.getEmail()));
    }

    /**
     * 管理员编辑用户邮箱（不要求用户原邮箱验证）。
     *
     * @throws BusinessException 404 用户不存在；400 邮箱格式错误
     * @throws ConflictException 409 邮箱已被其他账号占用
     */
    @Transactional
    public AdminUserDTO updateEmail(Long userId, String email) {
        User user = requireUser(userId);
        String normalized = EmailAddresses.normalize(email);
        User other = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .apply("lower(email) = {0}", normalized));
        if (other != null && !other.getId().equals(userId)) {
            throw new ConflictException("该邮箱已被其他账号绑定", Map.of("field", "email"));
        }
        user.setEmail(normalized);
        userMapper.updateById(user);
        notifyEmailChanged(user);
        log.info("admin updated email for userId={}", userId);
        return toDto(user);
    }

    /** task-76：管理员修改邮箱后通知用户（best-effort，失败不影响操作）。 */
    private void notifyEmailChanged(User user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        try {
            java.util.Map<String, String> variables = new java.util.LinkedHashMap<>();
            variables.put("username", safeName(user));
            variables.put("email", maskEmail(user.getEmail()));
            variables.put("action", "邮箱变更");
            variables.put("reason", "你的账号邮箱已由管理员更新");
            emailGateway.sendByPurpose(user.getEmail(), EmailPurpose.ADMIN_OPERATION_NOTICE, variables);
        } catch (RuntimeException e) {
            log.warn("email changed notice failed for userId={}, provider-issue={}",
                    user.getId(), e.getClass().getSimpleName());
        }
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return user;
    }

    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(ALPHABET[secureRandom.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static String safeName(User user) {
        return user.getNickname() != null && !user.getNickname().isBlank()
                ? user.getNickname() : user.getUsername();
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    private static AdminUserDTO toDto(User u) {
        AdminUserDTO dto = new AdminUserDTO();
        dto.setId(u.getId());
        dto.setUsername(u.getUsername());
        dto.setNickname(u.getNickname());
        dto.setStatus(u.getStatus());
        dto.setIsAdmin(u.getIsAdmin());
        dto.setEmail(u.getEmail());
        dto.setEmailVerified(u.getEmail() != null);
        dto.setCreatedAt(u.getCreatedAt() != null
                ? u.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
        return dto;
    }
}
