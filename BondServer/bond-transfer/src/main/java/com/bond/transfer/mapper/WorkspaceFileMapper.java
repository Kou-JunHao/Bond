package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.WorkspaceFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface WorkspaceFileMapper extends BaseMapper<WorkspaceFile> {

    /**
     * task-117：文件搜索（前缀/关键词全部走 #{} 参数绑定，绝不拼接 SQL）。
     *
     * <p>排序：名称前缀命中优先（CASE 0/1）→ updated_at DESC → id DESC；
     * keyset 分页用 {@code (updated_at, id) &lt; (cursorTime, cursorId)}（XML 中 &lt; 已转义）。</p>
     *
     * <p>性能备注：ILIKE '%kw%' 用不上普通 B-tree；PG 若已有 pg_trgm，后续可加
     * {@code gin (file_name gin_trgm_ops)} 索引（本单不新建扩展、不加该索引）。</p>
     */
    @Select("""
            <script>
            SELECT f.* FROM workspace_files f
            WHERE f.deleted_at IS NULL
              AND f.workspace_id IN
              <foreach collection="workspaceIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
              AND f.file_name ILIKE #{pattern}
            <if test="cursorTime != null">
              AND (f.updated_at &lt; #{cursorTime}
                   OR (f.updated_at = #{cursorTime} AND f.id &lt; #{cursorId}))
            </if>
            ORDER BY
              CASE WHEN f.file_name ILIKE #{prefixPattern} THEN 0 ELSE 1 END,
              f.updated_at DESC, f.id DESC
            LIMIT #{fetch}
            </script>
            """)
    List<WorkspaceFile> search(@Param("workspaceIds") List<Long> workspaceIds,
                               @Param("pattern") String pattern,
                               @Param("prefixPattern") String prefixPattern,
                               @Param("cursorTime") OffsetDateTime cursorTime,
                               @Param("cursorId") Long cursorId,
                               @Param("fetch") int fetch);

    /** task-117：搜索命中总数（与 search 同过滤条件、不含 cursor）。 */
    @Select("""
            <script>
            SELECT count(*) FROM workspace_files f
            WHERE f.deleted_at IS NULL
              AND f.workspace_id IN
              <foreach collection="workspaceIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
              AND f.file_name ILIKE #{pattern}
            </script>
            """)
    long countSearch(@Param("workspaceIds") List<Long> workspaceIds,
                     @Param("pattern") String pattern);

    /**
     * 按工作区批量统计文件数（task-102）：不含目录、不含回收站（deleted_at IS NULL），
     * 一条 GROUP BY 补齐当前页，避免逐行 COUNT 的 N+1。
     */
    @Select("""
            <script>
            SELECT workspace_id, count(*) AS file_count
            FROM workspace_files
            WHERE workspace_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND is_directory = FALSE
              AND deleted_at IS NULL
            GROUP BY workspace_id
            </script>
            """)
    List<Map<String, Object>> countActiveFilesByWorkspaceIds(@Param("ids") List<Long> ids);
}
