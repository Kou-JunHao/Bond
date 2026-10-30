package com.bond.auth.service;

import com.bond.auth.mapper.EmailVerificationCodeMapper;
import com.bond.common.email.EmailGateway;
import com.bond.common.entity.EmailVerificationCode;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.RateLimitedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 邮箱验证码服务（task-57）：冷却/日上限/一次性/尝试次数/HMAC 不存明文。
 */
@ExtendWith(MockitoExtension.class)
class VerificationCodeServiceTest {

    private static final String EMAIL = "alice@example.com";
    private static final String PURPOSE = VerificationCodeService.PURPOSE_REGISTER;
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

    private void defaultConfig() {
        when(configService.getInt(anyString(), any(Integer.class))).thenAnswer(inv -> inv.getArgument(1));
    }

    private EmailVerificationCode activeRow(String code, OffsetDateTime expiresAt, int attempts) {
        EmailVerificationCode row = new EmailVerificationCode();
        row.setId(1L);
        row.setEmail(EMAIL);
        row.setPurpose(PURPOSE);
        row.setCodeHash(VerificationCodeService.hmacHex(code, EMAIL, PURPOSE));
        row.setExpiresAt(expiresAt);
        row.setAttempts(attempts);
        return row;
    }

    @Test
    @DisplayName("发送：存 HMAC 摘要（非明文）并发信，返回冷却秒数")
    void sendStoresHashedCodeAndMails() {
        defaultConfig();
        when(codeMapper.countWithinCooldown(EMAIL, PURPOSE)).thenReturn(0L);
        when(codeMapper.countToday(EMAIL, PURPOSE)).thenReturn(0L);

        long cooldown = newService().send("  Alice@Example.com ", PURPOSE, null, "1.2.3.4");

        assertEquals(60, cooldown);
        ArgumentCaptor<EmailVerificationCode> captor = ArgumentCaptor.forClass(EmailVerificationCode.class);
        verify(codeMapper).insertCode(captor.capture());
        EmailVerificationCode row = captor.getValue();
        assertEquals(EMAIL, row.getEmail(), "邮箱应规范化为小写");
        assertNotNull(row.getCodeHash());
        assertEquals(64, row.getCodeHash().length(), "SHA-256 hex 长度 64");
        assertNotNull(row.getExpiresAt());
        assertEquals(0, row.getAttempts());
        verify(emailGateway).sendByPurpose(eq(EMAIL),
                eq(com.bond.common.email.EmailPurpose.REGISTER_CODE), anyMap());
    }

    @Test
    @DisplayName("发送：60 秒冷却内重复请求抛 429（RateLimitedException），不落库不发信")
    void sendRejectsWithinCooldown() {
        when(configService.getInt("email.code.resend_seconds", 60)).thenReturn(60);
        when(codeMapper.countWithinCooldown(EMAIL, PURPOSE)).thenReturn(1L);

        RateLimitedException ex = assertThrows(RateLimitedException.class,
                () -> newService().send(EMAIL, PURPOSE, null, "1.2.3.4"));
        assertTrue(ex.getRetryAfterSeconds() >= 1);
        verify(codeMapper, never()).insertCode(any());
        verify(emailGateway, never()).sendByPurpose(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("发送：当日达上限返回 429 业务错误")
    void sendRejectsDailyLimit() {
        when(configService.getInt("email.code.resend_seconds", 60)).thenReturn(60);
        when(codeMapper.countWithinCooldown(EMAIL, PURPOSE)).thenReturn(0L);
        when(configService.getInt("email.code.daily_limit", 10)).thenReturn(10);
        when(codeMapper.countToday(EMAIL, PURPOSE)).thenReturn(10L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> newService().send(EMAIL, PURPOSE, null, "1.2.3.4"));
        assertEquals(429, ex.getCode());
        verify(codeMapper, never()).insertCode(any());
    }

    @Test
    @DisplayName("发送：未知用途 / 非法邮箱直接 400，不查库")
    void sendRejectsInvalidPurposeAndEmail() {
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> newService().send(EMAIL, "login", null, "1.2.3.4"));
        assertEquals(400, e1.getCode());

        BusinessException e2 = assertThrows(BusinessException.class,
                () -> newService().send("not-an-email", PURPOSE, null, "1.2.3.4"));
        assertEquals(400, e2.getCode());
        verify(codeMapper, never()).insertCode(any());
    }

    @Test
    @DisplayName("发送失败（邮件网关异常）时删除刚插入的行，避免假冷却")
    void sendCleansUpWhenGatewayFails() {
        defaultConfig();
        when(codeMapper.countWithinCooldown(EMAIL, PURPOSE)).thenReturn(0L);
        when(codeMapper.countToday(EMAIL, PURPOSE)).thenReturn(0L);
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp down"))
                .when(emailGateway).sendByPurpose(anyString(), anyString(), anyMap());

        assertThrows(IllegalStateException.class,
                () -> newService().send(EMAIL, PURPOSE, null, "1.2.3.4"));
        verify(codeMapper).deleteById(any(java.io.Serializable.class));
    }

    @Test
    @DisplayName("校验：正确码一次性消费并返回邮箱")
    void verifyAndConsumeHappyPath() {
        when(codeMapper.selectActive(EMAIL, PURPOSE)).thenReturn(activeRow("123456", NOW.plusMinutes(5), 0));
        when(configService.getInt("email.code.max_attempts", 5)).thenReturn(5);
        when(codeMapper.consume(1L)).thenReturn(1);

        assertEquals(EMAIL, newService().verifyAndConsume(EMAIL, PURPOSE, " 123456 "));
        verify(codeMapper).consume(1L);
    }

    @Test
    @DisplayName("校验：错误码累加尝试次数并提示错误")
    void verifyWrongCodeIncrementsAttempts() {
        when(codeMapper.selectActive(EMAIL, PURPOSE)).thenReturn(activeRow("123456", NOW.plusMinutes(5), 0));
        when(configService.getInt("email.code.max_attempts", 5)).thenReturn(5);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> newService().verifyAndConsume(EMAIL, PURPOSE, "000000"));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("验证码错误"));
        verify(codeMapper).incrementAttempts(1L);
        verify(codeMapper, never()).consume(any());
    }

    @Test
    @DisplayName("校验：达到最大尝试次数后作废验证码")
    void verifyTooManyAttemptsInvalidates() {
        when(codeMapper.selectActive(EMAIL, PURPOSE)).thenReturn(activeRow("123456", NOW.plusMinutes(5), 5));
        when(configService.getInt("email.code.max_attempts", 5)).thenReturn(5);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> newService().verifyAndConsume(EMAIL, PURPOSE, "123456"));
        assertEquals(400, ex.getCode());
        verify(codeMapper).invalidate(1L);
    }

    @Test
    @DisplayName("校验：过期码/不存在/格式错误都拒绝")
    void verifyRejectsExpiredMissingAndMalformed() {
        when(codeMapper.selectActive(EMAIL, PURPOSE))
                .thenReturn(activeRow("123456", NOW.minusMinutes(1), 0));
        BusinessException expired = assertThrows(BusinessException.class,
                () -> newService().verifyAndConsume(EMAIL, PURPOSE, "123456"));
        assertTrue(expired.getMessage().contains("过期"));

        when(codeMapper.selectActive(EMAIL, PURPOSE)).thenReturn(null);
        BusinessException missing = assertThrows(BusinessException.class,
                () -> newService().verifyAndConsume(EMAIL, PURPOSE, "123456"));
        assertTrue(missing.getMessage().contains("无效"));

        BusinessException malformed = assertThrows(BusinessException.class,
                () -> newService().verifyAndConsume(EMAIL, PURPOSE, "12ab56"));
        assertEquals(400, malformed.getCode());
    }

    @Test
    @DisplayName("校验：并发消费失败（consume 影响 0 行）按已使用处理")
    void verifyConcurrentConsumeLost() {
        when(codeMapper.selectActive(EMAIL, PURPOSE)).thenReturn(activeRow("123456", NOW.plusMinutes(5), 0));
        when(configService.getInt("email.code.max_attempts", 5)).thenReturn(5);
        when(codeMapper.consume(1L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> newService().verifyAndConsume(EMAIL, PURPOSE, "123456"));
        assertTrue(ex.getMessage().contains("已使用"));
    }

    @Test
    @DisplayName("改邮箱确认：取用户最近 change_email 码并消费")
    void verifyChangeEmailUsesActiveRow() {
        EmailVerificationCode row = new EmailVerificationCode();
        row.setId(2L);
        row.setEmail("new@example.com");
        row.setPurpose(VerificationCodeService.PURPOSE_CHANGE_EMAIL);
        row.setCodeHash(VerificationCodeService.hmacHex("654321", "new@example.com",
                VerificationCodeService.PURPOSE_CHANGE_EMAIL));
        row.setExpiresAt(NOW.plusMinutes(5));
        row.setAttempts(0);
        when(codeMapper.selectActiveByUser(9L, VerificationCodeService.PURPOSE_CHANGE_EMAIL)).thenReturn(row);
        when(codeMapper.selectActive("new@example.com", VerificationCodeService.PURPOSE_CHANGE_EMAIL)).thenReturn(row);
        when(configService.getInt("email.code.max_attempts", 5)).thenReturn(5);
        when(codeMapper.consume(2L)).thenReturn(1);

        assertEquals("new@example.com", newService().verifyChangeEmail(9L, "654321"));
        assertNotEquals(row.getCodeHash(), "654321", "库里不应出现明文验证码");
    }

    @Test
    @DisplayName("normalizeEmail：大小写/空格归一，非法格式 400")
    void normalizeEmail() {
        assertEquals(EMAIL, VerificationCodeService.normalizeEmail(" Alice@Example.COM "));
        assertThrows(BusinessException.class, () -> VerificationCodeService.normalizeEmail("a@b"));
        assertThrows(BusinessException.class, () -> VerificationCodeService.normalizeEmail(""));
        assertFalse(VerificationCodeService.hmacHex("123456", EMAIL, PURPOSE).isEmpty());
    }
}
