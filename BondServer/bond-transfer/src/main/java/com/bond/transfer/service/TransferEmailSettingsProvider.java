package com.bond.transfer.service;

import com.bond.common.email.EmailSettings;
import com.bond.common.email.EmailSettingsProvider;
import com.bond.common.email.EmailSettingsResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * bond-transfer 的发信配置读取（task-63 v3）：DB {@code system_config} 优先，
 * 环境变量兜底，默认 provider=off。
 */
@Component
@RequiredArgsConstructor
public class TransferEmailSettingsProvider implements EmailSettingsProvider {

    private final ConfigService configService;

    @Override
    public EmailSettings load() {
        return EmailSettingsResolver.resolve(configService::get);
    }
}
