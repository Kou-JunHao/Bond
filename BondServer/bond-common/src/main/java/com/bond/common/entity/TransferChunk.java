package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Data
@TableName("transfer_chunks")
public class TransferChunk {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long taskId;
    private Integer chunkIndex;
    private Long chunkSize;
    private String etag;
    private Integer status;
    private LocalDateTime createdAt;

    /**
     * 接收方首次取走该分片的时间（V13，TIMESTAMPTZ）；NULL=未下载。
     * 由下载接口以「NULL → now()」单向迁移做幂等标记，非 NULL 后不再重写。
     */
    private OffsetDateTime downloadedAt;
}
