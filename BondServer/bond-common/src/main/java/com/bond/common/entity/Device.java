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
    /**
     * 稳定设备标识：优先取客户端上报的 deviceId（fingerprint）；缺省回退为
     * deviceName + "|" + deviceType。与 V5__devices_dedupe.sql 的回填规则一致。
     */
    private String deviceUid;
    private String deviceName;
    private String deviceType;
    private String publicKey;
    private LocalDateTime lastSeenAt;
    private Boolean isOnline;
    private LocalDateTime createdAt;
}
