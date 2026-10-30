package com.bond.common.dto;

import java.time.OffsetDateTime;

/**
 * 客户端可见的软件包摘要（task-81）。不含对象存储路径。
 *
 * @param code          包短标识
 * @param name          展示名
 * @param description   简介
 * @param platform      windows/macos/linux/android/any
 * @param latestVersion 最新已发布版本号（无发布时为 null）
 * @param releaseCount  已发布版本数
 * @param updatedAt     包记录更新时间
 */
public record SoftwarePackageDTO(
        String code,
        String name,
        String description,
        String platform,
        String latestVersion,
        long releaseCount,
        OffsetDateTime updatedAt
) {
}
