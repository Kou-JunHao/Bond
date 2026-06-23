using System.Text.Json.Serialization;

namespace BondClient.Models;

public class Workspace
{
    [JsonPropertyName("id")]
    public long Id { get; set; }

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("description")]
    public string? Description { get; set; }

    [JsonPropertyName("ownerId")]
    public long OwnerId { get; set; }

    [JsonPropertyName("maxSize")]
    public long MaxSize { get; set; }

    [JsonPropertyName("usedSize")]
    public long UsedSize { get; set; }

    [JsonPropertyName("createdAt")]
    public long? CreatedAt { get; set; }
}
