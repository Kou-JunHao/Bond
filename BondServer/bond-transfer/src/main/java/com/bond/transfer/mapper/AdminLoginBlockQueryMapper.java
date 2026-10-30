package com.bond.transfer.mapper;

import com.bond.common.entity.AdminLoginBlock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 管理端登录封禁查询/解除（task-144，bond-transfer 管理端使用）。
 *
 * <p>封禁的写入在 bond-auth 守卫里完成；管理端只读取列表并支持人工解除。
 * 解除用显式 UPDATE（{@code lifted_at IS NULL} 作为乐观条件），避免并发重复解除。</p>
 */
@Mapper
public interface AdminLoginBlockQueryMapper {

    @Select("<script>"
            + "SELECT * FROM admin_login_blocks"
            + "<where>"
            + "<if test='active != null and active'> AND lifted_at IS NULL AND expires_at &gt; now()</if>"
            + "<if test='active != null and !active'> AND (lifted_at IS NOT NULL OR expires_at &lt;= now())</if>"
            + "<if test='scope != null and scope != \"\"'> AND scope = #{scope}</if>"
            + "<if test='value != null and value != \"\"'> AND value = #{value}</if>"
            + "</where>"
            + " ORDER BY banned_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<AdminLoginBlock> selectPage(@Param("active") Boolean active,
                                     @Param("scope") String scope,
                                     @Param("value") String value,
                                     @Param("limit") int limit,
                                     @Param("offset") long offset);

    @Select("<script>"
            + "SELECT count(*) FROM admin_login_blocks"
            + "<where>"
            + "<if test='active != null and active'> AND lifted_at IS NULL AND expires_at &gt; now()</if>"
            + "<if test='active != null and !active'> AND (lifted_at IS NOT NULL OR expires_at &lt;= now())</if>"
            + "<if test='scope != null and scope != \"\"'> AND scope = #{scope}</if>"
            + "<if test='value != null and value != \"\"'> AND value = #{value}</if>"
            + "</where>"
            + "</script>")
    long countPage(@Param("active") Boolean active,
                   @Param("scope") String scope,
                   @Param("value") String value);

    /**
     * 人工解除：只有仍生效（未解除）的行会被更新，返回 0 表示不存在/已解除。
     */
    @Update("UPDATE admin_login_blocks SET lifted_at = now(), lifted_by = #{actorId} "
            + "WHERE id = #{id} AND lifted_at IS NULL")
    int lift(@Param("id") Long id, @Param("actorId") Long actorId);
}
