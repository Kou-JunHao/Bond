package com.bond.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("transfer_tasks")
public class TransferTask {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long senderId;
    private Long receiverId;
    private Long senderDeviceId;
    private Long receiverDeviceId;
    private String fileName;
    private Long fileSize;
    private Integer chunkSize;
    private Integer chunkCount;
    private String minioPath;
    private String encryptedKey;
    private Integer status;
    private Integer uploadedChunks;
    private Long workspaceId;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
