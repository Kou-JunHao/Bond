package com.bond.transfer.mapper;

import lombok.Data;

/** 公告已读人数聚合行（任务单 #19）。 */
@Data
public class AnnouncementReadCountRow {

    private Long announcementId;

    private Long cnt;
}
