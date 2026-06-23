using System.Text.Json.Serialization;

namespace BondClient.Models;

public class ResumeState
{
    [JsonPropertyName("taskId")]
    public string TaskId { get; set; } = "";

    [JsonPropertyName("fileName")]
    public string FileName { get; set; } = "";

    [JsonPropertyName("fileSize")]
    public long FileSize { get; set; }

    [JsonPropertyName("chunkSize")]
    public int ChunkSize { get; set; }

    [JsonPropertyName("uploadedChunks")]
    public List<int> UploadedChunks { get; set; } = new();

    [JsonPropertyName("encryptedAesKey")]
    public string? EncryptedAesKey { get; set; }

    [JsonPropertyName("createdAt")]
    public string CreatedAt { get; set; } = "";
}
