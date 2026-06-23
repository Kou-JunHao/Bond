package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("system_config")
public class SystemConfig {
    @TableId(type = IdType.INPUT)
    private String configKey;
    private String configValue;
    private String configType;
    private String description;
    private Long updatedBy;
    private java.time.LocalDateTime updatedAt;
}
