package com.bond.common.dto;

import com.bond.common.entity.AdminLoginBlock;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 管理端登录封禁条目（task-144）。
 *
 * <p>{@code active=true} 表示未解除且未到期；{@code remainingSeconds} 为剩余封禁秒数（已到期/已解除为 0）。</p>
 */
@Data
public class AdminLoginBlockDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** ip / user */
    private String scope;

    /** IP 或用户名（scope 决定含义）。 */
    private String value;

    private String reason;
    private Integer failCount;
    private Long bannedAtMillis;
    private Long expiresAtMillis;
    private Long liftedAtMillis;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long liftedBy;

    private boolean active;
    private long remainingSeconds;

    public static AdminLoginBlockDTO of(AdminLoginBlock row, OffsetDateTime now) {
        AdminLoginBlockDTO dto = new AdminLoginBlockDTO();
        dto.setId(row.getId());
        dto.setScope(row.getScope());
        dto.setValue(row.getValue());
        dto.setReason(row.getReason());
        dto.setFailCount(row.getFailCount());
        dto.setBannedAtMillis(toMillis(row.getBannedAt()));
        dto.setExpiresAtMillis(toMillis(row.getExpiresAt()));
        dto.setLiftedAtMillis(toMillis(row.getLiftedAt()));
        dto.setLiftedBy(row.getLiftedBy());
        boolean active = row.getLiftedAt() == null && row.getExpiresAt() != null && row.getExpiresAt().isAfter(now);
        dto.setActive(active);
        long remaining = 0L;
        if (active) {
            remaining = Math.max(0L, java.time.Duration.between(now, row.getExpiresAt()).getSeconds());
        }
        dto.setRemainingSeconds(remaining);
        return dto;
    }

    private static Long toMillis(OffsetDateTime time) {
        return time == null ? null : time.toInstant().toEpochMilli();
    }
}
