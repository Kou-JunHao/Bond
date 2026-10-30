package com.bond.common.dto;

import com.bond.common.entity.AdminLoginLog;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 管理端登录日志条目（task-144）。
 *
 * <p>字段与 {@code admin_login_logs} 表一致，另加 {@code blockedNow}：
 * 该条目对应 IP 当前是否处于封禁期（真实数据来自 admin_login_blocks，不是猜的）。</p>
 */
@Data
public class AdminLoginLogDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String username;
    private String ip;
    private String userAgent;
    private String outcome;
    private String reason;
    private Integer attemptNo;
    private Long createdAtMillis;

    /** 该 IP 当前是否在封禁期。 */
    private boolean blockedNow;

    public static AdminLoginLogDTO of(AdminLoginLog row, boolean blockedNow) {
        AdminLoginLogDTO dto = new AdminLoginLogDTO();
        dto.setId(row.getId());
        dto.setUserId(row.getUserId());
        dto.setUsername(row.getUsername());
        dto.setIp(row.getIp());
        dto.setUserAgent(row.getUserAgent());
        dto.setOutcome(row.getOutcome());
        dto.setReason(row.getReason());
        dto.setAttemptNo(row.getAttemptNo());
        dto.setCreatedAtMillis(toMillis(row.getCreatedAt()));
        dto.setBlockedNow(blockedNow);
        return dto;
    }

    static Long toMillis(OffsetDateTime time) {
        return time == null ? null : time.toInstant().toEpochMilli();
    }
}
