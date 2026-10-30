package com.bond.auth.service;

import com.bond.auth.mapper.EmailVerificationCodeMapper;
import com.bond.common.email.EmailGateway;
import com.bond.common.entity.EmailVerificationCode;
import com.bond.common.exception.RateLimitedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证码发送与发信防洪的衔接（task-63）：guard 拒绝时不得落库/发信；
 * 放行时按用途映射模板并填充变量。
 */
@ExtendWith(MockitoExtension.class)
class VerificationCodeGuardTest {

    private static final String EMAIL = "user@example.com";
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 23, 9, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private EmailVerificationCodeMapper codeMapper;
    @Mock
    private ConfigService configService;
    @Mock
    private EmailGateway emailGateway;
    @Mock
    private EmailSendGuard sendGuard;

    private VerificationCodeService newService() {
        return new VerificationCodeService(codeMapper, configService, emailGateway, sendGuard,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    private void stubsBeforeGuard() {
        when(configService.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(codeMapper.countWithinCooldown(EMAIL, VerificationCodeService.PURPOSE_REGISTER)).thenReturn(0L);
        when(codeMapper.countToday(EMAIL, VerificationCodeService.PURPOSE_REGISTER)).thenReturn(0L);
    }

    @Test
    @DisplayName("防洪拒绝（429）：不插入验证码、不发信")
    void guardRejectionAbortsBeforeAnySideEffect() {
        stubsBeforeGuard();
        org.mockito.Mockito.doThrow(new RateLimitedException("邮件发送过于频繁，请稍后再试", 30))
                .when(sendGuard).check(eq(EMAIL), anyString(), eq(null), eq("1.1.1.1"));

        RateLimitedException ex = assertThrows(RateLimitedException.class,
                () -> newService().send(EMAIL, VerificationCodeService.PURPOSE_REGISTER, null, "1.1.1.1"));

        assertEquals(30, ex.getRetryAfterSeconds());
        verify(codeMapper, never()).insertCode(any());
        verify(emailGateway, never()).sendByPurpose(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("放行：按用途选模板（register→verification_code）并填充 code/email/expiresMinutes")
    void guardPassSendsTemplatedMail() {
        stubsBeforeGuard();

        long cooldown = newService().send(EMAIL, VerificationCodeService.PURPOSE_REGISTER, 42L, "1.1.1.1");

        assertEquals(60, cooldown);
        ArgumentCaptor<EmailVerificationCode> row = ArgumentCaptor.forClass(EmailVerificationCode.class);
        verify(codeMapper).insertCode(row.capture());
        assertNotNull(row.getValue().getCodeHash());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(emailGateway).sendByPurpose(eq(EMAIL),
                eq(com.bond.common.email.EmailPurpose.REGISTER_CODE), vars.capture());
        String code = vars.getValue().get("code");
        assertNotNull(code);
        assertEquals(6, code.length());
        assertEquals(EMAIL, vars.getValue().get("email"));
        assertEquals("10", vars.getValue().get("expiresMinutes"));
    }

    @Test
    @DisplayName("用途到模板的映射：reset_password→password_reset，change_email→change_email")
    void purposeMapsToTemplateCode() {
        when(configService.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(codeMapper.countWithinCooldown(EMAIL, VerificationCodeService.PURPOSE_RESET_PASSWORD)).thenReturn(0L);
        when(codeMapper.countToday(EMAIL, VerificationCodeService.PURPOSE_RESET_PASSWORD)).thenReturn(0L);

        newService().send(EMAIL, VerificationCodeService.PURPOSE_RESET_PASSWORD, null, "1.1.1.1");

        verify(emailGateway).sendByPurpose(eq(EMAIL),
                eq(com.bond.common.email.EmailPurpose.PASSWORD_RESET), anyMap());
        assertEquals("verification_code", VerificationCodeService.templateCodeOf("register"));
        assertEquals("change_email", VerificationCodeService.templateCodeOf("change_email"));
        assertTrue(VerificationCodeService.PURPOSE_REGISTER.length() > 0);
    }
}
