using System.Text.Json.Serialization;

namespace BondClient.Models;

public class DeviceModel
{
    [JsonPropertyName("id")]
    public long Id { get; set; }

    [JsonPropertyName("userId")]
    public long UserId { get; set; }

    [JsonPropertyName("deviceName")]
    public string DeviceName { get; set; } = "";

    [JsonPropertyName("deviceType")]
    public string DeviceType { get; set; } = "";

    [JsonPropertyName("publicKey")]
    public string? PublicKey { get; set; }

    [JsonPropertyName("isOnline")]
    public bool IsOnline { get; set; }

    [JsonPropertyName("lastSeenAt")]
    public long? LastSeenAt { get; set; }
}
