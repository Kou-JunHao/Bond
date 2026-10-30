package com.bond.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.AdminLoginBlock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

/**
 * 管理端登录封禁的判定与写入（task-144，bond-auth 侧）。
 */
@Mapper
public interface AdminLoginBlockMapper extends BaseMapper<AdminLoginBlock> {

    /** 当前的 active 封禁行（未解除且未到期）。 */
    @Select("SELECT * FROM admin_login_blocks "
            + "WHERE scope = #{scope} AND value = #{value} "
            + "  AND lifted_at IS NULL AND expires_at > #{now} "
            + "ORDER BY banned_at DESC LIMIT 1")
    AdminLoginBlock selectActive(@Param("scope") String scope, @Param("value") String value,
                                 @Param("now") OffsetDateTime now);

    /** 新建封禁前，把同 scope+value 的过期未解除行标记为已解除，避免撞部分唯一索引。 */
    @Update("UPDATE admin_login_blocks SET lifted_at = #{now} "
            + "WHERE scope = #{scope} AND value = #{value} "
            + "  AND lifted_at IS NULL AND expires_at <= #{now}")
    int liftExpired(@Param("scope") String scope, @Param("value") String value,
                    @Param("now") OffsetDateTime now);

    /** 最近一段时间内该 scope+value 的封禁次数（用于指数退避）。 */
    @Select("SELECT count(*) FROM admin_login_blocks "
            + "WHERE scope = #{scope} AND value = #{value} AND banned_at >= #{since}")
    int countRecentBlocks(@Param("scope") String scope, @Param("value") String value,
                          @Param("since") OffsetDateTime since);
}
