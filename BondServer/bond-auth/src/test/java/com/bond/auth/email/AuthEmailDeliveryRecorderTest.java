package com.bond.auth.email;

import com.bond.auth.mapper.EmailDeliveryLogMapper;
import com.bond.common.email.EmailDeliveryRecorder;
import com.bond.common.entity.EmailDeliveryLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * task-80：bond-auth 的投递日志落库映射（只写不查）。
 */
@ExtendWith(MockitoExtension.class)
class AuthEmailDeliveryRecorderTest {

    @Mock
    private EmailDeliveryLogMapper emailDeliveryLogMapper;

    @InjectMocks
    private AuthEmailDeliveryRecorder recorder;

    @Test
    @DisplayName("成功记录：实体字段映射正确，变量只存名称 JSON 数组")
    void mapsSuccessRecord() {
        recorder.record(new EmailDeliveryRecorder.EmailDeliveryRecord(
                7L, "b***@example.com", "register_code", "verification_code",
                "验证码 123456", "sent", null, List.of("code", "expiresMinutes"),
                List.of(), 0, "smtp", 42, 1, null));

        ArgumentCaptor<EmailDeliveryLog> captor = ArgumentCaptor.forClass(EmailDeliveryLog.class);
        verify(emailDeliveryLogMapper).insert(captor.capture());
        EmailDeliveryLog log = captor.getValue();
        assertEquals(7L, log.getUserId());
        assertEquals("b***@example.com", log.getToMasked());
        assertEquals("register_code", log.getPurpose());
        assertEquals("verification_code", log.getTemplateCode());
        assertEquals("验证码 123456", log.getSubject());
        assertEquals("sent", log.getStatus());
        assertNull(log.getFailureReason());
        assertEquals("[\"code\",\"expiresMinutes\"]", log.getVariableKeys());
        assertEquals(0, log.getPlaceholdersLeft());
        assertEquals("smtp", log.getProvider());
        assertEquals(42, log.getDurationMs());
    }

    @Test
    @DisplayName("失败记录：failure_reason 带上并可空变量")
    void mapsFailureRecord() {
        recorder.record(new EmailDeliveryRecorder.EmailDeliveryRecord(
                null, "x***@example.com", null, null, "主题", "failed",
                "EmailSendException: 550 missing html", List.of(), List.of(), 0, "resend", 9,
                4, "enc:v1:cipher"));

        ArgumentCaptor<EmailDeliveryLog> captor = ArgumentCaptor.forClass(EmailDeliveryLog.class);
        verify(emailDeliveryLogMapper).insert(captor.capture());
        EmailDeliveryLog log = captor.getValue();
        assertEquals("failed", log.getStatus());
        assertTrue(log.getFailureReason().contains("550"));
        assertEquals("[]", log.getVariableKeys());
        assertNull(log.getUserId());
        assertNull(log.getPurpose());
    }
}
