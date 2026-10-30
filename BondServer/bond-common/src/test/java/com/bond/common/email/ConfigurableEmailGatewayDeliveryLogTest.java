package com.bond.common.email;

import com.bond.common.entity.EmailTemplate;
import com.bond.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-80：发信成功/失败都必须落一条投递日志；写库失败绝不能影响发信。
 */
class ConfigurableEmailGatewayDeliveryLogTest {

    private static final String TO = "probe@example.com";

    private static EmailSettings enabledSmtp() {
        return new EmailSettings(
                EmailSettings.PROVIDER_SMTP,
                "verify@example.com",
                "Bond",
                new EmailSettings.Smtp("smtp.local", 587, "u", "p", true, true, false, 5),
                "",
                "https://api.resend.com",
                "");
    }

    private static EmailTemplate template() {
        EmailTemplate t = new EmailTemplate();
        t.setCode("verification_code");
        t.setSubject("验证码 {{code}}");
        t.setBodyHtml("<p>验证码 {{code}}</p>");
        t.setBodyText("验证码 {{code}}");
        t.setEnabled(true);
        return t;
    }

    /** 假发送实现：可控成功/失败，不走网络。 */
    private static ConfigurableEmailGateway gateway(EmailDeliveryRecorder recorder, Runnable sendAction) {
        ConfigurableEmailGateway gateway = new ConfigurableEmailGateway(
                ConfigurableEmailGatewayDeliveryLogTest::enabledSmtp,
                code -> template()) {
            @Override
            void sendViaProvider(EmailSettings settings, String to,
                                 EmailTemplateRenderer.RenderedEmail email, String provider) {
                sendAction.run();
            }
        };
        gateway.setDeliveryRecorder(recorder);
        return gateway;
    }

    private static final class RecordingRecorder implements EmailDeliveryRecorder {
        final List<EmailDeliveryRecord> records = new ArrayList<>();

        @Override
        public void record(EmailDeliveryRecord record) {
            records.add(record);
        }
    }

    @Test
    @DisplayName("成功发送：落 status=sent，变量名保留、收件人掩码、placeholders_left=0")
    void successWritesSentRecord() {
        RecordingRecorder recorder = new RecordingRecorder();
        ConfigurableEmailGateway gateway = gateway(recorder, () -> {
        });

        gateway.sendTemplate(TO, "verification_code", Map.of("code", "123456"));

        assertEquals(1, recorder.records.size());
        EmailDeliveryRecorder.EmailDeliveryRecord record = recorder.records.get(0);
        assertEquals(EmailDeliveryRecorder.EmailDeliveryRecord.STATUS_SENT, record.status());
        assertEquals("verification_code", record.templateCode());
        assertEquals("b***@example.com", record.toMasked());
        assertEquals("smtp", record.provider());
        assertEquals(0, record.placeholdersLeft());
        assertTrue(record.variableKeys().contains("code"));
        assertTrue(record.subject().contains("123456"));
        assertFalse(record.toMasked().contains("bondprobe"));
    }

    @Test
    @DisplayName("发送失败：先落 status=failed（含原因摘要），再把 503 抛给调用方")
    void failureWritesFailedRecordThenThrows503() {
        RecordingRecorder recorder = new RecordingRecorder();
        ConfigurableEmailGateway gateway = gateway(recorder, () -> {
            throw new EmailSendException("550 missing html or text field", null);
        });

        BusinessException ex = assertThrows(BusinessException.class,
                () -> gateway.sendTemplate(TO, "verification_code", Map.of("code", "123456")));

        assertEquals(503, ex.getCode());
        assertEquals(1, recorder.records.size());
        EmailDeliveryRecorder.EmailDeliveryRecord record = recorder.records.get(0);
        assertEquals(EmailDeliveryRecorder.EmailDeliveryRecord.STATUS_FAILED, record.status());
        assertNotNull(record.failureReason());
        assertTrue(record.failureReason().contains("550"));
        assertEquals(0, record.placeholdersLeft());
    }

    @Test
    @DisplayName("写库失败不影响发信：recorder 抛异常被吞掉，发送照常成功")
    void recorderFailureDoesNotBreakSending() {
        EmailDeliveryRecorder broken = record -> {
            throw new IllegalStateException("db down");
        };
        int[] sent = {0};
        ConfigurableEmailGateway gateway = gateway(broken, () -> sent[0]++);

        gateway.sendTemplate(TO, "verification_code", Map.of("code", "123456"));

        assertEquals(1, sent[0]);
    }

    @Test
    @DisplayName("按用途发送：日志记录 purpose")
    void purposeIsRecorded() {
        RecordingRecorder recorder = new RecordingRecorder();
        ConfigurableEmailGateway gateway = new ConfigurableEmailGateway(
                ConfigurableEmailGatewayDeliveryLogTest::enabledSmtp,
                code -> null,
                purpose -> "verification_code") {
            @Override
            void sendViaProvider(EmailSettings settings, String to,
                                 EmailTemplateRenderer.RenderedEmail email, String provider) {
            }
        };
        gateway.setDeliveryRecorder(recorder);

        gateway.sendByPurpose(TO, EmailPurpose.REGISTER_CODE, Map.of("code", "654321"));

        assertEquals(1, recorder.records.size());
        assertEquals(EmailPurpose.REGISTER_CODE, recorder.records.get(0).purpose());
    }
}
