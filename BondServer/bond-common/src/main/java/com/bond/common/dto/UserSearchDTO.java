package com.bond.common.dto;

import lombok.Data;

@Data
public class UserSearchDTO {
    private Long id;
    private String username;
    private String nickname;
    private String avatarUrl;
}
