package com.bond.common.dto;

import lombok.Data;
import java.util.List;

@Data
public class TransferTaskDTO {
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
    private Long expiresAt;
    private Long createdAt;
    private List<ChunkDTO> chunks;
}
