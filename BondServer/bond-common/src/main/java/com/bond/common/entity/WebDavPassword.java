package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * WebDAV 应用专用密码（V9）。
 *
 * <p>只存 bcrypt 哈希，明文只在生成时返回一次；吊销为软吊销（{@code revoked_at}），
 * 不物理删除，便于审计与排查。</p>
 */
@Data
@TableName("webdav_passwords")
public class WebDavPassword {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String name;
    private String passwordHash;
    private OffsetDateTime lastUsedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime revokedAt;
}
