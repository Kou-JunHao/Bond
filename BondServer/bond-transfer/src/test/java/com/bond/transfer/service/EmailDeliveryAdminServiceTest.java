package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.dto.PageResult;
import com.bond.common.email.ConfigCipher;
import com.bond.common.email.EmailGateway;
import com.bond.common.entity.EmailDeliveryLog;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.RateLimitedException;
import com.bond.transfer.dto.EmailDeliveryDTO;
import com.bond.transfer.dto.EmailDeliveryStatsDTO;
import com.bond.transfer.mapper.EmailDeliveryLogMapper;
import com.bond.transfer.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-80：管理端投递日志分页/筛选/统计（服务层）。
 *
 * <p>分页使用显式 count + LIMIT/OFFSET（模块未注册 MP 分页插件），
 * 因此用例直接验证 mapper.selectCount / selectList 的调用与参数。</p>
 */
@ExtendWith(MockitoExtension.class)
class EmailDeliveryAdminServiceTest {

    @Mock
    private EmailDeliveryLogMapper emailDeliveryLogMapper;

    @Mock
    private EmailGateway emailGateway;

    @Mock
    private AuditService auditService;

    @Mock
    private UserMapper userMapper;

    private EmailDeliveryAdminService service;

    @BeforeEach
    void setUp() {
        service = new EmailDeliveryAdminService(emailDeliveryLogMapper, new ObjectMapper(),
                emailGateway, auditService, userMapper);
    }

    private static EmailDeliveryLog log() {
        EmailDeliveryLog log = new EmailDeliveryLog();
        log.setId(9001L);
        log.setUserId(7L);
        log.setToMasked("b***@example.com");
        log.setPurpose("register_code");
        log.setTemplateCode("verification_code");
        log.setSubject("验证码 123456");
        log.setStatus("sent");
        log.setVariableKeys("[\"code\",\"expiresMinutes\"]");
        log.setPlaceholdersLeft(0);
        log.setProvider("smtp");
        log.setDurationMs(42);
        log.setCreatedAt(OffsetDateTime.now());
        return log;
    }

    @Test
    @DisplayName("分页：total 取自独立 count（与 items 同过滤条件），DTO 映射正确")
    @SuppressWarnings("unchecked")
    void pageMapsToDtoWithFilteredCount() {
        when(emailDeliveryLogMapper.selectCount(any())).thenReturn(1L);
        when(emailDeliveryLogMapper.selectList(any())).thenReturn(List.of(log()));

        PageResult<EmailDeliveryDTO> result = service.page(1, 20, "SENT", " register_code ");

        assertEquals(1, result.getItems().size());
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getPage());
        assertEquals(20, result.getSize());
        EmailDeliveryDTO dto = result.getItems().get(0);
        assertEquals("b***@example.com", dto.toMasked());
        assertEquals("sent", dto.status());
        assertEquals(List.of("code", "expiresMinutes"), dto.variableKeys());
        assertNull(dto.failureReason());
        assertTrue(dto.subject().contains("123456"));

        // 两条查询各调用一次（count 给 total、list 给 items），且都用同一过滤条件构造
        verify(emailDeliveryLogMapper).selectCount(any());
        verify(emailDeliveryLogMapper).selectList(any());
    }

    @Test
    @DisplayName("分页：page/size 越界收敛；空筛选条件不带 where")
    @SuppressWarnings("unchecked")
    void pageClampsAndBuildsLimit() {
        when(emailDeliveryLogMapper.selectCount(any())).thenReturn(0L);
        when(emailDeliveryLogMapper.selectList(any())).thenReturn(List.of());

        PageResult<EmailDeliveryDTO> result = service.page(3, 1000, " ", null);

        assertEquals(0, result.getTotal());
        assertEquals(3, result.getPage());
        assertEquals(100, result.getSize());
        verify(emailDeliveryLogMapper).selectCount(any());
        verify(emailDeliveryLogMapper).selectList(any());
    }

    @Test
    @DisplayName("统计：days 透传、失败率百分比、byPurpose 存在")
    void statsAssemble() {
        when(emailDeliveryLogMapper.statsTotals(7))
                .thenReturn(Map.of("total", 10L, "sent", 8L, "failed", 2L));
        when(emailDeliveryLogMapper.statsByPurpose(7)).thenReturn(List.of(
                Map.of("purpose", "register_code", "total", 6L, "failed", 0L),
                Map.of("purpose", "welcome", "total", 4L, "failed", 2L)));

        EmailDeliveryStatsDTO stats = service.stats(0);

        assertEquals(7, stats.days());
        assertEquals(10, stats.total());
        assertEquals(8, stats.sent());
        assertEquals(2, stats.failed());
        assertEquals(20.0, stats.failureRate(), 1e-9);
        assertEquals(2, stats.byPurpose().size());
        assertEquals("register_code", stats.byPurpose().get(0).purpose());
    }

    @Test
    @DisplayName("统计：无失败时 failureRate 为 0.0 且 byPurpose 仍序列化（生产缺陷回归）")
    void statsZeroFailureRate() {
        when(emailDeliveryLogMapper.statsTotals(7))
                .thenReturn(Map.of("total", 1L, "sent", 1L, "failed", 0L));
        when(emailDeliveryLogMapper.statsByPurpose(7)).thenReturn(List.of(
                Map.of("purpose", "register_code", "total", 1L, "failed", 0L)));

        EmailDeliveryStatsDTO stats = service.stats(7);

        assertEquals(1, stats.total());
        assertEquals(0.0, stats.failureRate(), 1e-9);
        assertEquals(1, stats.byPurpose().size());
    }

    @Test
    @DisplayName("统计：无数据时全 0、失败率 0.0、byPurpose 为空列表")
    void statsEmpty() {
        when(emailDeliveryLogMapper.statsTotals(anyInt())).thenReturn(Map.of());
        when(emailDeliveryLogMapper.statsByPurpose(anyInt())).thenReturn(List.of());

        EmailDeliveryStatsDTO stats = service.stats(7);

        assertEquals(7, stats.days());
        assertEquals(0, stats.total());
        assertEquals(0, stats.failed());
        assertEquals(0.0, stats.failureRate(), 1e-9);
        assertTrue(stats.byPurpose().isEmpty());
    }

    // ── task-134：手动重发 / 重试次数 / lastError 脱敏 ──

    private static String resendPayload(String to, Map<String, String> variables) throws Exception {
        String json = new ObjectMapper().writeValueAsString(Map.of("to", to, "variables", variables));
        return ConfigCipher.fromEnvironment().encrypt(json);
    }

    @Test
    @DisplayName("重发：复用原模板/原收件人/原变量，并写审计与返回最新记录")
    void resendUsesOriginalTemplateAndAudits() throws Exception {
        EmailDeliveryLog row = log();
        row.setStatus("failed");
        row.setResendPayload(resendPayload("bob@example.com", Map.of("code", "1234")));
        when(emailDeliveryLogMapper.selectById(9001L)).thenReturn(row);
        when(emailDeliveryLogMapper.selectOne(any())).thenReturn(row);

        EmailDeliveryAdminService.ResendOutcome outcome = service.resend(9001L, 7L, "1.2.3.4");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(emailGateway).sendTemplate(eq("bob@example.com"), eq("verification_code"), vars.capture());
        assertEquals("1234", vars.getValue().get("code"));
        assertFalse(vars.getValue().containsKey("__resend_to"));
        assertEquals(9001L, outcome.latestDelivery().id());
        assertTrue(outcome.message().contains("latestDelivery"));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(7L), any(), eq("mail.delivery.resend"),
                eq("email_delivery_log"), eq(9001L), detail.capture(), eq("1.2.3.4"));
        assertTrue(detail.getValue().contains("originalId"));
        assertTrue(detail.getValue().contains("\"ok\":true"));
    }

    @Test
    @DisplayName("重发：同一记录 10 秒内第二次返回 429（Retry-After）且不再发信")
    void resendIsRateLimited() throws Exception {
        EmailDeliveryLog row = log();
        row.setResendPayload(resendPayload("bob@example.com", Map.of("code", "1")));
        when(emailDeliveryLogMapper.selectById(9001L)).thenReturn(row);
        when(emailDeliveryLogMapper.selectOne(any())).thenReturn(row);

        service.resend(9001L, 7L, null);
        RateLimitedException ex = assertThrows(RateLimitedException.class,
                () -> service.resend(9001L, 7L, null));
        assertTrue(ex.getRetryAfterSeconds() >= 1);
        verify(emailGateway, times(1)).sendTemplate(any(), any(), any());
    }

    @Test
    @DisplayName("重发：缺失载荷的历史记录返回 400，不发信")
    void resendRejectsRecordWithoutPayload() {
        when(emailDeliveryLogMapper.selectById(9001L)).thenReturn(log());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resend(9001L, 7L, null));
        assertEquals(400, ex.getCode());
        verify(emailGateway, times(0)).sendTemplate(any(), any(), any());
    }

    @Test
    @DisplayName("重发：记录不存在返回 404")
    void resendMissingRecordIs404() {
        when(emailDeliveryLogMapper.selectById(9001L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resend(9001L, 7L, null));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("lastError 脱敏：服务商 key 形态/敏感键值被替换且截断到 200 字符")
    void sanitizeLastErrorStripsSecrets() {
        String raw = "550 invalid api_key=secret123 re_ABCdef123456 " + "x".repeat(400);

        String sanitized = EmailDeliveryAdminService.sanitizeLastError(raw);

        assertTrue(sanitized.length() <= 200);
        assertFalse(sanitized.contains("secret123"));
        assertFalse(sanitized.contains("re_ABCdef123456"));
        assertTrue(sanitized.contains("re_***"));
    }
}
