package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class InvitationDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long workspaceId;
    private String workspaceName;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long inviterId;
    private String inviterNickname;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long inviteeId;
    private String inviteToken;
    private Integer role;
    private Integer status;
    private Long expiresAt;
    private Long createdAt;
}
