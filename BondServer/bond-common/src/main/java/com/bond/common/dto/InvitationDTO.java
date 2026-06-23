package com.bond.common.dto;

import lombok.Data;

@Data
public class InvitationDTO {
    private Long id;
    private Long workspaceId;
    private String workspaceName;
    private Long inviterId;
    private String inviterNickname;
    private Long inviteeId;
    private String inviteToken;
    private Integer role;
    private Integer status;
    private Long expiresAt;
    private Long createdAt;
}
