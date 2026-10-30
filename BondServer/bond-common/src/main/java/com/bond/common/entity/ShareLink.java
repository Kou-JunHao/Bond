package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 分享链接（公网匿名下载入口）。
 *
 * <p>token 为 32 字节 SecureRandom 的 base64url 编码（43 字符），数据库唯一索引兜底；
 * 提取码只存 HMAC 摘要（code_hash），明文不落库。下载次数仅用于次数限制/审计，
 * 计费口径是 {@code usage_counters.relay_bytes_down}。</p>
 */
@Data
@TableName("share_links")
public class ShareLink {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String token;
    private Long fileId;
    private Long workspaceId;
    private Long creatorId;
    private Boolean requiresCode;
    private String codeHash;
    private Integer failedAttempts;
    private OffsetDateTime codeLockedUntil;
    private OffsetDateTime expiresAt;
    private Integer maxDownloads;
    private Integer downloadCount;
    private Integer status;
    /** 最近一次成功开始下载的时间（task-163；纯 Range 续传也 touch）。 */
    private OffsetDateTime lastAccessAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
