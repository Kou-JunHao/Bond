package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.SoftwareRelease;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 软件版本发布（task-81）。
 */
@Mapper
public interface SoftwareReleaseMapper extends BaseMapper<SoftwareRelease> {

    /** 客户端：指定包下已发布版本（channel 为空则全部渠道）。 */
    @Select("SELECT * FROM software_releases "
            + "WHERE package_id = #{packageId} AND published = TRUE "
            + "AND (#{channel,jdbcType=VARCHAR} IS NULL OR channel = #{channel,jdbcType=VARCHAR}) "
            + "ORDER BY created_at DESC, version DESC LIMIT #{limit}")
    List<SoftwareRelease> selectPublishedByPackage(@Param("packageId") Long packageId,
                                                   @Param("channel") String channel,
                                                   @Param("limit") int limit);

    /**
     * 管理端：指定包下全部版本（含未发布）。
     *
     * <p>显式列且不取 {@code minio_path}——管理端列表不需要也不应带出存储路径；
     * 按 {@code created_at DESC, id DESC} 稳定排序（同秒创建时用 id 兜底）。</p>
     */
    @Select("SELECT id, package_id, version, channel, file_name, file_size, sha256, release_notes, "
            + "published, download_count, created_by, created_at, published_at "
            + "FROM software_releases WHERE package_id = #{packageId} "
            + "ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<SoftwareRelease> selectAllByPackage(@Param("packageId") Long packageId,
                                             @Param("limit") int limit);

    @Select("SELECT * FROM software_releases WHERE package_id = #{packageId} "
            + "AND version = #{version} AND channel = #{channel}")
    SoftwareRelease selectByVersion(@Param("packageId") Long packageId,
                                    @Param("version") String version,
                                    @Param("channel") String channel);

    /**
     * 按包统计已发布版本数与最新版本（仅 published）。
     * 单条 SQL 避免 N+1。
     */
    @Select("SELECT r.package_id AS package_id, count(*) AS total, "
            + "(SELECT r2.version FROM software_releases r2 "
            + " WHERE r2.package_id = r.package_id AND r2.published = TRUE "
            + " ORDER BY r2.published_at DESC NULLS LAST, r2.created_at DESC LIMIT 1) AS latest_version "
            + "FROM software_releases r WHERE r.published = TRUE GROUP BY r.package_id")
    List<Map<String, Object>> statsByPackage();

    /** 管理端：全部包（含未发布）的版本数与最新版本。 */
    @Select("SELECT r.package_id AS package_id, count(*) AS total, "
            + "(SELECT r2.version FROM software_releases r2 "
            + " WHERE r2.package_id = r.package_id AND r2.published = TRUE "
            + " ORDER BY r2.published_at DESC NULLS LAST, r2.created_at DESC LIMIT 1) AS latest_version "
            + "FROM software_releases r GROUP BY r.package_id")
    List<Map<String, Object>> statsByPackageAll();

    @Update("UPDATE software_releases SET download_count = download_count + 1 WHERE id = #{id}")
    int incrementDownloadCount(@Param("id") Long id);

    /**
     * task-106：显式发布。
     *
     * <p>不能依赖 {@code updateById}（它跳过 null 字段），这里需要「published=true 且 published_at=now()」
     * 的确定性写入，与 unpublish 成对。</p>
     */
    @Update("UPDATE software_releases SET published = TRUE, published_at = NOW() WHERE id = #{id}")
    int publishById(@Param("id") Long id);

    /**
     * task-106：显式下架。
     *
     * <p>MyBatis-Plus 的 {@code updateById} 默认跳过 null 字段，{@code setPublishedAt(null)} 不会落库，
     * 导致 DB 里仍是已发布态。用显式 UPDATE 保证 {@code published=false} 与
     * {@code published_at=NULL} 同时生效。</p>
     */
    @Update("UPDATE software_releases SET published = FALSE, published_at = NULL WHERE id = #{id}")
    int unpublishById(@Param("id") Long id);
}
