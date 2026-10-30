package com.bond.transfer.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 管理端「邮件投递日志」列表项（task-80）。
 *
 * <p>只暴露非敏感列：收件人为掩码、变量仅名称列表；正文/变量值不在表内，也不会出现在这里。</p>
 */
public record EmailDeliveryDTO(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        String toMasked,
        String purpose,
        String templateCode,
        String subject,
        String status,
        String failureReason,
        List<String> variableKeys,
        Integer placeholdersLeft,
        String provider,
        Integer durationMs,
        Integer attempts,
        String lastError,
        OffsetDateTime createdAt) {
}
