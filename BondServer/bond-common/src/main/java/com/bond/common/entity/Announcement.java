package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 系统公告（V15，任务单 #19）。
 *
 * <p>{@code publishedAt} 是唯一可见性时间点：为空或未来时间 = 未发布/定时发布，列表不展示；
 * {@code expiresAt} 为空 = 永不过期。{@code level} 取值 info / warn / critical。</p>
 */
@Data
@TableName("announcements")
public class Announcement {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String title;

    private String content;

    private String level;

    private OffsetDateTime publishedAt;

    private OffsetDateTime expiresAt;

    private Long createdBy;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
