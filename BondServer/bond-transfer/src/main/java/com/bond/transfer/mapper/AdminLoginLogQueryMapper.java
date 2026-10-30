package com.bond.transfer.mapper;

import com.bond.common.entity.AdminLoginLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端登录审计查询（task-144，bond-transfer 管理端使用）。
 *
 * <p>写入侧在 bond-auth（{@code AdminLoginAuditWriter}），这里只做只读查询：
 * 列表、分页 count、统计聚合。项目未注册 MyBatis-Plus 分页插件，因此全部手写
 * {@code LIMIT/OFFSET} + 独立 count，禁止「全表读内存再切」。</p>
 */
@Mapper
public interface AdminLoginLogQueryMapper {

    @Select("<script>"
            + "SELECT * FROM admin_login_logs WHERE created_at &gt;= #{since}"
            + "<if test='to != null'> AND created_at &lt;= #{to}</if>"
            + "<if test='ip != null and ip != \"\"'> AND ip = #{ip}</if>"
            + "<if test='username != null and username != \"\"'> AND username = #{username}</if>"
            + "<if test='outcome != null and outcome != \"\"'> AND outcome = #{outcome}</if>"
            + " ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<AdminLoginLog> selectPage(@Param("since") OffsetDateTime since,
                                   @Param("to") OffsetDateTime to,
                                   @Param("ip") String ip,
                                   @Param("username") String username,
                                   @Param("outcome") String outcome,
                                   @Param("limit") int limit,
                                   @Param("offset") long offset);

    @Select("<script>"
            + "SELECT count(*) FROM admin_login_logs WHERE created_at &gt;= #{since}"
            + "<if test='to != null'> AND created_at &lt;= #{to}</if>"
            + "<if test='ip != null and ip != \"\"'> AND ip = #{ip}</if>"
            + "<if test='username != null and username != \"\"'> AND username = #{username}</if>"
            + "<if test='outcome != null and outcome != \"\"'> AND outcome = #{outcome}</if>"
            + "</script>")
    long countPage(@Param("since") OffsetDateTime since,
                   @Param("to") OffsetDateTime to,
                   @Param("ip") String ip,
                   @Param("username") String username,
                   @Param("outcome") String outcome);

    /**
     * 查询当前仍有效的封禁 IP / 用户名（用于给日志行打 {@code blockedNow}）。
     */
    @Select("SELECT scope, value FROM admin_login_blocks "
            + "WHERE lifted_at IS NULL AND expires_at > now()")
    List<Map<String, Object>> selectActiveScopes();

    @Select("SELECT count(*) AS total, "
            + "count(*) FILTER (WHERE outcome = 'success') AS success "
            + "FROM admin_login_logs WHERE created_at >= #{since}")
    Map<String, Object> totals(@Param("since") OffsetDateTime since);

    @Select("SELECT outcome AS outcome, count(*) AS cnt FROM admin_login_logs "
            + "WHERE created_at >= #{since} GROUP BY outcome ORDER BY cnt DESC")
    List<Map<String, Object>> countByOutcome(@Param("since") OffsetDateTime since);

    @Select("SELECT ip AS value, count(*) AS cnt, "
            + "count(*) FILTER (WHERE outcome <> 'success') AS failed "
            + "FROM admin_login_logs WHERE created_at >= #{since} "
            + "GROUP BY ip ORDER BY cnt DESC LIMIT #{limit}")
    List<Map<String, Object>> topIps(@Param("since") OffsetDateTime since, @Param("limit") int limit);

    @Select("SELECT username AS value, count(*) AS cnt, "
            + "count(*) FILTER (WHERE outcome <> 'success') AS failed "
            + "FROM admin_login_logs WHERE created_at >= #{since} "
            + "GROUP BY username ORDER BY cnt DESC LIMIT #{limit}")
    List<Map<String, Object>> topUsers(@Param("since") OffsetDateTime since, @Param("limit") int limit);

    @Select("SELECT to_char((created_at AT TIME ZONE 'Asia/Shanghai')::date, 'YYYY-MM-DD') AS day, "
            + "count(*) FILTER (WHERE outcome = 'success') AS success, "
            + "count(*) FILTER (WHERE outcome <> 'success') AS failed "
            + "FROM admin_login_logs WHERE created_at >= #{since} "
            + "GROUP BY 1 ORDER BY 1")
    List<Map<String, Object>> daily(@Param("since") OffsetDateTime since);
}
