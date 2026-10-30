package com.bond.auth.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bond.auth.mapper.UserMapper;
import com.bond.common.dto.UserDTO;
import com.bond.common.email.EmailGateway;
import com.bond.common.email.EmailPurpose;
import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.ConflictException;
import com.bond.common.utils.JwtUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-57：邮箱注册 / 验证码 / 找回密码 / 改邮箱 / refresh 失效。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceEmailTest {

    private static final String EMAIL = "alice@example.com";

    @Mock
    private UserMapper userMapper;
    @Mock
    private ConfigService configService;
    @Mock
    private PersonalSpaceInitializer personalSpaceInitializer;
    @Mock
    private VerificationCodeService verificationCodeService;
    @Mock
    private EmailGateway emailGateway;

    @InjectMocks
    private AuthService authService;

    private static User user(long id, String email, String plainPassword) {
        User u = new User();
        u.setId(id);
        u.setUsername("alice");
        u.setNickname("Alice");
        u.setPasswordHash(new BCryptPasswordEncoder().encode(plainPassword));
        u.setEmail(email);
        u.setStatus(1);
        return u;
    }

    @Test
    @DisplayName("邮件通道关闭时允许无邮箱注册（本地/测试）")
    void registerWithoutEmailWhenMailDisabled() {
        when(configService.getBoolean("user.allow_register", true)).thenReturn(true);
        when(emailGateway.isEnabled()).thenReturn(false);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        UserDTO dto = authService.register("alice", "secret123", "Alice");

        assertNotNull(dto.getId());
        verify(verificationCodeService, never()).verifyAndConsume(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("邮件通道启用时注册必须带邮箱，否则 400")
    void registerRequiresEmailWhenMailEnabled() {
        when(configService.getBoolean("user.allow_register", true)).thenReturn(true);
        when(emailGateway.isEnabled()).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register("alice", "secret123", "Alice"));
        assertEquals(400, ex.getCode());
        assertEquals("邮箱为必填项", ex.getMessage());
    }

    @Test
    @DisplayName("task-79：注册欢迎邮件必须携带 email（welcome 模板声明了 {{email}}）")
    void registerSendsWelcomeWithEmail() {
        when(configService.getBoolean("user.allow_register", true)).thenReturn(true);
        when(emailGateway.isEnabled()).thenReturn(true);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        authService.register("alice", "secret123", "Alice", " Alice@Example.COM ", "123456");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(emailGateway).sendByPurpose(eq(EMAIL), eq(EmailPurpose.WELCOME), captor.capture());
        assertEquals(EMAIL, captor.getValue().get("email"),
                "welcome 模板声明了 {{email}}，发送方必须提供，否则渲染成空白");
        assertEquals("Alice", captor.getValue().get("username"));
    }

    @Test
    @DisplayName("注册：邮箱已被占用返回 409（ConflictException）")
    void registerRejectsDuplicateEmail() {
        when(configService.getBoolean("user.allow_register", true)).thenReturn(true);
        // 第一次 selectOne 是用户名查重（null），第二次是邮箱查重（命中）
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null, user(9L, EMAIL, "x"));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> authService.register("alice", "secret123", "Alice", EMAIL, "123456"));
        assertEquals(409, ex.getCode());
    }

    @Test
    @DisplayName("注册：先校验邮箱/用户名唯一性再消费验证码，成功则落库并建个人空间")
    void registerWithEmailConsumesCode() {
        when(configService.getBoolean("user.allow_register", true)).thenReturn(true);
        when(emailGateway.isEnabled()).thenReturn(true);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        UserDTO dto = authService.register("alice", "secret123", "Alice", " Alice@Example.COM ", "123456");

        verify(verificationCodeService).verifyAndConsume(eq(EMAIL),
                eq(VerificationCodeService.PURPOSE_REGISTER), eq("123456"));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals(EMAIL, captor.getValue().getEmail());
        assertEquals(Boolean.TRUE, dto.getEmailVerified());
        assertEquals(EMAIL, dto.getEmail());
        verify(personalSpaceInitializer).createForUser(dto.getId());
    }

    @Test
    @DisplayName("注册：用户名冲突时不消费验证码（用户刚收到的码不被浪费）")
    void registerDoesNotConsumeCodeWhenUsernameTaken() {
        when(configService.getBoolean("user.allow_register", true)).thenReturn(true);
        User existing = user(9L, "other@example.com", "x");
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register("alice", "secret123", "Alice", EMAIL, "123456"));
        assertEquals(400, ex.getCode());
        verify(verificationCodeService, never()).verifyAndConsume(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("找回密码：未注册邮箱防枚举——返回同样冷却、不发信")
    void requestResetCodeForUnknownEmailIsEnumerationSafe() {
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(verificationCodeService.cooldownSeconds()).thenReturn(60L);

        assertEquals(60L, authService.requestEmailCode(EMAIL,
                VerificationCodeService.PURPOSE_RESET_PASSWORD, "1.2.3.4"));
        verify(verificationCodeService, never()).send(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("找回密码：已注册邮箱发信，携带 userId")
    void requestResetCodeForKnownEmailSends() {
        User u = user(7L, EMAIL, "x");
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(u);
        when(emailGateway.isEnabled()).thenReturn(true);
        when(verificationCodeService.send(EMAIL, VerificationCodeService.PURPOSE_RESET_PASSWORD,
                7L, "1.2.3.4")).thenReturn(60L);

        assertEquals(60L, authService.requestEmailCode(EMAIL,
                VerificationCodeService.PURPOSE_RESET_PASSWORD, "1.2.3.4"));
        verify(verificationCodeService).send(EMAIL,
                VerificationCodeService.PURPOSE_RESET_PASSWORD, 7L, "1.2.3.4");
    }

    @Test
    @DisplayName("匿名接口不允许 change_email 用途，必须登录走专用接口")
    void requestCodeRejectsChangeEmailPurpose() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.requestEmailCode("new@example.com",
                        VerificationCodeService.PURPOSE_CHANGE_EMAIL, "1.2.3.4"));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("找回密码成功：更新密码哈希 + 写 tokens_valid_after + 消费验证码")
    void resetPasswordInvalidatesRefreshTokens() {
        User u = user(7L, EMAIL, "old-password");
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(u);

        authService.resetPassword(EMAIL, "123456", "new-password");

        verify(verificationCodeService).verifyAndConsume(eq(EMAIL),
                eq(VerificationCodeService.PURPOSE_RESET_PASSWORD), eq("123456"));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        User saved = captor.getValue();
        assertNotNull(saved.getTokensValidAfter());
        assertTrue(new BCryptPasswordEncoder().matches("new-password", saved.getPasswordHash()));
        // 旧密码必须不再匹配
        assertEquals(false, new BCryptPasswordEncoder().matches("old-password", saved.getPasswordHash()));
    }

    @Test
    @DisplayName("找回密码：未注册邮箱与验证码错误同文案（防枚举）")
    void resetPasswordUnknownEmailSameError() {
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.resetPassword(EMAIL, "123456", "new-password"));
        assertEquals(400, ex.getCode());
        assertEquals("验证码无效或已过期", ex.getMessage());
    }

    @Test
    @DisplayName("改邮箱第一步：密码错误 400；新邮箱与当前相同 400")
    void requestChangeEmailValidatesPasswordAndUniqueness() {
        User u = user(7L, EMAIL, "secret123");
        String token = JwtUtils.generateAccessToken(7L, "alice");
        when(userMapper.selectById(7L)).thenReturn(u);

        BusinessException wrongPassword = assertThrows(BusinessException.class,
                () -> authService.requestChangeEmail(token, "bad-password", "new@example.com", "1.2.3.4"));
        assertEquals(400, wrongPassword.getCode());
        verify(verificationCodeService, never()).send(anyString(), anyString(), any(), anyString());

        BusinessException sameEmail = assertThrows(BusinessException.class,
                () -> authService.requestChangeEmail(token, "secret123", EMAIL.toUpperCase(), "1.2.3.4"));
        assertEquals(400, sameEmail.getCode());
    }

    @Test
    @DisplayName("改邮箱第二步：写入新邮箱并返回 DTO")
    void confirmChangeEmailUpdatesRow() {
        User u = user(7L, EMAIL, "secret123");
        String token = JwtUtils.generateAccessToken(7L, "alice");
        when(userMapper.selectById(7L)).thenReturn(u);
        when(verificationCodeService.verifyChangeEmail(7L, "654321")).thenReturn("new@example.com");

        UserDTO dto = authService.confirmChangeEmail(token, "654321");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertEquals("new@example.com", captor.getValue().getEmail());
        assertEquals("new@example.com", dto.getEmail());
    }

    @Test
    @DisplayName("改邮箱二次确认：目标邮箱在两步之间被其他账号占用 → 409（不 500）")
    void confirmChangeEmailRejectsEmailTakenMeanwhile() {
        User u = user(7L, EMAIL, "secret123");
        String token = JwtUtils.generateAccessToken(7L, "alice");
        when(userMapper.selectById(7L)).thenReturn(u);
        when(verificationCodeService.verifyChangeEmail(7L, "654321")).thenReturn("new@example.com");
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(user(9L, "new@example.com", "secret123"));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> authService.confirmChangeEmail(token, "654321"));

        assertEquals(409, ex.getCode());
        assertEquals("该邮箱已被其他账号使用", ex.getMessage());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("refresh token：早于 tokens_valid_after 签发的一律 401")
    void refreshRejectsTokenIssuedBeforePasswordChange() {
        String refreshToken = JwtUtils.generateRefreshToken(7L);
        User u = user(7L, EMAIL, "secret123");
        u.setTokensValidAfter(OffsetDateTime.now().plusMinutes(5));
        when(userMapper.selectById(7L)).thenReturn(u);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.refreshToken(refreshToken));
        assertEquals(401, ex.getCode());
    }
}
