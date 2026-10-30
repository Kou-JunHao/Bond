package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 聊天会话：{@code direct}（1:1，好友之间）或 {@code workspace}（工作区默认频道）。
 * <p>时间列为 TIMESTAMPTZ（V12），实体统一用 OffsetDateTime。</p>
 */
@Data
@TableName("chat_conversations")
public class ChatConversation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String type;

    private Long workspaceId;

    /** 仅 direct 使用：'min(userId):max(userId)'，唯一。 */
    private String directKey;

    private Long createdBy;

    /** 冗余的最后一条消息 id，用于会话列表排序与摘要（同事务内更新）。 */
    private Long lastMessageId;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
