package com.bond.transfer.mapper;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 会话列表查询结果（ChatConversationMapper.selectForUser 的映射行）。
 */
@Data
public class ChatConversationRow {

    private Long id;

    private String type;

    private Long workspaceId;

    private Long lastMessageId;

    private OffsetDateTime updatedAt;

    private Long peerUserId;

    private String peerNickname;

    private String peerAvatarUrl;

    private String workspaceName;

    private String lastMessageKind;

    private String lastMessagePreview;

    private OffsetDateTime lastMessageAt;

    private long unread;

    /** 当前用户是否置顶（task-136，chat_members.pinned）。 */
    private boolean pinned;

    /** 当前用户是否免打扰（task-136，chat_members.muted）。 */
    private boolean muted;
}
