package com.bond.auth.service;

import com.bond.common.email.EmailSettings;
import com.bond.common.email.EmailSettingsProvider;
import com.bond.common.email.EmailSettingsResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * bond-auth 的发信配置读取（task-63 v3）：从本服务的 system_configs 缓存取 {@code email.*}，
 * 交给公共解析器做「DB → 环境变量 → 默认(off)」优先级合并，并解密 SMTP 口令 / Resend Key。
 *
 * <p>默认 provider=off：未在后台配置前，发信接口统一返回 503「发信服务未配置」。</p>
 */
@Component
@RequiredArgsConstructor
public class AuthEmailSettingsProvider implements EmailSettingsProvider {

    private final ConfigService configService;

    @Override
    public EmailSettings load() {
        return EmailSettingsResolver.resolve(configService::get);
    }
}
