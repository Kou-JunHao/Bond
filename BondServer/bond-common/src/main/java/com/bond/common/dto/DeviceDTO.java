package com.bond.common.dto;

import lombok.Data;

@Data
public class DeviceDTO {
    private Long id;
    private Long userId;
    private String deviceName;
    private String deviceType;
    private String publicKey;
    private Boolean isOnline;
    private Long lastSeenAt;
}
