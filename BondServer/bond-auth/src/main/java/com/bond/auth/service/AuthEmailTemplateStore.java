package com.bond.auth.service;

import com.bond.auth.mapper.EmailTemplateMapper;
import com.bond.common.email.EmailTemplateStore;
import com.bond.common.entity.EmailTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * bond-auth 的模板读取（task-63）：查 DB {@code email_templates}，没有则返回 null，
 * 由 {@code ConfigurableEmailGateway} 回退内置默认模板。
 */
@Component
@RequiredArgsConstructor
public class AuthEmailTemplateStore implements EmailTemplateStore {

    private final EmailTemplateMapper emailTemplateMapper;

    @Override
    public EmailTemplate find(String code) {
        return emailTemplateMapper.selectByCode(code);
    }
}
