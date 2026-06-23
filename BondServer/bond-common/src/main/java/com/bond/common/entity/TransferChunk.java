package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

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
}
