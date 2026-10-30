package com.bond.auth.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bond.auth.mapper.UserMapper;
import com.bond.common.dto.UserDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册链路：用户插入成功后必须调用个人空间初始化（同事务）。
 *
 * <p>JWT_SECRET 由 bond-auth/pom.xml 的 surefire environmentVariables 注入（仅测试用的假值，
 * 长度 >= 32 字节以满足 HS256），因此这里走真实的 JwtUtils。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceRegisterTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private ConfigService configService;
    @Mock
    private PersonalSpaceInitializer personalSpaceInitializer;
    @Mock
    private VerificationCodeService verificationCodeService;
    @Mock
    private com.bond.common.email.EmailGateway emailGateway;

    @InjectMocks
    private AuthService authService;

    @Test
    @DisplayName("register 尾部创建个人空间（verify initializer 收到新用户 id）")
    void registerCreatesPersonalSpace() {
        when(configService.getBoolean("user.allow_register", true)).thenReturn(true);
        when(emailGateway.isEnabled()).thenReturn(false);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        UserDTO dto = authService.register("alice", "secret123", "Alice");

        assertNotNull(dto);
        assertNotNull(dto.getId(), "user id must be generated");
        assertEquals("alice", dto.getUsername());
        assertNotNull(dto.getAccessToken());
        assertNotNull(dto.getRefreshToken());
        verify(personalSpaceInitializer).createForUser(dto.getId());
    }

    @Test
    @DisplayName("禁止注册时直接拒绝，不创建用户/个人空间")
    void registerRejectedWhenDisabled() {
        when(configService.getBoolean("user.allow_register", true)).thenReturn(false);

        com.bond.common.exception.BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.bond.common.exception.BusinessException.class,
                () -> authService.register("bob", "secret123", "Bob"));
        assertEquals(403, ex.getCode());
        verify(personalSpaceInitializer, org.mockito.Mockito.never()).createForUser(any());
    }
}
