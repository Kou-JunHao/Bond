package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话已读回执（task-136）：每个成员最后读到的消息位点，供「已读/未读」展示。
 *
 * <p>仅会话成员可读；workspace 频道的成员来自 workspace_members，direct 会话来自 chat_members。
 * 从未上报已读的成员 {@code lastReadMessageId=0}、{@code lastReadAtMillis=null}。</p>
 */
@Data
public class ChatReadStateDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long conversationId;

    private List<MemberState> members = new ArrayList<>();

    @Data
    public static class MemberState {

        @JsonSerialize(using = ToStringSerializer.class)
        private Long userId;

        private String nickname;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long lastReadMessageId;

        private Long lastReadAtMillis;
    }
}
