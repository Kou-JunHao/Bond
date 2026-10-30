package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class UserDTO {
    /** ID 超出 JS 安全整数范围（>2^53），序列化为字符串，避免前端精度丢失。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String username;
    private String nickname;
    private String avatarUrl;
    /** 登录/找回邮箱（task-57）：未绑定时为 null。 */
    private String email;
    /** 是否已绑定邮箱（= email != null）；客户端据此决定是否显示“绑定邮箱”。 */
    private Boolean emailVerified;
    private String accessToken;
    private String refreshToken;
}
