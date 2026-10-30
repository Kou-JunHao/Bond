package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 会话内消息搜索结果项（task-136）：只搜本人可读的未删除、未撤回文本消息。
 */
@Data
public class ChatSearchItemDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String body;

    private Long createdAtMillis;

    private String senderNickname;
}
