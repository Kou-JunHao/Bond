package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.TransferChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TransferChunkMapper extends BaseMapper<TransferChunk> {

    /** 已成功接收的分片字节总和；in 提供累计上限校验依据。 */
    @Select("SELECT COALESCE(SUM(chunk_size), 0) FROM transfer_chunks "
            + "WHERE task_id = #{taskId} AND status = 1")
    Long sumUploadedBytes(@Param("taskId") Long taskId);

    /**
     * V13：接收方首次取走分片时打点，幂等（只有 downloaded_at 还是 NULL 才写）。
     *
     * <p>返回 1 表示本次完成了「未下载 → 已下载」状态迁移，调用方据此决定是否给
     * {@code transfer_tasks.downloaded_chunks} 加一；并发/重复下载同一片只会有一个请求拿到 1。</p>
     */
    @Update("UPDATE transfer_chunks SET downloaded_at = now() "
            + "WHERE id = #{id} AND downloaded_at IS NULL")
    int markDownloaded(@Param("id") Long id);

    /**
     * task-109：校验失败后重置整任务的分片状态。
     *
     * <p>不能用 {@code updateById} 逐片重置——MyBatis-Plus 默认跳过 null 字段，
     * {@code setEtag(null)} 不会落库，旧 etag 会残留。这里显式 SET etag = NULL。</p>
     */
    @Update("UPDATE transfer_chunks SET status = 0, etag = NULL WHERE task_id = #{taskId}")
    int resetByTaskId(@Param("taskId") Long taskId);
}
