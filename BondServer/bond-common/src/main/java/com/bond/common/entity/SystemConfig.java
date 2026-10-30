package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
@TableName("system_config")
public class SystemConfig {
    @TableId(type = IdType.INPUT)
    private String configKey;
    private String configValue;
    private String configType;
    private String description;
    /** 该实体被 AdminController 直接序列化返回，ID 字段同样转字符串避免 JS 精度丢失。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long updatedBy;
    private java.time.LocalDateTime updatedAt;
}
