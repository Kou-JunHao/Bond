package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 聊天消息。
 * <p>软删只写 deleted_at；查询一律带 deleted_at IS NULL。正文/文件 id 由 kind 决定。</p>
 */
@Data
@TableName("chat_messages")
public class ChatMessage {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long conversationId;

    private Long senderId;

    /** text / file / system */
    private String kind;

    private String body;

    private Long fileId;

    /** 客户端生成的重试幂等键（同会话唯一，NULL 不约束）。 */
    private String clientMsgId;

    private OffsetDateTime createdAt;

    private OffsetDateTime deletedAt;

    /** 已撤回：正文/文件引用已清空，客户端显示占位。 */
    private Boolean recalled;

    private OffsetDateTime recalledAt;
}
