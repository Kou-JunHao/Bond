package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.TransferTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TransferTaskMapper extends BaseMapper<TransferTask> {

    /**
     * V13：接收方下载分片计数原子自增（不做读-改-写，避免并发丢更新）。
     *
     * <p>只在 {@link TransferChunkMapper#markDownloaded(Long)} 返回 1（首次下载）时调用，
     * 因此前端重试/断点续传重复拉同一片不会重复累加。</p>
     */
    @Update("UPDATE transfer_tasks SET downloaded_chunks = downloaded_chunks + 1 WHERE id = #{id}")
    int incrementDownloadedChunks(@Param("id") Long id);
}
