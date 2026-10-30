package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.EmailDeliveryLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 邮件投递日志（task-80）：管理端分页/统计 + 保留期清理（bond-transfer 侧）。
 */
@Mapper
public interface EmailDeliveryLogMapper extends BaseMapper<EmailDeliveryLog> {

    /** 指定天数窗口内的总/成功/失败数。 */
    @Select("SELECT count(*) AS total, "
            + "count(*) FILTER (WHERE status = 'sent') AS sent, "
            + "count(*) FILTER (WHERE status = 'failed') AS failed "
            + "FROM email_delivery_log "
            + "WHERE created_at >= now() - (#{days} * interval '1 day')")
    Map<String, Object> statsTotals(@Param("days") int days);

    /** 指定天数窗口内按用途分组的发送量（purpose 为 null 时归入 "(none)"）。 */
    @Select("SELECT coalesce(purpose, '(none)') AS purpose, "
            + "count(*) AS total, "
            + "count(*) FILTER (WHERE status = 'failed') AS failed "
            + "FROM email_delivery_log "
            + "WHERE created_at >= now() - (#{days} * interval '1 day') "
            + "GROUP BY coalesce(purpose, '(none)') "
            + "ORDER BY total DESC, purpose ASC")
    List<Map<String, Object>> statsByPurpose(@Param("days") int days);

    /** 删除早于 cutOff 的记录（保留策略 30 天，由定时任务调用）。 */
    @Delete("DELETE FROM email_delivery_log WHERE created_at < #{cutOff}")
    int deleteOlderThan(@Param("cutOff") OffsetDateTime cutOff);
}
