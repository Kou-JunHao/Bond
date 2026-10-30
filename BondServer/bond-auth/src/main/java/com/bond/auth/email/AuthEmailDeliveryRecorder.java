package com.bond.auth.email;

import com.bond.auth.mapper.EmailDeliveryLogMapper;
import com.bond.common.email.EmailDeliveryLogs;
import com.bond.common.email.EmailDeliveryRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * bond-auth 的投递日志落库实现（task-80）。
 *
 * <p>由 {@code ConfigurableEmailGateway} 在成功/失败后调用；本类允许抛异常，
 * 网关会捕获并 WARN，保证写库故障不影响发信。</p>
 */
@Component
@RequiredArgsConstructor
public class AuthEmailDeliveryRecorder implements EmailDeliveryRecorder {

    private final EmailDeliveryLogMapper emailDeliveryLogMapper;

    @Override
    public void record(EmailDeliveryRecord record) {
        emailDeliveryLogMapper.insert(EmailDeliveryLogs.toEntity(record));
    }
}
