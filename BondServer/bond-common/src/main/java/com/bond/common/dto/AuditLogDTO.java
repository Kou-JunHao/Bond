package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 管理操作审计日志输出（任务单 #19，GET /api/admin/audit-logs）。
 *
 * <p>{@code detail} 是服务端过滤后的 JSON 字符串（可能为 null），不包含密码/令牌等敏感值。</p>
 */
@Data
public class AuditLogDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long actorId;

    private String actorName;

    private String action;

    private String targetType;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long targetId;

    private String detail;

    private String ip;

    private Long createdAtMillis;
}
