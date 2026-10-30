package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class UserSearchDTO {
    /** ID 超出 JS 安全整数范围（>2^53），序列化为字符串，避免前端精度丢失。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String username;
    private String nickname;
    private String avatarUrl;
}
