package com.bond.auth.email;

import com.bond.auth.mapper.EmailTemplateBindingMapper;
import com.bond.common.email.EmailPurposeStore;
import com.bond.common.entity.EmailTemplateBinding;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * bond-auth 的用途绑定读取（task-76）：注册验证码/重置密码/欢迎邮件按后台绑定选模板。
 */
@Component
@RequiredArgsConstructor
public class AuthEmailPurposeStore implements EmailPurposeStore {

    private final EmailTemplateBindingMapper bindingMapper;

    @Override
    public String boundTemplateCode(String purpose) {
        EmailTemplateBinding binding = bindingMapper.selectByPurpose(purpose);
        return binding == null ? null : binding.getTemplateCode();
    }
}
