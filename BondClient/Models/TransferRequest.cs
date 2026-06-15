using System.Collections.Generic;
using System.Text.Json.Serialization;

namespace BondClient.Models;

public class TransferRequest
{
    [JsonPropertyName("fromId")]
    public string FromId { get; set; } = "";

    [JsonPropertyName("fromName")]
    public string FromName { get; set; } = "";

    [JsonPropertyName("files")]
    public List<FileEntry> Files { get; set; } = [];

    [JsonPropertyName("totalSize")]
    public long TotalSize { get; set; }

    [JsonPropertyName("password")]
    public string? Password { get; set; }
}

public class FileEntry
{
    [JsonPropertyName("relativePath")]
    public string RelativePath { get; set; } = "";

    [JsonPropertyName("size")]
    public long Size { get; set; }

    [JsonPropertyName("isDirectory")]
    public bool IsDirectory { get; set; }
}
