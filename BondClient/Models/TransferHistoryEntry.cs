using System.Text.Json.Serialization;

namespace BondClient.Models;

public enum TransferDirection
{
    Sent,
    Received
}

public class TransferHistoryEntry
{
    [JsonPropertyName("fileName")]
    public string FileName { get; set; } = "";

    [JsonPropertyName("fileSize")]
    public long FileSize { get; set; }

    [JsonPropertyName("direction")]
    public TransferDirection Direction { get; set; }

    [JsonPropertyName("timestamp")]
    public long Timestamp { get; set; }

    [JsonPropertyName("deviceName")]
    public string DeviceName { get; set; } = "";

    [JsonIgnore]
    public DateTime Time => DateTimeOffset.FromUnixTimeMilliseconds(Timestamp).LocalDateTime;

    [JsonIgnore]
    public string SizeText
    {
        get
        {
            string[] units = ["B", "KB", "MB", "GB"];
            double size = FileSize;
            int unit = 0;
            while (size >= 1024 && unit < units.Length - 1) { size /= 1024; unit++; }
            return $"{size:F1} {units[unit]}";
        }
    }

    [JsonIgnore]
    public string TimeAgo
    {
        get
        {
            var span = DateTime.Now - Time;
            if (span.TotalSeconds < 60) return "刚刚";
            if (span.TotalMinutes < 60) return $"{(int)span.TotalMinutes} 分钟前";
            if (span.TotalHours < 24) return $"{(int)span.TotalHours} 小时前";
            return Time.ToString("MM-dd HH:mm");
        }
    }
}
