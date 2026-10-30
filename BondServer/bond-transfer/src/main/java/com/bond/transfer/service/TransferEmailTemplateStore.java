package com.bond.transfer.service;

import com.bond.common.email.EmailTemplateStore;
import com.bond.common.entity.EmailTemplate;
import com.bond.transfer.mapper.EmailTemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * bond-transfer 的模板读取（task-63）：供 {@code ConfigurableEmailGateway} 渲染/发送使用。
 */
@Component
@RequiredArgsConstructor
public class TransferEmailTemplateStore implements EmailTemplateStore {

    private final EmailTemplateMapper emailTemplateMapper;

    @Override
    public EmailTemplate find(String code) {
        return emailTemplateMapper.selectByCode(code);
    }
}
