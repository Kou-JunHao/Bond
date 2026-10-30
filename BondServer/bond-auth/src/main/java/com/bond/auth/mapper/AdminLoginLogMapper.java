package com.bond.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.AdminLoginLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;

/**
 * 管理端登录审计的写入/计数查询（task-144，bond-auth 侧只负责记录与判定）。
 */
@Mapper
public interface AdminLoginLogMapper extends BaseMapper<AdminLoginLog> {

    /** 窗口内同一 IP 的管理端登录失败次数（bad_credentials / user_disabled）。 */
    @Select("SELECT count(*) FROM admin_login_logs "
            + "WHERE ip = #{ip} AND outcome IN ('bad_credentials', 'user_disabled') AND created_at >= #{since}")
    long countFailuresByIp(@Param("ip") String ip, @Param("since") OffsetDateTime since);

    /** 窗口内同一账号的管理端登录失败次数。 */
    @Select("SELECT count(*) FROM admin_login_logs "
            + "WHERE username = #{username} AND outcome IN ('bad_credentials', 'user_disabled') AND created_at >= #{since}")
    long countFailuresByUsername(@Param("username") String username, @Param("since") OffsetDateTime since);

    /** 同一 IP+username 最近一次成功登录时间（用于计算连续失败序号）。 */
    @Select("SELECT max(created_at) FROM admin_login_logs "
            + "WHERE ip = #{ip} AND username = #{username} AND outcome = 'success'")
    OffsetDateTime lastSuccessAt(@Param("ip") String ip, @Param("username") String username);

    /** 自上次成功以来的连续失败次数（after 为空表示从未成功过）。 */
    @Select({"<script>",
            "SELECT count(*) FROM admin_login_logs",
            "WHERE ip = #{ip} AND username = #{username}",
            "  AND outcome IN ('bad_credentials', 'user_disabled')",
            "<if test='after != null'> AND created_at &gt; #{after}</if>",
            "</script>"})
    long countFailuresAfter(@Param("ip") String ip, @Param("username") String username,
                            @Param("after") OffsetDateTime after);
}
