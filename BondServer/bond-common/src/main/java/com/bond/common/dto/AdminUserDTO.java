package com.bond.common.dto;

import lombok.Data;

@Data
public class AdminUserDTO {
    private Long id;
    private String username;
    private String nickname;
    private String avatarUrl;
    private Integer status;
    private Boolean isAdmin;
    private Long createdAt;
    private long deviceCount;
    private long transferCount;
}
