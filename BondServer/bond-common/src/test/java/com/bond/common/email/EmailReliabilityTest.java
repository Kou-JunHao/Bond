package com.bond.common.email;

import com.bond.common.entity.EmailTemplate;
import com.bond.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-129：发信可靠性——瞬时错误指数退避重试、永久失败不重试、缺变量渲染为 “—”、失败摘要可诊断。
 *
 * <p>全部走假 provider（覆写 {@code sendViaProvider}），不触网、不 sleep（替换 backoffSleeper）。</p>
 */
class EmailReliabilityTest {

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
        t.setBodyHtml("<p>验证码 {{code}}，操作人 {{action}}</p>");
        t.setBodyText("验证码 {{code}}，操作人 {{action}}");
        t.setEnabled(true);
        return t;
    }

    private static final class RecordingRecorder implements EmailDeliveryRecorder {
        final List<EmailDeliveryRecord> records = new ArrayList<>();

        @Override
        public void record(EmailDeliveryRecord record) {
            records.add(record);
        }
    }

    /** 记录顺序的 sleeper 替代品，避免单测真的等 200/600/1800ms。 */
    private static final class RecordingSleeper implements java.util.function.LongConsumer {
        final List<Long> delays = new ArrayList<>();

        @Override
        public void accept(long value) {
            delays.add(value);
        }
    }

    @Test
    @DisplayName("瞬时错误（可重试）按 200/600ms 退避后成功：共 3 次尝试，成功日志只落 1 条 sent")
    void transientFailuresAreRetriedWithBackoff() {
        RecordingRecorder recorder = new RecordingRecorder();
        RecordingSleeper sleeper = new RecordingSleeper();
        AtomicInteger attempts = new AtomicInteger();

        ConfigurableEmailGateway gateway = new ConfigurableEmailGateway(
                EmailReliabilityTest::enabledSmtp,
                code -> template()) {
            @Override
            void sendViaProvider(EmailSettings settings, String to,
                                 EmailTemplateRenderer.RenderedEmail email, String provider) {
                int attempt = attempts.incrementAndGet();
                if (attempt <= 2) {
                    throw new EmailSendException("http 503 upstream busy", 503, true, null);
                }
            }
        };
        gateway.setDeliveryRecorder(recorder);
        gateway.setBackoffSleeper(sleeper);

        gateway.sendTemplate(TO, "verification_code", Map.of("code", "123456"));

        assertEquals(3, attempts.get(), "两次瞬时失败后第三次应成功");
        assertEquals(List.of(200L, 600L), sleeper.delays, "退避顺序必须是 200ms → 600ms");
        assertEquals(1, recorder.records.size());
        assertEquals(EmailDeliveryRecorder.EmailDeliveryRecord.STATUS_SENT, recorder.records.get(0).status());
    }

    @Test
    @DisplayName("永久失败（4xx）不重试：只尝试 1 次、不退避、日志含 httpStatus 与 attempts")
    void permanentFailureIsNotRetried() {
        RecordingRecorder recorder = new RecordingRecorder();
        RecordingSleeper sleeper = new RecordingSleeper();
        AtomicInteger attempts = new AtomicInteger();

        ConfigurableEmailGateway gateway = new ConfigurableEmailGateway(
                EmailReliabilityTest::enabledSmtp,
                code -> template()) {
            @Override
            void sendViaProvider(EmailSettings settings, String to,
                                 EmailTemplateRenderer.RenderedEmail email, String provider) {
                attempts.incrementAndGet();
                throw new EmailSendException("http 400 invalid recipient", 400, false, null);
            }
        };
        gateway.setDeliveryRecorder(recorder);
        gateway.setBackoffSleeper(sleeper);

        assertThrows(BusinessException.class,
                () -> gateway.sendTemplate(TO, "verification_code", Map.of("code", "123456")));

        assertEquals(1, attempts.get(), "永久失败不允许重试");
        assertTrue(sleeper.delays.isEmpty(), "永久失败不允许退避等待");

        assertEquals(1, recorder.records.size());
        EmailDeliveryRecorder.EmailDeliveryRecord record = recorder.records.get(0);
        assertEquals(EmailDeliveryRecorder.EmailDeliveryRecord.STATUS_FAILED, record.status());
        assertNotNull(record.failureReason());
        assertTrue(record.failureReason().contains("provider=smtp"));
        assertTrue(record.failureReason().contains("httpStatus=400"));
        assertTrue(record.failureReason().contains("attempts=1"));
    }

    @Test
    @DisplayName("瞬时错误重试耗尽：抛 503，失败日志 attempts=4（首试 + 3 次重试）")
    void retriesExhaustedYields503AndDiagnosableFailure() {
        RecordingRecorder recorder = new RecordingRecorder();
        RecordingSleeper sleeper = new RecordingSleeper();
        AtomicInteger attempts = new AtomicInteger();

        ConfigurableEmailGateway gateway = new ConfigurableEmailGateway(
                EmailReliabilityTest::enabledSmtp,
                code -> template()) {
            @Override
            void sendViaProvider(EmailSettings settings, String to,
                                 EmailTemplateRenderer.RenderedEmail email, String provider) {
                attempts.incrementAndGet();
                throw new EmailSendException("connect timeout", 429, true, null);
            }
        };
        gateway.setDeliveryRecorder(recorder);
        gateway.setBackoffSleeper(sleeper);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> gateway.sendTemplate(TO, "verification_code", Map.of("code", "123456")));

        assertEquals(503, ex.getCode());
        assertEquals(4, attempts.get(), "MAX_RETRIES=3 → 最多 4 次尝试");
        assertEquals(List.of(200L, 600L, 1800L), sleeper.delays);
        EmailDeliveryRecorder.EmailDeliveryRecord record = recorder.records.get(0);
        assertEquals(EmailDeliveryRecorder.EmailDeliveryRecord.STATUS_FAILED, record.status());
        assertTrue(record.failureReason().contains("attempts=4"));
        assertTrue(record.failureReason().contains("httpStatus=429"));
    }

    @Test
    @DisplayName("缺变量兜底：模板引用但未提供的变量渲染为 “—”，其余变量正常，日志记录缺失变量名")
    void missingVariablesRenderAsEmDashAndAreRecorded() {
        RecordingRecorder recorder = new RecordingRecorder();
        List<String> sentHtml = new ArrayList<>();

        ConfigurableEmailGateway gateway = new ConfigurableEmailGateway(
                EmailReliabilityTest::enabledSmtp,
                code -> template()) {
            @Override
            void sendViaProvider(EmailSettings settings, String to,
                                 EmailTemplateRenderer.RenderedEmail email, String provider) {
                sentHtml.add(email.html());
            }
        };
        gateway.setDeliveryRecorder(recorder);

        // 模板声明/引用了 action，但调用方只传 code
        gateway.sendTemplate(TO, "verification_code", Map.of("code", "123456"));

        assertEquals(1, sentHtml.size());
        String html = sentHtml.get(0);
        assertTrue(html.contains("123456"));
        assertTrue(html.contains("—"), "缺失变量必须渲染为 em dash，不能留 {{action}}");
        assertFalse(html.contains("{{action}}"), "绝不能把裸占位符发给用户");

        EmailDeliveryRecorder.EmailDeliveryRecord record = recorder.records.get(0);
        assertEquals(0, record.placeholdersLeft(), "渲染后不应残留 {{ 占位符");
        assertTrue(record.missingVariables().contains("action"), "缺失变量名要进投递日志");
    }

    @Test
    @DisplayName("变量名只落名不落值：日志中不出现验证码值")
    void recordKeepsVariableNamesOnly() {
        RecordingRecorder recorder = new RecordingRecorder();
        ConfigurableEmailGateway gateway = new ConfigurableEmailGateway(
                EmailReliabilityTest::enabledSmtp,
                code -> template()) {
            @Override
            void sendViaProvider(EmailSettings settings, String to,
                                 EmailTemplateRenderer.RenderedEmail email, String provider) {
            }
        };
        gateway.setDeliveryRecorder(recorder);

        gateway.sendTemplate(TO, "verification_code", Map.of("code", "051430"));

        EmailDeliveryRecorder.EmailDeliveryRecord record = recorder.records.get(0);
        assertTrue(record.variableKeys().contains("code"));
        assertFalse(record.variableKeys().contains("051430"), "变量值不得进入 variableKeys");
        assertFalse(String.valueOf(record.failureReason()).contains("051430"));
    }
}
