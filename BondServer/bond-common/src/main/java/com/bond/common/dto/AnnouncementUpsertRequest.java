package com.bond.common.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 管理台公告创建/更新请求（任务单 #19）。
 *
 * <p>{@code publishedAt} 为空 = 立即发布；未来时间 = 定时发布（到点前列表不可见）；
 * {@code expiresAt} 为空 = 永不过期。</p>
 */
@Data
public class AnnouncementUpsertRequest {

    private String title;

    private String content;

    /** info / warn / critical，大小写不敏感，默认 info。 */
    private String level;

    private OffsetDateTime publishedAt;

    private OffsetDateTime expiresAt;
}
