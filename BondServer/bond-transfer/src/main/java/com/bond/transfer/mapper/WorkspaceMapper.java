package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.Workspace;
import com.bond.transfer.service.UsageAuditRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface WorkspaceMapper extends BaseMapper<Workspace> {

    /**
     * 一次查询批量取工作区统计（避免列表页逐工作区 COUNT 的 N+1）。
     *
     * <p>两个相关子查询仍然只有一条 SQL；文件数不统计目录行。</p>
     *
     * @return 每行 workspace_id / member_count / file_count（PostgreSQL 返回 Long）
     */
    @Select("<script>"
            + "SELECT w.id AS workspace_id, "
            + "(SELECT COUNT(*) FROM workspace_members m WHERE m.workspace_id = w.id) AS member_count, "
            + "(SELECT COUNT(*) FROM workspace_files f WHERE f.workspace_id = w.id "
            + "AND f.is_directory = FALSE AND f.deleted_at IS NULL) AS file_count "
            + "FROM workspaces w WHERE w.id IN "
            + "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Map<String, Object>> selectStatsByIds(@Param("ids") Collection<Long> ids);

    /**
     * 原子占用空间：仅当 used_size + delta <= max_size 时才更新（并发安全，影响行数 0 即超额）。
     *
     * @return 影响行数（1=成功，0=空间不足或工作区不存在）
     */
    @Update("UPDATE workspaces SET used_size = used_size + #{delta}, updated_at = now() "
            + "WHERE id = #{workspaceId} AND used_size + #{delta} <= max_size")
    int reserveUsedSize(@Param("workspaceId") Long workspaceId, @Param("delta") long delta);

    /** 原子释放空间（删除文件时）；不会减到负数。 */
    @Update("UPDATE workspaces SET used_size = GREATEST(used_size - #{delta}, 0), updated_at = now() "
            + "WHERE id = #{workspaceId}")
    int releaseUsedSize(@Param("workspaceId") Long workspaceId, @Param("delta") long delta);

    /** 档位变更后刷新该用户名下所有工作区（含个人空间）的配额上限。 */
    @Update("UPDATE workspaces SET max_size = #{maxSize}, updated_at = now() WHERE owner_id = #{ownerId}")
    int updateMaxSizeByOwner(@Param("ownerId") Long ownerId, @Param("maxSize") long maxSize);

    /**
     * 把 {@code used_size} 精确写成给定值（task-162 对账专用；幂等，可重复执行）。
     */
    @Update("UPDATE workspaces SET used_size = #{usedSize}, updated_at = now() WHERE id = #{workspaceId}")
    int setUsedSize(@Param("workspaceId") Long workspaceId, @Param("usedSize") long usedSize);

    /**
     * 对账查询（task-162）：每个工作区的「有效存储」= 未删除文件字节 + 未删除文档字节，
     * 与 {@code used_size} 对比。目录行不算（file_size 恒 0），回收站行不算（删除时已释放）。
     *
     * <p>一条 SQL 完成，避免逐空间往返；{@code ids} 为空表示取前 {@code limit} 个空间。</p>
     */
    @Select("<script>"
            + "SELECT w.id AS workspace_id, w.owner_id AS owner_id, w.name AS name, "
            + "w.used_size AS used_size, "
            + "COALESCE((SELECT SUM(f.file_size) FROM workspace_files f "
            + "  WHERE f.workspace_id = w.id AND f.deleted_at IS NULL AND f.is_directory = FALSE), 0) AS file_bytes, "
            + "COALESCE((SELECT SUM(d.size_bytes) FROM documents d "
            + "  WHERE d.workspace_id = w.id AND d.deleted_at IS NULL), 0) AS document_bytes "
            + "FROM workspaces w "
            + "<if test='ids != null and ids.size() > 0'>WHERE w.id IN "
            + "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach> "
            + "</if>"
            + "ORDER BY w.id LIMIT #{limit}"
            + "</script>")
    List<UsageAuditRow> selectUsageAudit(@Param("ids") Collection<Long> ids, @Param("limit") int limit);

    /**
     * 回收站占用字节（task-88 方案 B：回收站内容继续占配额）。
     *
     * <p>= 软删文件（非目录）file_size 之和 + 软删文档 size_bytes 之和；一个 SQL 聚合，供
     * {@code GET /api/files/quota} 的 {@code trashBytes} 字段使用。</p>
     */
    @Select("SELECT COALESCE(("
            + "  SELECT SUM(f.file_size) FROM workspace_files f "
            + "  WHERE f.workspace_id = #{workspaceId} AND f.deleted_at IS NOT NULL AND f.is_directory = FALSE"
            + "), 0) + COALESCE(("
            + "  SELECT SUM(d.size_bytes) FROM documents d "
            + "  WHERE d.workspace_id = #{workspaceId} AND d.deleted_at IS NOT NULL"
            + "), 0)")
    long selectTrashBytes(@Param("workspaceId") Long workspaceId);
}
