package com.bond.common.dto;

import lombok.Data;

@Data
public class FriendRequestDTO {
    private Long id;
    private Long fromUserId;
    private String fromUsername;
    private String fromNickname;
    private String message;
    private Integer status;
    private Long createdAt;
}
