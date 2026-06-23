using System.Text.Json.Serialization;

namespace BondClient.Models;

public class ApiResult
{
    [JsonPropertyName("code")]
    public int Code { get; set; }

    [JsonPropertyName("message")]
    public string Message { get; set; } = "";

    public bool IsSuccess => Code == 200;
}

public class ApiResult<T> : ApiResult
{
    [JsonPropertyName("data")]
    public T? Data { get; set; }
}
