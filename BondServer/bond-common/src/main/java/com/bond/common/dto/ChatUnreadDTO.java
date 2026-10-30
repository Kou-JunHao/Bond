package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 未读聚合（task-136）：{@code GET /api/chat/unread}，供客户端一次刷新侧栏徽标。
 *
 * <p>未读口径与会话列表一致：会话内 id 大于本人 last_read_message_id、非本人发送、未删除的消息数。
 * 未打开过的工作区频道计入 0（首次打开时才建已读位点）。</p>
 */
@Data
public class ChatUnreadDTO {

    private long total;

    private List<ConversationUnread> byConversation = new ArrayList<>();

    @Data
    public static class ConversationUnread {

        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;

        private long unread;
    }
}
