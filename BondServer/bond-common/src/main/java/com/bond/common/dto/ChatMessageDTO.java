package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 聊天消息（HTTP 列表 / 发送响应 / WS msg 帧共用）。
 */
@Data
public class ChatMessageDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long conversationId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long senderId;

    private String senderNickname;

    /** text / file / image / system */
    private String kind;

    /** 删除后为 null（客户端显示「该消息已删除」）。 */
    private String body;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long fileId;

    private String fileName;

    private Long fileSize;

    private String clientMsgId;

    private Long createdAtMillis;

    private boolean deleted;

    /** 已撤回：body/fileId 为 null，客户端显示「消息已撤回」占位。 */
    private boolean recalled;

    /** 撤回时间（毫秒时间戳），未撤回为 null。 */
    private Long recalledAtMillis;

    /** file 消息的附加信息（服务端已校验文件存在且可访问）。 */
    private String fileContentType;
}
