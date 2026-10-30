package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 会话成员 + 每人已读位点。
 * <p>复合主键 (conversation_id, user_id)：MyBatis-Plus 不适合用 BaseMapper 按 id 操作，
 * 因此仓库里只通过自定义 SQL 读写（见 ChatMemberMapper）。</p>
 */
@Data
@TableName("chat_members")
public class ChatMember {

    private Long conversationId;

    private Long userId;

    /** 已读到的消息 id；未读 = 该会话中 id 更大、且不是自己发的未删除消息数。 */
    private Long lastReadMessageId;

    private Boolean muted;

    private OffsetDateTime lastReadAt;

    /** 该用户是否置顶（成员维度）。 */
    private Boolean pinned;

    private OffsetDateTime joinedAt;
}
