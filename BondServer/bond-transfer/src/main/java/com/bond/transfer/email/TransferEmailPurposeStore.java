package com.bond.transfer.email;

import com.bond.common.email.EmailPurposeStore;
import com.bond.common.entity.EmailTemplateBinding;
import com.bond.transfer.mapper.EmailTemplateBindingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * bond-transfer 的用途绑定读取（task-76）：后台改绑后下一次发信即生效（网关每次发信都查）。
 */
@Component
@RequiredArgsConstructor
public class TransferEmailPurposeStore implements EmailPurposeStore {

    private final EmailTemplateBindingMapper bindingMapper;

    @Override
    public String boundTemplateCode(String purpose) {
        EmailTemplateBinding binding = bindingMapper.selectByPurpose(purpose);
        return binding == null ? null : binding.getTemplateCode();
    }
}
