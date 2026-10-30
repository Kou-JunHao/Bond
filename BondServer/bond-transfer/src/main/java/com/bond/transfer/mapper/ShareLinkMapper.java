package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.ShareLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 分享链接状态变更全部走单条原子 SQL（并发安全）：
 * 计数、撤销、提取码失败/锁定都在数据库端完成，避免「先查后改」的竞态。
 */
@Mapper
public interface ShareLinkMapper extends BaseMapper<ShareLink> {

    @Select("SELECT * FROM share_links WHERE token = #{token}")
    ShareLink selectByToken(@Param("token") String token);

    /** 当前用户「有效」链接数（未撤销、未过期）——用于档位活跃链接上限判断。 */
    @Select("SELECT COUNT(*) FROM share_links WHERE creator_id = #{creatorId} AND status = 0 "
            + "AND (expires_at IS NULL OR expires_at > now())")
    int countActiveByCreator(@Param("creatorId") Long creatorId);

    /**
     * 完整下载开始时 +1（Range 续传不调用）。一条 SQL 同时校验状态/有效期/次数，
     * 返回 0 表示链接已失效或次数用尽，调用方按「已耗尽/已失效」处理。
     */
    @Update("UPDATE share_links SET download_count = download_count + 1, last_access_at = now(), "
            + "updated_at = now() "
            + "WHERE id = #{id} AND status = 0 "
            + "AND (expires_at IS NULL OR expires_at > now()) "
            + "AND (max_downloads = 0 OR download_count < max_downloads)")
    int incrementDownloadCount(@Param("id") Long id);

    /**
     * 纯 Range 续传（不计数）时刷新最近访问时间（task-163）；不写 download_count。
     */
    @Update("UPDATE share_links SET last_access_at = now() WHERE id = #{id} AND status = 0")
    int touchLastAccess(@Param("id") Long id);

    /** 撤销（幂等：已撤销时返回 0，调用方无需报错）。 */
    @Update("UPDATE share_links SET status = 1, updated_at = now() WHERE id = #{id} AND status = 0")
    int revokeById(@Param("id") Long id);

    /**
     * 过期标记（task-157 定时任务调用）：把已到 expires_at 且仍为「正常」的链接置为
     * {@code status = 2}（已过期），**不删行**（管理端审计需要）。幂等：标记过的行不再命中。
     *
     * @return 本次标记行数
     */
    @Update("UPDATE share_links SET status = 2, updated_at = now() "
            + "WHERE status = 0 AND expires_at IS NOT NULL AND expires_at < #{now}")
    int markExpired(@Param("now") java.time.OffsetDateTime now);

    /** 提取码失败 +1；达到阈值时写入锁定截止时间（一条 SQL 完成计数与锁定）。 */
    @Update("UPDATE share_links SET failed_attempts = failed_attempts + 1, "
            + "code_locked_until = CASE WHEN failed_attempts + 1 >= #{maxAttempts} "
            + "THEN now() + (CAST(#{lockMinutes} AS INTEGER) * interval '1 minute') "
            + "ELSE code_locked_until END, updated_at = now() WHERE id = #{id}")
    int recordCodeFailure(@Param("id") Long id,
                          @Param("maxAttempts") int maxAttempts,
                          @Param("lockMinutes") int lockMinutes);

    /** 提取码校验成功后清零失败计数/锁定。 */
    @Update("UPDATE share_links SET failed_attempts = 0, code_locked_until = NULL, updated_at = now() "
            + "WHERE id = #{id}")
    int resetCodeFailures(@Param("id") Long id);

    /**
     * 管理端治理列表（task-89）：一次 join 取齐用户名与文件名，禁止 N+1。
     *
     * <p>bond-transfer 未注册 MyBatis-Plus 分页插件，这里用显式 LIMIT/OFFSET；
     * 总数由 {@link #countAdmin} 独立查询，保证 total 与 items 同条件。</p>
     */
    @Select({"<script>",
            "SELECT l.id AS id, l.token AS token, l.file_id AS fileId, f.file_name AS fileName,",
            "       l.creator_id AS ownerId, u.username AS ownerUsername, l.status AS status,",
            "       l.expires_at AS expiresAt, l.download_count AS downloadCount,",
            "       l.created_at AS createdAt, l.updated_at AS updatedAt",
            "  FROM share_links l",
            "  LEFT JOIN users u ON u.id = l.creator_id",
            "  LEFT JOIN workspace_files f ON f.id = l.file_id",
            " WHERE 1 = 1",
            "<if test=\"status != null\"> AND l.status = #{status} </if>",
            "<if test=\"ownerKeyword != null and ownerKeyword != ''\"> AND u.username ILIKE concat('%', #{ownerKeyword}, '%') </if>",
            "<if test=\"tokenPrefix != null and tokenPrefix != ''\"> AND l.token LIKE concat(#{tokenPrefix}, '%') </if>",
            " ORDER BY l.created_at DESC, l.id DESC",
            " LIMIT #{limit} OFFSET #{offset}",
            "</script>"})
    List<AdminShareRow> selectAdminPage(@Param("status") Integer status,
                                        @Param("ownerKeyword") String ownerKeyword,
                                        @Param("tokenPrefix") String tokenPrefix,
                                        @Param("limit") int limit,
                                        @Param("offset") long offset);

    /** 管理端列表的独立 count（与 {@link #selectAdminPage} 同条件）。 */
    @Select({"<script>",
            "SELECT COUNT(*) FROM share_links l",
            "  LEFT JOIN users u ON u.id = l.creator_id",
            " WHERE 1 = 1",
            "<if test=\"status != null\"> AND l.status = #{status} </if>",
            "<if test=\"ownerKeyword != null and ownerKeyword != ''\"> AND u.username ILIKE concat('%', #{ownerKeyword}, '%') </if>",
            "<if test=\"tokenPrefix != null and tokenPrefix != ''\"> AND l.token LIKE concat(#{tokenPrefix}, '%') </if>",
            "</script>"})
    long countAdmin(@Param("status") Integer status,
                    @Param("ownerKeyword") String ownerKeyword,
                    @Param("tokenPrefix") String tokenPrefix);
}
