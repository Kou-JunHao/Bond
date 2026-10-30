package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class WorkspaceMemberDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long workspaceId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;
    private String nickname;
    private Integer role;
    private Long joinedAt;
}
