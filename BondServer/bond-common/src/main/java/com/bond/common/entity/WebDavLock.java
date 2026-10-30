package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * WebDAV 排他写锁（task-126）。
 *
 * <p>锁按 {@code resource_path} 唯一，{@code token} 为客户端拿到的
 * {@code opaquelocktoken:...}；{@code depth} 取 {@code 0} 或 {@code infinity}。</p>
 */
@Data
@TableName("webdav_locks")
public class WebDavLock {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String token;

    private String resourcePath;

    private String owner;

    private String depth;

    /** 锁到期时间；到期后视为不存在并清理。 */
    private OffsetDateTime timeoutAt;

    private OffsetDateTime createdAt;
}
