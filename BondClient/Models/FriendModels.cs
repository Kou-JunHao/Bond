using System.Text.Json.Serialization;

namespace BondClient.Models;

public class FriendInfo
{
    [JsonPropertyName("friendUserId")]
    public long FriendUserId { get; set; }

    [JsonPropertyName("username")]
    public string Username { get; set; } = "";

    [JsonPropertyName("nickname")]
    public string Nickname { get; set; } = "";

    [JsonPropertyName("avatarUrl")]
    public string? AvatarUrl { get; set; }

    [JsonPropertyName("isOnline")]
    public bool IsOnline { get; set; }

    [JsonPropertyName("since")]
    public long? Since { get; set; }
}

public class FriendRequestInfo
{
    [JsonPropertyName("id")]
    public long Id { get; set; }

    [JsonPropertyName("fromUserId")]
    public long FromUserId { get; set; }

    [JsonPropertyName("fromUsername")]
    public string FromUsername { get; set; } = "";

    [JsonPropertyName("fromNickname")]
    public string FromNickname { get; set; } = "";

    [JsonPropertyName("message")]
    public string? Message { get; set; }

    [JsonPropertyName("createdAt")]
    public long? CreatedAt { get; set; }
}

public class UserSearchResult
{
    [JsonPropertyName("id")]
    public long Id { get; set; }

    [JsonPropertyName("username")]
    public string Username { get; set; } = "";

    [JsonPropertyName("nickname")]
    public string Nickname { get; set; } = "";

    [JsonPropertyName("avatarUrl")]
    public string? AvatarUrl { get; set; }
}
