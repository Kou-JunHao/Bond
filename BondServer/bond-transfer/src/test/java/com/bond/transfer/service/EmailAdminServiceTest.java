package com.bond.transfer.service;

import com.bond.common.email.ConfigurableEmailGateway;
import com.bond.common.email.EmailSendException;
import com.bond.common.email.EmailSettings;
import com.bond.common.entity.EmailTemplate;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.EmailTemplateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台邮件设置/模板管理（task-63）：口令不回显、加密落库、空值保持原值、
 * 自检错误透出、模板校验/重置/预览。
 */
@ExtendWith(MockitoExtension.class)
class EmailAdminServiceTest {

    private static final Long ADMIN = 7L;

    @Mock
    private ConfigService configService;
    @Mock
    private EmailTemplateMapper emailTemplateMapper;
    @Mock
    private com.bond.transfer.mapper.EmailTemplateBindingMapper bindingMapper;
    @Mock
    private TransferEmailSettingsProvider settingsProvider;
    @Mock
    private ConfigurableEmailGateway gateway;

    private EmailAdminService service() {
        return new EmailAdminService(configService, emailTemplateMapper, bindingMapper, settingsProvider,
                gateway, new ObjectMapper());
    }

    private static EmailSettings smtpSettings() {
        return new EmailSettings("smtp", "verify@example.com", "Bond",
                new EmailSettings.Smtp("smtp.example.com", 465, "user@example.com", "topsecret", true, false, true, 5),
                "", "https://api.resend.com", "");
    }

    private static EmailSettings resendSettings() {
        return new EmailSettings("resend", "verify@example.com", "Bond",
                new EmailSettings.Smtp("", 587, "", "", true, false, false, 5),
                "re_live_key", "https://api.resend.com", "");
    }

    @BeforeEach
    void stubConfig() {
        lenient().when(configService.get(anyString())).thenReturn("");
        lenient().when(configService.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    @DisplayName("GET 设置：口令/Key 只回显是否已设置，响应里不出现密文或明文")
    void settingsNeverReturnsSecrets() {
        when(settingsProvider.load()).thenReturn(smtpSettings());
        when(configService.get("email.smtp.password")).thenReturn("enc:v1:0123456789abcdef");
        when(configService.get("email.resend.api_key")).thenReturn("enc:v1:apiKeyCipher");

        Map<String, Object> body = service().settings();

        @SuppressWarnings("unchecked")
        Map<String, Object> smtp = (Map<String, Object>) body.get("smtp");
        assertEquals(true, smtp.get("passwordSet"));
        assertFalse(smtp.containsKey("password"));
        assertEquals("smtp", body.get("effectiveProvider"));
        assertEquals(true, body.get("configured"));

        String json = body.toString();
        assertFalse(json.contains("enc:v1:"));
        assertFalse(json.contains("topsecret"));
    }

    @Test
    @DisplayName("PUT 设置：非空口令 AES-GCM 加密落库（enc:v1: 前缀），保存后 invalidate 缓存")
    void updateEncryptsPassword() {
        when(settingsProvider.load()).thenReturn(smtpSettings());

        Map<String, Object> smtp = new LinkedHashMap<>();
        smtp.put("host", "smtp.example.com");
        smtp.put("port", 465);
        smtp.put("username", "user@example.com");
        smtp.put("password", "new-plain-password");
        smtp.put("auth", true);
        smtp.put("ssl", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", "smtp");
        body.put("smtp", smtp);

        service().updateSettings(body, ADMIN);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(configService).set(eq("email.smtp.password"), value.capture(), eq(ADMIN));
        assertTrue(value.getValue().startsWith("enc:v1:"));
        assertFalse(value.getValue().contains("new-plain-password"));
        verify(gateway).invalidate();
    }

    @Test
    @DisplayName("PUT 设置：口令传空 / 掩码值 = 保持原值，不重写该行")
    void blankPasswordKeepsExisting() {
        when(settingsProvider.load()).thenReturn(smtpSettings());

        Map<String, Object> smtp = new LinkedHashMap<>();
        smtp.put("host", "smtp.example.com");
        smtp.put("port", 465);
        smtp.put("username", "user@example.com");
        smtp.put("password", "****cret");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", "smtp");
        body.put("smtp", smtp);

        service().updateSettings(body, ADMIN);

        verify(configService, never()).set(eq("email.smtp.password"), anyString(), anyLong());
    }

    @Test
    @DisplayName("自检失败：把下游真实错误摘要以 400 返回，且不含口令")
    void sendTestSurfacesDownstreamError() {
        when(settingsProvider.load()).thenReturn(resendSettings());
        doThrow(new EmailSendException("SMTP 550 mailbox unavailable", null))
                .when(gateway).send(anyString(), anyString(), any(), anyString());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().sendTest("admin@example.com", ADMIN));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("SMTP 550"));
        assertFalse(ex.getMessage().contains("re_live_key"));
    }

    @Test
    @DisplayName("自检邮件：text 与品牌化 html 都非空，不含凭据/口令（task-69）")
    void sendTestBuildsTextAndBrandedHtml() {
        when(settingsProvider.load()).thenReturn(resendSettings());

        service().sendTest("admin@example.com", ADMIN);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(gateway).send(eq("admin@example.com"), eq("Bond 邮件配置自检"), html.capture(), text.capture());

        assertFalse(html.getValue().isBlank(), "html 不能为空");
        assertFalse(text.getValue().isBlank(), "text 不能为空");
        assertTrue(html.getValue().contains("#E57153"), "应为品牌色 HTML");
        assertTrue(html.getValue().contains("邮件配置自检"));
        assertTrue(html.getValue().contains("SMTP 主机"));
        assertTrue(text.getValue().contains("发送时间"));
        assertTrue(text.getValue().contains("provider"));
        assertFalse(html.getValue().contains("re_live_key"));
        assertFalse(html.getValue().toLowerCase(java.util.Locale.ROOT).contains("password"));
    }

    @Test
    @DisplayName("模板：不存在 404；主题为空 400；正常更新记录 updatedBy")
    void templateValidationAndUpdate() {
        when(emailTemplateMapper.selectByCode("nope")).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service().getTemplate("nope")).getCode());

        EmailTemplate existing = new EmailTemplate();
        existing.setCode("verification_code");
        existing.setName("验证码");
        existing.setSubject("原主题");
        existing.setIsBuiltin(true);
        existing.setVariables("[\"code\"]");
        when(emailTemplateMapper.selectByCode("verification_code")).thenReturn(existing);

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("subject", "   ");
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service().updateTemplate("verification_code", bad, ADMIN)).getCode());

        Map<String, Object> good = new LinkedHashMap<>();
        good.put("subject", "新主题 {{code}}");
        good.put("enabled", true);
        service().updateTemplate("verification_code", good, ADMIN);

        ArgumentCaptor<EmailTemplate> captor = ArgumentCaptor.forClass(EmailTemplate.class);
        // task-71：variables 是 jsonb，必须走手写 updateTemplate（#{variables}::jsonb），不能 updateById
        verify(emailTemplateMapper).updateTemplate(captor.capture());
        assertEquals(ADMIN, captor.getValue().getUpdatedBy());
        assertTrue(captor.getValue().getSubject().contains("新主题"));
        assertEquals("[\"code\"]", captor.getValue().getVariables(), "未传 variables 时保留原值");
    }

    @Test
    @DisplayName("task-71：更新 variables 列表 → 捕获的 JSON 字符串正确（入库走 ::jsonb 手写 SQL）")
    void updateTemplateVariablesStoredAsJson() {
        EmailTemplate existing = new EmailTemplate();
        existing.setCode("verification_code");
        existing.setSubject("主题");
        existing.setIsBuiltin(true);
        existing.setVariables("[]");
        when(emailTemplateMapper.selectByCode("verification_code")).thenReturn(existing);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("variables", List.of("code", "username", "expiresMinutes"));
        service().updateTemplate("verification_code", body, ADMIN);

        ArgumentCaptor<EmailTemplate> captor = ArgumentCaptor.forClass(EmailTemplate.class);
        verify(emailTemplateMapper).updateTemplate(captor.capture());
        assertEquals("[\"code\",\"username\",\"expiresMinutes\"]", captor.getValue().getVariables());
        verify(emailTemplateMapper, never()).updateById(any(EmailTemplate.class));
    }

    @Test
    @DisplayName("task-71：Mapper 的写 SQL 显式 ::jsonb（防回退成 varchar 绑定）")
    void mapperSqlCastsVariablesToJsonb() throws Exception {
        String update = String.join("\n", com.bond.transfer.mapper.EmailTemplateMapper.class
                .getMethod("updateTemplate", EmailTemplate.class)
                .getAnnotation(org.apache.ibatis.annotations.Update.class).value());
        String insert = String.join("\n", com.bond.transfer.mapper.EmailTemplateMapper.class
                .getMethod("insertTemplate", EmailTemplate.class)
                .getAnnotation(org.apache.ibatis.annotations.Insert.class).value());
        assertTrue(update.contains("variables = #{variables}::jsonb"));
        assertTrue(insert.contains("#{variables}::jsonb"));
    }

    @Test
    @DisplayName("模板 reset：恢复内置默认内容（DB 行更新）")
    void resetRestoresBuiltin() {
        EmailTemplate customized = new EmailTemplate();
        customized.setCode("verification_code");
        customized.setName("改坏的名字");
        customized.setSubject("改坏的主题");
        customized.setIsBuiltin(false);
        when(emailTemplateMapper.selectByCode("verification_code")).thenReturn(customized);

        service().resetTemplate("verification_code", ADMIN);

        ArgumentCaptor<EmailTemplate> captor = ArgumentCaptor.forClass(EmailTemplate.class);
        verify(emailTemplateMapper).updateTemplate(captor.capture());
        EmailTemplate restored = captor.getValue();
        assertEquals(com.bond.common.email.BuiltInEmailTemplates.get("verification_code").getSubject(),
                restored.getSubject());
        assertEquals(true, restored.getIsBuiltin());
        assertEquals(ADMIN, restored.getUpdatedBy());
    }

    @Test
    @DisplayName("preview：DB 无该模板时回退内置，渲染变量且不写库")
    void previewFallsBackToBuiltin() {
        when(emailTemplateMapper.selectByCode("verification_code")).thenReturn(null);

        Map<String, Object> rendered = service().preview("verification_code",
                Map.of("code", "123456", "username", "alice"), null);

        assertTrue(String.valueOf(rendered.get("html")).contains("123456"));
        assertTrue(String.valueOf(rendered.get("subject")).contains("Bond"));
        verify(emailTemplateMapper, never()).updateTemplate(any(EmailTemplate.class));
    }

    @Test
    @DisplayName("模板列表带上内置标记与空列表兜底")
    void listTemplates() {
        when(emailTemplateMapper.selectAll()).thenReturn(List.of());
        assertNull(service().listTemplates().stream().findFirst().orElse(null));
    }

    // ── task-76：方案 B（自定义模板 + 用途绑定）──

    @Test
    @DisplayName("新增自定义模板：后端自动生成 custom_ code、自动解析 variables、内置标记 false")
    void createTemplateGeneratesCodeAndVariables() throws Exception {
        java.util.concurrent.atomic.AtomicReference<EmailTemplate> inserted =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            inserted.set(inv.getArgument(0));
            return 1;
        }).when(emailTemplateMapper).insertTemplate(any(EmailTemplate.class));
        when(emailTemplateMapper.selectByCode(anyString()))
                .thenAnswer(inv -> inserted.get());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "自定义通知");
        body.put("subject", "你好 {{username}}");
        body.put("bodyHtml", "<p>{{code}} {{unknownVar}}</p>");

        Map<String, Object> created = service().createTemplate(body, ADMIN);

        org.junit.jupiter.api.Assertions.assertNotNull(created);
        EmailTemplate saved = inserted.get();
        assertTrue(saved.getCode().startsWith("custom_"));
        assertEquals("你好 {{username}}", saved.getSubject());
        assertEquals(false, saved.getIsBuiltin());
        assertEquals(true, saved.getEnabled());
        assertTrue(saved.getVariables().contains("\"username\""));
        assertTrue(saved.getVariables().contains("\"code\""));
        assertFalse(saved.getVariables().contains("unknownVar"));
        assertEquals(ADMIN, saved.getUpdatedBy());
    }

    @Test
    @DisplayName("新增自定义模板：空请求体 / 缺名称 / 缺主题 → 400")
    void createTemplateValidation() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service().createTemplate(null, ADMIN)).getCode());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "x");
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service().createTemplate(body, ADMIN)).getCode());
    }

    @Test
    @DisplayName("删除模板：内置 409；已绑定用途 400 且不删行")
    void deleteTemplateGuards() {
        EmailTemplate builtin = new EmailTemplate();
        builtin.setCode("verification_code");
        builtin.setIsBuiltin(true);
        when(emailTemplateMapper.selectByCode("verification_code")).thenReturn(builtin);
        assertEquals(409, assertThrows(com.bond.common.exception.ConflictException.class,
                () -> service().deleteTemplate("verification_code", ADMIN)).getCode());

        EmailTemplate custom = new EmailTemplate();
        custom.setCode("custom_ab12cd34");
        custom.setIsBuiltin(false);
        when(emailTemplateMapper.selectByCode("custom_ab12cd34")).thenReturn(custom);
        when(bindingMapper.selectPurposesByTemplate("custom_ab12cd34"))
                .thenReturn(List.of("register_code"));
        BusinessException bound = assertThrows(BusinessException.class,
                () -> service().deleteTemplate("custom_ab12cd34", ADMIN));
        assertEquals(400, bound.getCode());
        assertTrue(bound.getMessage().contains("register_code"));
        verify(emailTemplateMapper, never()).deleteByCode(anyString());
    }

    @Test
    @DisplayName("删除模板：未绑定的自定义模板可删，返回 deleted=true")
    void deleteCustomTemplate() {
        EmailTemplate custom = new EmailTemplate();
        custom.setCode("custom_ab12cd34");
        custom.setIsBuiltin(false);
        when(emailTemplateMapper.selectByCode("custom_ab12cd34")).thenReturn(custom);
        when(bindingMapper.selectPurposesByTemplate("custom_ab12cd34")).thenReturn(List.of());

        Map<String, Object> result = service().deleteTemplate("custom_ab12cd34", ADMIN);

        assertEquals(true, result.get("deleted"));
        verify(emailTemplateMapper).deleteByCode("custom_ab12cd34");
    }

    @Test
    @DisplayName("用途列表：含内置默认模板与 requiredVariables，无绑定则 effective=builtin")
    void purposesList() {
        when(bindingMapper.selectByPurpose(anyString())).thenReturn(null);

        List<Map<String, Object>> list = service().purposes();

        assertEquals(com.bond.common.email.EmailPurpose.all().size(), list.size());
        Map<String, Object> register = list.stream()
                .filter(item -> "register_code".equals(item.get("purpose")))
                .findFirst().orElseThrow();
        assertEquals("verification_code", register.get("builtinTemplateCode"));
        assertEquals("verification_code", register.get("effectiveTemplateCode"));
        assertNull(register.get("boundTemplateCode"));
        assertTrue(register.get("requiredVariables").toString().contains("code"));
    }

    @Test
    @DisplayName("绑定用途：模板禁用/缺必需变量 → warnings 软提示，但仍 upsert")
    void bindPurposeReportsWarnings() {
        EmailTemplate custom = new EmailTemplate();
        custom.setCode("custom_ab12cd34");
        custom.setSubject("你好");
        custom.setBodyHtml("<p>hi</p>");
        custom.setBodyText("hi");
        custom.setEnabled(false);
        when(emailTemplateMapper.selectByCode("custom_ab12cd34")).thenReturn(custom);

        Map<String, Object> result = service().bindPurpose("register_code", "custom_ab12cd34", ADMIN);

        assertEquals("custom_ab12cd34", result.get("boundTemplateCode"));
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.get("warnings");
        assertTrue(warnings.size() >= 2);
        ArgumentCaptor<com.bond.common.entity.EmailTemplateBinding> captor =
                ArgumentCaptor.forClass(com.bond.common.entity.EmailTemplateBinding.class);
        verify(bindingMapper).upsert(captor.capture());
        assertEquals("register_code", captor.getValue().getPurpose());
        assertEquals("custom_ab12cd34", captor.getValue().getTemplateCode());
        assertEquals(ADMIN, captor.getValue().getUpdatedBy());
    }

    @Test
    @DisplayName("解绑用途：templateCode 为 null/空 → 删除绑定、effective 回内置默认、HTTP 200")
    void unbindPurposeFallsBackToBuiltin() {
        Map<String, Object> result = service().bindPurpose("register_code", "  ", ADMIN);

        verify(bindingMapper).deleteByPurpose("register_code");
        assertNull(result.get("boundTemplateCode"));
        assertEquals("verification_code", result.get("effectiveTemplateCode"));
    }

    @Test
    @DisplayName("用途列表 overridden：绑定非内置模板为 true、绑定内置本身/未绑定为 false")
    void purposesListReportsOverridden() {
        com.bond.common.entity.EmailTemplateBinding binding =
                new com.bond.common.entity.EmailTemplateBinding();
        binding.setPurpose("register_code");
        binding.setTemplateCode("custom_24e012ca");
        when(bindingMapper.selectByPurpose(anyString()))
                .thenAnswer(inv -> "register_code".equals(inv.getArgument(0)) ? binding : null);

        List<Map<String, Object>> list = service().purposes();

        Map<String, Object> register = list.stream()
                .filter(item -> "register_code".equals(item.get("purpose")))
                .findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, register.get("overridden"));
        assertEquals("custom_24e012ca", register.get("effectiveTemplateCode"));
        Map<String, Object> passwordReset = list.stream()
                .filter(item -> "password_reset".equals(item.get("purpose")))
                .findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, passwordReset.get("overridden"));
    }

    @Test
    @DisplayName("preview 只给 purpose：按绑定模板解析并渲染")
    void previewByPurposeUsesBoundTemplate() {
        com.bond.common.entity.EmailTemplateBinding binding =
                new com.bond.common.entity.EmailTemplateBinding();
        binding.setPurpose("register_code");
        binding.setTemplateCode("custom_24e012ca");
        when(bindingMapper.selectByPurpose("register_code")).thenReturn(binding);

        EmailTemplate custom = new EmailTemplate();
        custom.setCode("custom_24e012ca");
        custom.setSubject("欢迎 {{username}}");
        custom.setBodyHtml("<p>验证码 {{code}}</p>");
        custom.setBodyText("验证码 {{code}}");
        custom.setEnabled(true);
        when(emailTemplateMapper.selectByCode("custom_24e012ca")).thenReturn(custom);

        Map<String, Object> rendered = service().preview(null,
                Map.of("code", "999999", "username", "alice"), "register_code");

        assertTrue(String.valueOf(rendered.get("html")).contains("999999"));
        assertTrue(String.valueOf(rendered.get("subject")).contains("alice"));
    }

    @Test
    @DisplayName("preview 只给 purpose：绑定缺失时回落内置模板")
    void previewByPurposeFallsBackToBuiltin() {
        when(bindingMapper.selectByPurpose("password_reset")).thenReturn(null);
        when(emailTemplateMapper.selectByCode("password_reset")).thenReturn(null);

        Map<String, Object> rendered = service().preview(null,
                Map.of("code", "123456"), "password_reset");

        assertTrue(String.valueOf(rendered.get("html")).contains("123456"));
        assertTrue(String.valueOf(rendered.get("subject")).contains("Bond"));
    }

    @Test
    @DisplayName("preview 未知 purpose → 400")
    void previewUnknownPurposeReturns400() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().preview(null, Map.of(), "no_such_purpose"));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("preview 什么都不给 → 400")
    void previewWithoutCodeOrPurposeReturns400() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().preview(null, Map.of(), null));
        assertEquals(400, ex.getCode());
    }
}
