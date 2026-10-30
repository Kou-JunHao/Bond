package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 会话列表项。id 均为雪花、超出 JS 安全整数，统一序列化成字符串。
 */
@Data
public class ChatConversationDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** direct / workspace */
    private String type;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long workspaceId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long peerUserId;

    private String peerNickname;

    private String peerAvatarUrl;

    private String workspaceName;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastMessageId;

    private String lastMessageKind;

    /** 最后消息摘要：文本截断；file 显示「[文件]」；已删除显示空。 */
    private String lastMessagePreview;

    private Long lastMessageAtMillis;

    private long unreadCount;

    /** 当前用户是否置顶（成员维度，最多 20 个）。 */
    private boolean pinned;

    /** 当前用户是否免打扰。 */
    private boolean muted;

    private Long updatedAtMillis;
}
