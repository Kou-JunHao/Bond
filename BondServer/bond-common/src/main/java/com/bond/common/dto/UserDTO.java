package com.bond.common.dto;

import lombok.Data;

@Data
public class UserDTO {
    private Long id;
    private String username;
    private String nickname;
    private String avatarUrl;
    private String accessToken;
    private String refreshToken;
}
