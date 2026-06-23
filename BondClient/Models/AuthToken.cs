using System.Text.Json.Serialization;

namespace BondClient.Models;

public class AuthToken
{
    [JsonPropertyName("accessToken")]
    public string AccessToken { get; set; } = "";

    [JsonPropertyName("refreshToken")]
    public string RefreshToken { get; set; } = "";

    [JsonPropertyName("expiresAt")]
    public long ExpiresAt { get; set; }
}
