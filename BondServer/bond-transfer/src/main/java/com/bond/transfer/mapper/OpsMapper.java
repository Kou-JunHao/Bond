package com.bond.transfer.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 运维定时任务用到的少量 SQL（task-74）。
 *
 * <p>只读查询 + PG advisory lock；不引入新表，也不新增迁移。</p>
 */
public interface OpsMapper {

    /**
     * 抢一把会话级 advisory lock。连接归还池前必须 {@link #advisoryUnlock(long)}，
     * 否则锁会随连接复用一直挂在该 PG 会话上。
     */
    @Select("SELECT pg_try_advisory_lock(#{key})")
    boolean tryAdvisoryLock(@Param("key") long key);

    @Select("SELECT pg_advisory_unlock(#{key})")
    boolean advisoryUnlock(@Param("key") long key);

    /**
     * 仍被 {@code workspace_files} 行引用的对象键。
     *
     * <p>包含回收站行（软删但尚未彻底删除）——它们仍然指向真实对象，GC 不能当作孤儿。</p>
     */
    @Select("SELECT minio_path FROM workspace_files WHERE minio_path IS NOT NULL")
    List<String> selectReferencedObjectKeys();

    /** 进行中（或已完成但清理滞后）的分片上传目标键，也属于“可能正在写”的对象。 */
    @Select("SELECT storage_key FROM file_upload_sessions WHERE storage_key IS NOT NULL")
    List<String> selectUploadSessionKeys();

    /**
     * 审计日志保留（task-157）：删除 {@code created_at < cutoff} 的最多 {@code limit} 行。
     *
     * <p>用子查询 + LIMIT 而不是一次性 DELETE，避免长事务锁住审计写入；外层按批循环。</p>
     */
    @Delete("DELETE FROM admin_operation_logs WHERE id IN "
            + "(SELECT id FROM admin_operation_logs WHERE created_at < #{cutoff} "
            + "ORDER BY created_at LIMIT #{limit})")
    int deleteOperationLogsBefore(@Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);

    /** 管理端登录审计日志保留（task-157），语义同上。 */
    @Delete("DELETE FROM admin_login_logs WHERE id IN "
            + "(SELECT id FROM admin_login_logs WHERE created_at < #{cutoff} "
            + "ORDER BY created_at LIMIT #{limit})")
    int deleteLoginLogsBefore(@Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);
}
