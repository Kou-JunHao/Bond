package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 软件版本发布（task-81）：同一包下 (version, channel) 唯一；published=true 才出现在客户端接口。
 */
@Data
@TableName("software_releases")
public class SoftwareRelease {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long packageId;

    private String version;

    /** stable / beta / nightly */
    private String channel;

    private String fileName;

    private Long fileSize;

    /** 小写十六进制 SHA-256（64 字符）；客户端下载后可自行校验。 */
    private String sha256;

    /** 对象存储键（不在任何对外响应中返回）。 */
    private String minioPath;

    private String releaseNotes;

    private Boolean published;

    private Long downloadCount;

    private Long createdBy;

    private OffsetDateTime createdAt;

    private OffsetDateTime publishedAt;
}
