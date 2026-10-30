package com.bond.common.email;

import java.util.List;

/**
 * 邮件投递记录回调（task-80 引入，task-129 增加缺失变量名）。
 *
 * <p>由 {@link ConfigurableEmailGateway} 在发送成功/失败后调用。实现方写库，
 * <b>任何写库异常都必须由网关吞掉</b>，绝不能因为“记日志失败”影响发信。</p>
 */
public interface EmailDeliveryRecorder {

    /** 记录一条投递结果。实现方不应抛出异常（网关仍会兜底）。 */
    void record(EmailDeliveryRecord record);

    /**
     * 单条投递记录。
     *
     * <p>安全口径：{@code toMasked} 只允许掩码；{@code variableKeys}/{@code missingVariables}
     * 只允许变量<b>名</b>；变量值（验证码、临时密码等）绝不进入本对象。</p>
     *
     * @param variableKeys     实际提供的变量名（升序）
     * @param missingVariables 模板引用了但未提供/为空的变量名（升序；渲染为 “—”，task-129）
     */
    record EmailDeliveryRecord(Long userId,
                               String toMasked,
                               String purpose,
                               String templateCode,
                               String subject,
                               String status,
                               String failureReason,
                               List<String> variableKeys,
                               List<String> missingVariables,
                               int placeholdersLeft,
                               String provider,
                               int durationMs,
                               int attempts,
                               String resendPayload) {

        public static final String STATUS_SENT = "sent";
        public static final String STATUS_FAILED = "failed";
    }
}
