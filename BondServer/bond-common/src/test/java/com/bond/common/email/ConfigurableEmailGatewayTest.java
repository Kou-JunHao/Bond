package com.bond.common.email;

import com.bond.common.entity.EmailTemplate;
import com.bond.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行时发信网关（task-63）：provider=off 默认拒绝；60s 缓存与 invalidate；
 * 模板 DB 优先、disabled/缺失回退内置；掩码。
 */
class ConfigurableEmailGatewayTest {

    private static EmailTemplate template(String code, String subject, boolean enabled) {
        EmailTemplate t = new EmailTemplate();
        t.setCode(code);
        t.setSubject(subject);
        t.setBodyHtml("<p>{{code}}</p>");
        t.setBodyText("{{code}}");
        t.setEnabled(enabled);
        return t;
    }

    @Test
    @DisplayName("默认 provider=off：isEnabled=false，send/sendTemplate 均 503「发信服务未配置」")
    void offByDefaultRejectsAllSends() {
        ConfigurableEmailGateway gateway = new ConfigurableEmailGateway(
                EmailSettings::defaults, code -> null);

        assertFalse(gateway.isEnabled());
        BusinessException send = assertThrows(BusinessException.class,
                () -> gateway.send("to@example.com", "subject", "text"));
        assertEquals(503, send.getCode());
        BusinessException template = assertThrows(BusinessException.class,
                () -> gateway.sendTemplate("to@example.com", BuiltInEmailTemplates.WELCOME, null));
        assertEquals(503, template.getCode());
    }

    @Test
    @DisplayName("设置缓存 60s：重复读取只查一次；invalidate 后重新读")
    void settingsCachedThenInvalidated() {
        AtomicInteger loads = new AtomicInteger();
        ConfigurableEmailGateway gateway = new ConfigurableEmailGateway(
                () -> {
                    loads.incrementAndGet();
                    return EmailSettings.defaults();
                }, code -> null);

        gateway.settings();
        gateway.settings();
        assertEquals(1, loads.get());

        gateway.invalidate();
        gateway.settings();
        assertEquals(2, loads.get());
    }

    @Test
    @DisplayName("模板查找：DB 优先")
    void lookupTemplatePrefersDatabase() {
        ConfigurableEmailGateway gateway = new ConfigurableEmailGateway(
                EmailSettings::defaults,
                code -> template(code, "DB 模板 {{code}}", true));

        EmailTemplate found = gateway.lookupTemplate(BuiltInEmailTemplates.VERIFICATION_CODE);

        assertEquals("DB 模板 {{code}}", found.getSubject());
    }

    @Test
    @DisplayName("DB 模板 disabled → 回退内置默认")
    void disabledTemplateFallsBackToBuiltin() {
        ConfigurableEmailGateway gateway = new ConfigurableEmailGateway(
                EmailSettings::defaults,
                code -> template(code, "停用的 DB 模板", false));

        EmailTemplate found = gateway.lookupTemplate(BuiltInEmailTemplates.VERIFICATION_CODE);

        assertNotNull(found);
        assertFalse("停用的 DB 模板".equals(found.getSubject()));
        assertTrue(found.getBodyHtml().contains("{{code}}"));
    }

    @Test
    @DisplayName("DB 查库异常 → 回退内置；确实不存在 → 500 配置缺陷")
    void storeFailureFallsBackButUnknownThrows() {
        ConfigurableEmailGateway throwing = new ConfigurableEmailGateway(
                EmailSettings::defaults,
                code -> {
                    throw new IllegalStateException("db down");
                });
        assertNotNull(throwing.lookupTemplate(BuiltInEmailTemplates.WELCOME));

        ConfigurableEmailGateway gateway = new ConfigurableEmailGateway(
                EmailSettings::defaults, code -> null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> gateway.lookupTemplate("no_such_template"));
        assertEquals(500, ex.getCode());
    }

    @Test
    @DisplayName("收件人掩码只露首字符与域名")
    void maskRecipient() {
        assertEquals("a***@example.com", ConfigurableEmailGateway.mask("alice@example.com"));
        assertEquals("***", ConfigurableEmailGateway.mask(""));
        assertEquals("***", ConfigurableEmailGateway.mask("no-at-sign"));
    }
}
