package com.bond.common.dto;

import java.time.OffsetDateTime;

/**
 * 客户端可见的软件版本（task-81）。不含对象存储路径（minioPath）。
 *
 * @param id            版本 id
 * @param packageCode   所属包短标识
 * @param version       版本号（展示用，如 1.4.0）
 * @param channel       stable/beta/nightly
 * @param fileName      原始文件名（下载时作为附件名）
 * @param fileSize      字节数
 * @param sha256        SHA-256（小写 hex）
 * @param releaseNotes  更新说明
 * @param downloadCount 下载次数
 * @param publishedAt   发布时间
 * @param createdAt     创建时间
 * @param published     是否已发布（管理端列表用它显式区分下架版本；客户端接口只会返回 true）
 */
public record SoftwareReleaseDTO(
        Long id,
        String packageCode,
        String version,
        String channel,
        String fileName,
        Long fileSize,
        String sha256,
        String releaseNotes,
        Long downloadCount,
        OffsetDateTime publishedAt,
        OffsetDateTime createdAt,
        Boolean published
) {
}
