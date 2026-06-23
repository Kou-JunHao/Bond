package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("devices")
public class Device {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String deviceName;
    private String deviceType;
    private String publicKey;
    private LocalDateTime lastSeenAt;
    private Boolean isOnline;
    private LocalDateTime createdAt;
}
