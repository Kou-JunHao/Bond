package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bond.common.dto.AdminUserDTO;
import com.bond.common.email.EmailGateway;
import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.ConflictException;
import com.bond.transfer.mapper.UserMapper;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * task-57 管理台：重置密码（临时密码只发邮件、不回显）+ 编辑邮箱（唯一性/规范化）。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserEmailServiceTest {

    private static final String EMAIL = "alice@example.com";
    private static final Pattern TEMP_PASSWORD = Pattern.compile("(?m)^    ([A-Za-z0-9]{16})$");

    @Mock
    private UserMapper userMapper;
    @Mock
    private EmailGateway emailGateway;

    @InjectMocks
    private AdminUserEmailService service;

    private static User user(long id, String email) {
        User u = new User();
        u.setId(id);
        u.setUsername("alice");
        u.setNickname("Alice");
        u.setEmail(email);
        u.setStatus(1);
        u.setIsAdmin(false);
        return u;
    }

    @Test
    @DisplayName("重置密码：生成临时密码、BCrypt 落库、作废旧 refresh、发信且不回显密码")
    void resetPasswordSendsTempPasswordByMail() {
        User u = user(7L, EMAIL);
        when(userMapper.selectById(7L)).thenReturn(u);

        Map<String, Object> result = service.resetPassword(7L);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById((User) saved.capture());
        User persisted = saved.getValue();
        assertNotNull(persisted.getTokensValidAfter(), "改密后必须作废旧 refresh token");

        ArgumentCaptor<Map> vars = ArgumentCaptor.forClass(Map.class);
        verify(emailGateway).sendByPurpose(eq(EMAIL),
                eq(com.bond.common.email.EmailPurpose.ADMIN_RESET_PASSWORD), vars.capture());
        String tempPassword = String.valueOf(vars.getValue().get("code"));
        assertEquals(16, tempPassword.length(), "临时密码应为 16 位");
        assertTrue(new BCryptPasswordEncoder().matches(tempPassword, persisted.getPasswordHash()),
                "库里存的哈希必须与邮件中的临时密码匹配");

        // 接口返回只包含投递结果与掩码邮箱，不含密码或完整邮箱
        assertEquals(true, result.get("delivered"));
        assertEquals("a***@example.com", result.get("emailMasked"));
    }

    @Test
    @DisplayName("重置密码：用户未绑定邮箱时 400，且不发信不写库")
    void resetPasswordRequiresEmail() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, null));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.resetPassword(7L));
        assertEquals(400, ex.getCode());
        verify(userMapper, never()).updateById(any(User.class));
        verify(emailGateway, never()).sendByPurpose(anyString(), anyString(), any(Map.class));
    }

    @Test
    @DisplayName("重置密码：用户不存在 404")
    void resetPasswordUserNotFound() {
        when(userMapper.selectById(7L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.resetPassword(7L));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("编辑邮箱：规范化小写落库并返回 emailVerified=true")
    void updateEmailNormalizesAndPersists() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, null));
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        AdminUserDTO dto = service.updateEmail(7L, "  Alice@Example.COM ");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById((User) saved.capture());
        assertEquals(EMAIL, saved.getValue().getEmail());
        assertEquals(EMAIL, dto.getEmail());
        assertEquals(Boolean.TRUE, dto.getEmailVerified());
    }

    @Test
    @DisplayName("编辑邮箱：被其他账号占用时 409")
    void updateEmailConflict() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, null));
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(user(9L, EMAIL));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.updateEmail(7L, EMAIL));
        assertEquals(409, ex.getCode());
        assertEquals(Map.of("field", "email"), ex.getData());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("编辑邮箱：同一用户本身绑定该邮箱不算冲突")
    void updateEmailAllowsSameUser() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, EMAIL));
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(user(7L, EMAIL));

        AdminUserDTO dto = service.updateEmail(7L, EMAIL);
        assertEquals(EMAIL, dto.getEmail());
        verify(userMapper).updateById(any(User.class));
    }

    @Test
    @DisplayName("编辑邮箱：非法格式 400")
    void updateEmailRejectsInvalidFormat() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, null));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateEmail(7L, "not-an-email"));
        assertEquals(400, ex.getCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("编辑邮箱：用户不存在 404")
    void updateEmailUserNotFound() {
        when(userMapper.selectById(7L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateEmail(7L, EMAIL));
        assertEquals(404, ex.getCode());
    }
}
