package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.FileUploadSession;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 上传会话 Mapper（V7）。
 *
 * <p>读取统一用 {@code etags::text AS etags} 显式转字符串：PostgreSQL 的 jsonb
 * 交给 MyBatis 的 StringTypeHandler 有类型差异风险，显式 cast 后行为确定。
 * 写入时用 {@code #{etags}::jsonb} 显式转换，避免 varchar 赋给 jsonb 列报错。</p>
 */
@Mapper
public interface UploadSessionMapper extends BaseMapper<FileUploadSession> {

    String COLUMNS = "id, user_id, workspace_id, parent_path, file_name, content_type, file_size, "
            + "chunk_size, total_chunks, storage_upload_id, storage_key, etags::text AS etags, "
            + "received_bytes, created_at, updated_at, expires_at";

    @Select("SELECT " + COLUMNS + " FROM file_upload_sessions WHERE id = #{id}")
    FileUploadSession selectSession(@Param("id") Long id);

    /** 断点续传复用：同用户/工作区/目录/文件名/大小且未过期的最新会话。 */
    @Select("SELECT " + COLUMNS + " FROM file_upload_sessions"
            + " WHERE user_id = #{userId} AND workspace_id = #{workspaceId}"
            + " AND parent_path = #{parentPath} AND file_name = #{fileName} AND file_size = #{fileSize}"
            + " AND expires_at > now() ORDER BY created_at DESC LIMIT 1")
    FileUploadSession selectReusable(@Param("userId") Long userId,
                                     @Param("workspaceId") Long workspaceId,
                                     @Param("parentPath") String parentPath,
                                     @Param("fileName") String fileName,
                                     @Param("fileSize") long fileSize);

    @Insert("INSERT INTO file_upload_sessions (id, user_id, workspace_id, parent_path, file_name, content_type,"
            + " file_size, chunk_size, total_chunks, storage_upload_id, storage_key, etags, received_bytes,"
            + " created_at, updated_at, expires_at)"
            + " VALUES (#{id}, #{userId}, #{workspaceId}, #{parentPath}, #{fileName}, #{contentType},"
            + " #{fileSize}, #{chunkSize}, #{totalChunks}, #{storageUploadId}, #{storageKey}, #{etags}::jsonb,"
            + " #{receivedBytes}, now(), now(), #{expiresAt})")
    int insertSession(FileUploadSession session);

    /** 幂等推进：整体覆写已收分片数组与累计字节（调用方在单 JVM 锁内合并，避免乱序丢更新）。 */
    @Update("UPDATE file_upload_sessions SET etags = #{etags}::jsonb, received_bytes = #{receivedBytes},"
            + " updated_at = now() WHERE id = #{id}")
    int updateProgress(@Param("id") Long id,
                       @Param("etags") String etags,
                       @Param("receivedBytes") long receivedBytes);

    /** 过期清理：单轮最多 limit 条（batch 上限由配置控制）。 */
    @Select("SELECT " + COLUMNS + " FROM file_upload_sessions"
            + " WHERE expires_at < now() ORDER BY expires_at LIMIT #{limit}")
    List<FileUploadSession> selectExpired(@Param("limit") int limit);

    // ── task-54：未完成上传会话分页列表（只读，一条 SELECT + 一条 count，无 N+1） ──

    /** 当前用户未过期会话，按创建时间倒序。表内只会存在未完成会话（complete/abort 后删行）。 */
    @Select("SELECT " + COLUMNS + " FROM file_upload_sessions"
            + " WHERE user_id = #{userId} AND expires_at > now()"
            + " ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}")
    List<FileUploadSession> selectActivePage(@Param("userId") Long userId,
                                             @Param("offset") long offset,
                                             @Param("limit") int limit);

    @Select("SELECT count(*) FROM file_upload_sessions WHERE user_id = #{userId} AND expires_at > now()")
    long countActive(@Param("userId") Long userId);
}
