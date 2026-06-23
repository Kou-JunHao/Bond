using System.Text.Json.Serialization;

namespace BondClient.Models;

public class TransferTask
{
    [JsonPropertyName("id")]
    public long Id { get; set; }

    [JsonPropertyName("senderId")]
    public long SenderId { get; set; }

    [JsonPropertyName("receiverId")]
    public long ReceiverId { get; set; }

    [JsonPropertyName("senderDeviceId")]
    public long? SenderDeviceId { get; set; }

    [JsonPropertyName("receiverDeviceId")]
    public long? ReceiverDeviceId { get; set; }

    [JsonPropertyName("fileName")]
    public string FileName { get; set; } = "";

    [JsonPropertyName("fileSize")]
    public long FileSize { get; set; }

    [JsonPropertyName("chunkSize")]
    public int ChunkSize { get; set; }

    [JsonPropertyName("chunkCount")]
    public int ChunkCount { get; set; }

    [JsonPropertyName("minioPath")]
    public string? MinioPath { get; set; }

    [JsonPropertyName("encryptedKey")]
    public string? EncryptedKey { get; set; }

    [JsonPropertyName("status")]
    public int Status { get; set; }

    [JsonPropertyName("uploadedChunks")]
    public int UploadedChunks { get; set; }

    [JsonPropertyName("workspaceId")]
    public long? WorkspaceId { get; set; }

    [JsonPropertyName("expiresAt")]
    public long? ExpiresAt { get; set; }

    [JsonPropertyName("createdAt")]
    public long? CreatedAt { get; set; }
}
