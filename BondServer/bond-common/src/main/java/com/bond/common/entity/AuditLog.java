package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 管理操作审计日志（V15，任务单 #19）。
 *
 * <p>只追加：表里没有删除入口，接口也不提供 DELETE。{@code actorName} 是冗余快照，
 * 用户改名/删号后历史记录仍可读。历史（V15 上线前）的管理操作没有记录，属预期。</p>
 */
@Data
@TableName("audit_logs")
public class AuditLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long actorId;

    private String actorName;

    private String action;

    private String targetType;

    private Long targetId;

    /** JSONB，存过滤后的上下文（不含敏感值）。 */
    private String detail;

    private String ip;

    private OffsetDateTime createdAt;
}
