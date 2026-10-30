using System;
using System.IO;
using CommunityToolkit.Mvvm.ComponentModel;

namespace Bond.Models;

/// <summary>上传队列里一条文件任务的状态（分片上传 / 断点续传，task-42）。</summary>
public enum UploadTaskStatus
{
    Queued,
    Hashing,
    Uploading,
    Paused,
    Failed,
    Completed,
    Cancelled
}

/// <summary>
/// 上传队列里的一条任务：文件路径、已传字节、实时速度、状态与后端 uploadId。
/// 进度只来自本机真实读写/网络字节，不做假数字。
/// </summary>
public sealed partial class UploadTaskItem : ObservableObject
{
    public UploadTaskItem(string localPath, string parentPath)
    {
        LocalPath = localPath;
        ParentPath = parentPath;
        FileName = Path.GetFileName(localPath);
        Key = Guid.NewGuid().ToString("N");
        try { TotalBytes = new FileInfo(localPath).Length; }
        catch { TotalBytes = 0; }
    }

    public string Key { get; }

    /// <summary>任务归属账号（JWT sub）。0 表示旧数据/未知归属，一律不展示、不续传。</summary>
    public long OwnerUserId { get; set; }

    public string LocalPath { get; }

    public string ParentPath { get; }

    public string FileName { get; }

    [ObservableProperty] private long _totalBytes;
    [ObservableProperty] private long _sentBytes;
    [ObservableProperty] private UploadTaskStatus _status = UploadTaskStatus.Queued;
    [ObservableProperty] private string? _errorMessage;
    [ObservableProperty] private double _speedBytesPerSecond;
    [ObservableProperty] private string? _uploadId;
    [ObservableProperty] private DateTimeOffset? _expiresAt;

    public bool IsActive => Status is UploadTaskStatus.Hashing or UploadTaskStatus.Uploading;

    public bool IsResumable => Status is UploadTaskStatus.Paused or UploadTaskStatus.Failed;

    public bool IsFinished => Status is UploadTaskStatus.Completed or UploadTaskStatus.Cancelled;

    public double Progress => TotalBytes <= 0
        ? 0
        : Math.Clamp(SentBytes / (double)TotalBytes, 0, 1);

    /// <summary>列表/平铺虚拟行上显示的百分比文本（task-42）。</summary>
    public string ProgressText => $"{Math.Round(Progress * 100, 0):0}%";

    public string SizeText => $"{FormatSize(SentBytes)} / {FormatSize(TotalBytes)}";

    public string SpeedText => SpeedBytesPerSecond <= 0
        ? ""
        : $"{FormatSize((long)SpeedBytesPerSecond)}/s";

    public string StatusText => Status switch
    {
        UploadTaskStatus.Queued => "排队中",
        UploadTaskStatus.Hashing => "计算校验…",
        UploadTaskStatus.Uploading => "上传中",
        UploadTaskStatus.Paused => "未完成 · 可续传",
        UploadTaskStatus.Failed => "失败",
        UploadTaskStatus.Completed => "已完成",
        UploadTaskStatus.Cancelled => "已取消",
        _ => ""
    };

    public void UpdateSent(long sent)
    {
        SentBytes = Math.Clamp(sent, 0, TotalBytes);
        OnPropertyChanged(nameof(Progress));
        OnPropertyChanged(nameof(ProgressText));
        OnPropertyChanged(nameof(SizeText));
    }

    public void UpdateSpeed(double bytesPerSecond)
    {
        SpeedBytesPerSecond = bytesPerSecond;
        OnPropertyChanged(nameof(SpeedText));
    }

    public void SetStatus(UploadTaskStatus status, string? error = null)
    {
        Status = status;
        ErrorMessage = error;
        OnPropertyChanged(nameof(IsActive));
        OnPropertyChanged(nameof(IsResumable));
        OnPropertyChanged(nameof(IsFinished));
        OnPropertyChanged(nameof(StatusText));
    }

    public static string FormatSize(long bytes)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        double value = bytes;
        var unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }

        return unit == 0 ? $"{bytes} B" : $"{value:0.#} {units[unit]}";
    }
}
