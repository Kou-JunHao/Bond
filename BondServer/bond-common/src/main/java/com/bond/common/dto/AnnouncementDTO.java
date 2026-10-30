package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 公告对客户端（管理台/普通用户）的输出结构（任务单 #19）。
 *
 * <p>时间一律用毫秒时间戳，避免前端再解析时区；{@code read} 仅对普通用户的列表有意义，
 * 管理台列表该字段无意义（恒 false）。</p>
 */
@Data
public class AnnouncementDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String title;

    private String content;

    /** info / warn / critical */
    private String level;

    private Long publishedAtMillis;

    private Long expiresAtMillis;

    private Long createdAtMillis;

    private boolean read;

    /** 管理台列表：已读人数（普通用户接口恒 0）。 */
    private long readCount;
}
