using System.Text.Json.Serialization;

namespace BondClient.Models;

public class WorkspaceFile
{
    [JsonPropertyName("id")]
    public long Id { get; set; }

    [JsonPropertyName("workspaceId")]
    public long WorkspaceId { get; set; }

    [JsonPropertyName("fileName")]
    public string FileName { get; set; } = "";

    [JsonPropertyName("fileSize")]
    public long FileSize { get; set; }

    [JsonPropertyName("minioPath")]
    public string MinioPath { get; set; } = "";

    [JsonPropertyName("contentType")]
    public string? ContentType { get; set; }

    [JsonPropertyName("uploadedBy")]
    public long UploadedBy { get; set; }

    [JsonPropertyName("parentPath")]
    public string ParentPath { get; set; } = "/";

    [JsonPropertyName("isDirectory")]
    public bool IsDirectory { get; set; }

    [JsonPropertyName("createdAt")]
    public long? CreatedAt { get; set; }
}
