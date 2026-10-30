package com.bond.transfer.mapper;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * announcements 的联表行（任务单 #19）。
 *
 * <p>只用于「当前用户可见公告」查询：{@code read} 表示是否已有 announcement_reads 记录。
 * MyBatis 通过 setter 映射（与 ChatConversationRow 的写法保持一致）。</p>
 */
@Data
public class AnnouncementRow {

    private Long id;

    private String title;

    private String content;

    private String level;

    private OffsetDateTime publishedAt;

    private OffsetDateTime expiresAt;

    private Boolean read;
}
