using System.Text.Json.Serialization;

namespace BondClient.Models;

public class TransferChunkInfo
{
    [JsonPropertyName("id")]
    public long Id { get; set; }

    [JsonPropertyName("taskId")]
    public long TaskId { get; set; }

    [JsonPropertyName("chunkIndex")]
    public int ChunkIndex { get; set; }

    [JsonPropertyName("chunkSize")]
    public long ChunkSize { get; set; }

    [JsonPropertyName("etag")]
    public string? Etag { get; set; }

    [JsonPropertyName("status")]
    public int Status { get; set; }
}
