package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 软件分发包（task-81）：一个包 = 一个产品线（如 "bond-pan-windows"），下挂多个版本发布。
 */
@Data
@TableName("software_packages")
public class SoftwarePackage {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 稳定短标识（小写-连字符），客户端按它拉取。 */
    private String code;

    private String name;

    private String description;

    /** windows / macos / linux / android / any */
    private String platform;

    private Long createdBy;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
