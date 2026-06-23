package com.bond.common.dto;

import lombok.Data;

@Data
public class FriendDTO {
    private Long id;
    private Long userId;
    private String username;
    private String nickname;
    private String avatarUrl;
    private Integer status;
    private Long createdAt;
}
