package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import java.util.List;

/**
 * 传输任务对外 JSON 契约（客户端 BondClient 按 lowerCamelCase 解析）。
 *
 * <p>task-107：显式钉死命名策略。此前契约依赖 Jackson 的默认命名；一旦某个环境
 * 的 {@code spring.jackson.property-naming-strategy} 被配成 SNAKE_CASE，
 * 同一份 DTO 就会输出 {@code uploaded_chunks / chunk_count}，
 * 导致客户端读不到 {@code uploadedChunks/chunkCount}（表现为进度为 0）。
 * 类级 {@code @JsonNaming} 优先级高于全局 ObjectMapper 配置，保证任何环境下
 * 字段名都是 {@code uploadedChunks / chunkCount / fileName / createdAt ...}。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public class TransferTaskDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long senderId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long receiverId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long senderDeviceId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long receiverDeviceId;
    private String fileName;
    private Long fileSize;
    private Integer chunkSize;
    private Integer chunkCount;
    private String minioPath;
    private String encryptedKey;
    private Integer status;
    private Integer uploadedChunks;

    /** 接收方已下载分片数（V13）；仅 receiver 下载时自增，发送方下载不计数。 */
    private Integer downloadedChunks;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long workspaceId;
    private String transferType;
    private Long expiresAt;
    private Long createdAt;
    private List<ChunkDTO> chunks;

    // ── task-54：传输中心可见性字段 ──

    /** 已完成字节数（uploadedChunks × chunkSize，封顶 fileSize）。 */
    private Long progressBytes;

    /** 总字节数，等同 fileSize；单独给出便于客户端统一使用。 */
    private Long totalBytes;

    /**
     * 实时速度（字节/秒）。服务端不保存逐任务测速历史，**本期固定为 null**，
     * 由客户端按两次轮询的 progressBytes 差值自算；为将来预留字段。
     */
    private Long speedBytesPerSec;

    /** 对端显示名：direction=up/sent 时是接收方，direction=down/received 时是发送方。 */
    private String peerName;
}
