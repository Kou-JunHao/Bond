package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.Announcement;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 公告数据访问（任务单 #19，V15）。
 */
@Mapper
public interface AnnouncementMapper extends BaseMapper<Announcement> {

    /**
     * 当前用户可见的公告：已发布（published_at 非空且不晚于 now）、未过期（expires_at 为空或不早于 now）。
     * 已读状态用 LEFT JOIN 一次取出，避免 N+1。
     */
    @Select("""
            SELECT a.id, a.title, a.content, a.level, a.published_at, a.expires_at,
                   (r.id IS NOT NULL) AS read
              FROM announcements a
              LEFT JOIN announcement_reads r
                     ON r.announcement_id = a.id AND r.user_id = #{userId}
             WHERE a.published_at IS NOT NULL AND a.published_at <= now()
               AND (a.expires_at IS NULL OR a.expires_at > now())
             ORDER BY a.published_at DESC
             LIMIT #{limit}
            """)
    List<AnnouncementRow> selectVisible(@Param("userId") Long userId, @Param("limit") int limit);

    /** 未读数：用户从未读过的可见公告条数（单条 SQL，见任务要求第 4 条）。 */
    @Select("""
            SELECT count(*)
              FROM announcements a
             WHERE a.published_at IS NOT NULL AND a.published_at <= now()
               AND (a.expires_at IS NULL OR a.expires_at > now())
               AND NOT EXISTS (SELECT 1 FROM announcement_reads r
                                WHERE r.announcement_id = a.id AND r.user_id = #{userId})
            """)
    long countUnread(@Param("userId") Long userId);

    /** 幂等标记已读：唯一约束冲突时 Do Nothing。 */
    @Insert("""
            INSERT INTO announcement_reads (id, announcement_id, user_id, read_at)
            VALUES (#{id}, #{announcementId}, #{userId}, now())
            ON CONFLICT (announcement_id, user_id) DO NOTHING
            """)
    int markRead(@Param("id") Long id,
                 @Param("announcementId") Long announcementId,
                 @Param("userId") Long userId);

    /** 管理台列表的已读人数（一条聚合 SQL，供当前页使用）。 */
    @Select("""
            <script>
            SELECT announcement_id AS announcementId, count(*) AS cnt
              FROM announcement_reads
             WHERE announcement_id IN
                   <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
             GROUP BY announcement_id
            </script>
            """)
    List<AnnouncementReadCountRow> countReads(@Param("ids") List<Long> ids);

    /**
     * task-109：显式清空 expires_at。
     *
     * <p>MyBatis-Plus {@code updateById} 默认 FieldStrategy.NOT_NULL，实体字段为 null 时
     * 不会写库；后台编辑公告时 expiresAt 传 null 表示「取消过期时间」，必须走这条显式 UPDATE。</p>
     */
    @Update("UPDATE announcements SET expires_at = NULL, updated_at = now() WHERE id = #{id}")
    int clearExpiresAt(@Param("id") Long id);
}
