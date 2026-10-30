using System;
using System.Collections.Generic;
using Bond.Models;
using CommunityToolkit.Mvvm.ComponentModel;

namespace Bond.Models;

/// <summary>传输中心一行数据的来源（task-14）：设备中继任务 / 局域网直连任务 / 本机上传会话 / 本机下载会话。</summary>
public enum TransferRowKind
{
    Relay,
    LanDirect,
    Upload,
    Download
}

/// <summary>客户端会话方向（网盘上传、网盘下载）。</summary>
public enum TransferSessionKind
{
    Upload,
    Download
}

/// <summary>客户端会话状态。</summary>
public enum TransferSessionState
{
    Queued,
    Hashing,
    Running,
    Paused,
    Done,
    Failed,
    Canceled
}

/// <summary>
/// 最近 3 秒字节差的滑动窗口测速。样本不足或窗口内没有增长时返回 0，
/// 界面据此显示「—」，绝不编造速度。
/// </summary>
public sealed class TransferSpeedMeter
{
    private static readonly TimeSpan Window = TimeSpan.FromSeconds(3);
    private readonly List<(double Seconds, long Bytes)> _samples = [];

    public double BytesPerSecond
    {
        get
        {
            var now = Now();
            Trim(now);
            if (_samples.Count < 2) return 0;

            var first = _samples[0];
            var last = _samples[^1];
            var seconds = last.Seconds - first.Seconds;
            if (seconds < 0.25 || last.Bytes <= first.Bytes) return 0;

            return (last.Bytes - first.Bytes) / seconds;
        }
    }

    public void Record(long totalBytes)
    {
        var now = Now();
        _samples.Add((now, totalBytes));
        Trim(now);
    }

    public void Reset() => _samples.Clear();

    private static double Now() => Environment.TickCount64 / 1000.0;

    private void Trim(double now)
    {
        var limit = now - Window.TotalSeconds;
        var drop = 0;
        while (drop < _samples.Count && _samples[drop].Seconds < limit) drop++;
        if (drop > 0) _samples.RemoveRange(0, drop);
    }
}

/// <summary>
/// 客户端会话（task-14）：网盘上传或网盘下载。上传会话由 <see cref="UploadTaskItem"/> 镜像而来，
/// 下载会话由 <c>HttpBondStore.DownloadAsync</c> 在真实读流时推进；进度/速度都来自本机真实字节。
/// </summary>
public sealed partial class TransferSession : ObservableObject
{
    public TransferSession(TransferSessionKind kind, string id, string fileName, string targetLabel, long totalBytes)
    {
        Kind = kind;
        Id = id;
        FileName = fileName;
        TargetLabel = targetLabel;
        TotalBytes = totalBytes;
    }

    public TransferSessionKind Kind { get; }

    public string Id { get; }

    public string FileName { get; }

    /// <summary>目标空间或目标目录的可读名称（如「个人空间」「工作区 · 设计」）。</summary>
    public string TargetLabel { get; }

    public long TotalBytes { get; private set; }

    /// <summary>上传任务在计算校验后才拿到真实大小，允许会话总量后补。</summary>
    public void SetTotal(long totalBytes)
    {
        if (TotalBytes == totalBytes) return;

        TotalBytes = totalBytes;
        OnPropertyChanged(nameof(TotalBytes));
        OnPropertyChanged(nameof(Progress));
        OnPropertyChanged(nameof(ProgressText));
        OnPropertyChanged(nameof(SizeText));
    }

    /// <summary>上传：本地源文件；下载：本地目标文件（完成后用于「打开所在位置」）。</summary>
    public string? LocalPath { get; set; }

    /// <summary>下载取消用的取消源（由 store 注入，上传会话为 null）。</summary>
    internal System.Threading.CancellationTokenSource? Cancellation { get; set; }

    [ObservableProperty]
    private long _transferredBytes;

    [ObservableProperty]
    private TransferSessionState _state = TransferSessionState.Running;

    [ObservableProperty]
    private string? _errorMessage;

    [ObservableProperty]
    private double _speedBytesPerSecond;

    private readonly TransferSpeedMeter _meter = new();

    public double Progress => TotalBytes <= 0
        ? 0
        : Math.Clamp(TransferredBytes / (double)TotalBytes, 0, 1);

    public string ProgressText => $"{Progress * 100:0}%";

    public string SizeText => $"{Format(TransferredBytes)} / {Format(TotalBytes)}";

    public string SpeedText => SpeedBytesPerSecond <= 0 ? "—" : $"{Format((long)SpeedBytesPerSecond)}/s";

    public string StateText => State switch
    {
        TransferSessionState.Queued => Kind == TransferSessionKind.Upload ? "排队中" : "准备中",
        TransferSessionState.Hashing => "计算校验…",
        TransferSessionState.Running => Kind == TransferSessionKind.Upload ? "上传中" : "下载中",
        TransferSessionState.Paused => "已暂停",
        TransferSessionState.Done => "已完成",
        TransferSessionState.Failed => "失败",
        TransferSessionState.Canceled => "已取消",
        _ => string.Empty
    };

    public bool IsActive => State is TransferSessionState.Queued or TransferSessionState.Hashing
        or TransferSessionState.Running;

    public bool CanCancel => IsActive;

    public bool CanResume => Kind == TransferSessionKind.Upload
        && State is TransferSessionState.Paused or TransferSessionState.Failed;

    public bool CanOpenLocation => State == TransferSessionState.Done
        && !string.IsNullOrEmpty(LocalPath);

    partial void OnTransferredBytesChanged(long value)
    {
        OnPropertyChanged(nameof(Progress));
        OnPropertyChanged(nameof(ProgressText));
        OnPropertyChanged(nameof(SizeText));
    }

    partial void OnSpeedBytesPerSecondChanged(double value) => OnPropertyChanged(nameof(SpeedText));

    partial void OnStateChanged(TransferSessionState value)
    {
        OnPropertyChanged(nameof(StateText));
        OnPropertyChanged(nameof(IsActive));
        OnPropertyChanged(nameof(CanCancel));
        OnPropertyChanged(nameof(CanResume));
        OnPropertyChanged(nameof(CanOpenLocation));
    }

    /// <summary>记录一次真实字节推进（同时刷新 3 秒滑动窗口速度）。</summary>
    public void Record(long transferredBytes)
    {
        TransferredBytes = Math.Clamp(transferredBytes, 0, Math.Max(TotalBytes, transferredBytes));
        _meter.Record(TransferredBytes);
        SpeedBytesPerSecond = _meter.BytesPerSecond;
    }

    public void SetState(TransferSessionState state, string? error = null)
    {
        State = state;
        ErrorMessage = error;
        if (!IsActive)
        {
            _meter.Reset();
            SpeedBytesPerSecond = 0;
        }
    }

    /// <summary>用上传队列的一条真实任务刷新本会话（状态 / 已传字节）。</summary>
    public void ApplyUpload(UploadTaskItem task)
    {
        if (TransferredBytes != task.SentBytes) Record(task.SentBytes);

        var state = task.Status switch
        {
            UploadTaskStatus.Queued => TransferSessionState.Queued,
            UploadTaskStatus.Hashing => TransferSessionState.Hashing,
            UploadTaskStatus.Uploading => TransferSessionState.Running,
            UploadTaskStatus.Paused => TransferSessionState.Paused,
            UploadTaskStatus.Failed => TransferSessionState.Failed,
            UploadTaskStatus.Completed => TransferSessionState.Done,
            UploadTaskStatus.Cancelled => TransferSessionState.Canceled,
            _ => TransferSessionState.Queued
        };

        if (State != state) SetState(state, task.ErrorMessage);

        // 上传器按分片推进并自带瞬时速度，比 3 秒窗口样本更细；运行中优先采用它
        if (state == TransferSessionState.Running && task.SpeedBytesPerSecond > 0)
        {
            SpeedBytesPerSecond = task.SpeedBytesPerSecond;
        }
    }

    public static string Format(long bytes) => UploadTaskItem.FormatSize(bytes);
}

/// <summary>
/// 传输中心的只读行视图（task-14）：把「服务端传输任务」与「客户端上传/下载会话」统一成同一种展示模型。
/// 行对象跟随数据源属性变化实时刷新（进度 / 状态 / 速度），但不反向写数据。
/// </summary>
public sealed partial class TransferRow : ObservableObject
{
    private TransferRow(TransferItem? job, TransferSession? session)
    {
        Job = job;
        Session = session;

        if (job is not null)
        {
            Id = job.Id;
            job.PropertyChanged += OnSourceChanged;
        }
        else if (session is not null)
        {
            Id = session.Id;
            session.PropertyChanged += OnSourceChanged;
        }
        else
        {
            Id = string.Empty;
        }
    }

    public static TransferRow ForJob(TransferItem job) => new(job, null);

    public static TransferRow ForSession(TransferSession session) => new(null, session);

    public TransferItem? Job { get; }

    public TransferSession? Session { get; }

    public string Id { get; }

    public bool IsJob => Job is not null;

    public TransferRowKind Kind => Job is not null
        ? TransferRowKind.Relay
        : Session!.Kind == TransferSessionKind.Upload
            ? TransferRowKind.Upload
            : TransferRowKind.Download;

    public string KindText => Kind switch
    {
        TransferRowKind.Upload => "上传",
        TransferRowKind.Download => "下载",
        TransferRowKind.LanDirect => "局域网",
        _ => "设备"
    };

    public string FileName => Job?.FileName ?? Session?.FileName ?? string.Empty;

    /// <summary>设备名（任务）或目标空间（会话）。</summary>
    public string TargetText => Job?.PeerName ?? Session?.TargetLabel ?? string.Empty;

    public long Size => Job?.Size ?? Session?.TotalBytes ?? 0;

    public string SizeText => TransferSession.Format(Size);

    public double Progress => Job?.Progress ?? Session?.Progress ?? 0;

    public string ProgressText => Job?.ProgressText ?? Session?.ProgressText ?? "0%";

    /// <summary>任务进度由服务端分片计数而来，没有本机字节流可测速，如实显示「—」；不在传输的行不占速度列（task-101）。</summary>
    public string SpeedText => !ShowSpeed ? string.Empty : Job is not null ? "—" : Session!.SpeedText;

    /// <summary>
    /// 速度只属于真正在传字节的行：等待接收 / 已完成 / 失败 / 已取消都不显示（task-96、task-101）。
    /// </summary>
    public bool ShowSpeed => IsActive && !IsWaitingAccept;

    /// <summary>
    /// 接收方还在等对方开始传（PENDING）时进度恒为 0，显示 0% 会误导，因此不画百分比与进度条（task-101）；
    /// 暂停中的行仍保留真实进度（只是不再走字节，速度列已隐藏），已完成 / 失败 / 取消 / 过期不显示进度。
    /// </summary>
    public bool ShowProgress => !IsWaitingAccept
        && (IsActive
            || Job is { State: TransferState.Paused }
            || Session is { State: TransferSessionState.Paused });

    /// <summary>接收方视角的等待态叫「等待接收」，发送方仍是「等待中」（task-94）。</summary>
    public string StateText => Job is { State: TransferState.Pending, IsIncoming: true }
        ? "等待接收"
        : Job?.StateText ?? Session!.StateText;

    private bool IsWaitingAccept => Job is { State: TransferState.Pending, IsIncoming: true };

    /// <summary>行尾动作的语义提示：任务取消与上传/下载取消要能区分（task-101）。</summary>
    public string CancelTip => Job is not null
        ? "取消该任务（对方将收不到这个文件）"
        : Kind == TransferRowKind.Upload ? "取消这次上传" : "取消这次下载";

    public bool IsActive => Job?.IsActive ?? Session?.IsActive ?? false;

    // task-101 复核：接收方「等待接收」行只给「接受 / 拒绝」，不再同时给「取消」——
    // 拒绝本身就是接收方的取消语义，两个红叉并排会让用户分不清哪个才是撤回。
    public bool CanCancel => Job is { State: TransferState.Pending, IsIncoming: true }
        ? false
        : Job?.IsActive ?? Session?.CanCancel ?? false;

    public bool CanRetry => Job?.CanRetry ?? false;

    public bool CanResume => Session?.CanResume ?? false;

    public bool CanOpenLocation => Session?.CanOpenLocation ?? false;

    /// <summary>对方发过来、仍在等待接收的任务 —— 可点「接受」（POST /api/transfer/tasks/{id}/accept）。</summary>
    public bool CanAccept => Job is { State: TransferState.Pending, IsIncoming: true };

    /// <summary>与接受同条件 —— 可点「拒绝」（POST /api/transfer/tasks/{id}/reject）。</summary>
    public bool CanReject => Job is { State: TransferState.Pending, IsIncoming: true };

    public Material.Icons.MaterialIconKind Icon =>
        FileVisuals.Icon(FileVisuals.Classify(FileName, NodeKind.File));

    public Avalonia.Media.IBrush IconBrush => FileVisuals.Brush(FileVisuals.Classify(FileName, NodeKind.File));

    public Avalonia.Media.IBrush IconBackground =>
        FileVisuals.Background(FileVisuals.Classify(FileName, NodeKind.File));

    public Avalonia.Media.IBrush StateBrush => Avalonia.Media.Brush.Parse(StateText switch
    {
        "传输中" or "上传中" or "下载中" => "#5D7FA8",
        "已完成" => "#5D7358",
        "失败" or "已拒绝" => "#C0392B",
        "已过期" => "#B7791F",
        _ => "#9A948B"
    });

    /// <summary>
    /// 行的第二行明细：状态 · 设备/空间 · 大小。
    /// 目标为空时整段省略（包括分隔符）；上传到个人空间根目录的「/」不是有效目标，也按空处理（task-101）。
    /// </summary>
    public string DetailText
    {
        get
        {
            var target = TargetText?.Trim() ?? string.Empty;

            // 路径型目标（网盘上传/下载会话给的是目录路径）去掉前导斜杠：
            // 「/新建文件夹」读起来像绝对路径，用户只需要知道落在哪个目录。
            while (target.Length > 1 && target[0] == '/')
            {
                target = target[1..];
            }

            if (target is "" or "/")
            {
                return $"{StateText} · {SizeText}";
            }

            return $"{StateText} · {target} · {SizeText}";
        }
    }

    private void OnSourceChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        OnPropertyChanged(nameof(Progress));
        OnPropertyChanged(nameof(ProgressText));
        OnPropertyChanged(nameof(SpeedText));
        OnPropertyChanged(nameof(ShowSpeed));
        OnPropertyChanged(nameof(ShowProgress));
        OnPropertyChanged(nameof(StateText));
        OnPropertyChanged(nameof(StateBrush));
        OnPropertyChanged(nameof(IsActive));
        OnPropertyChanged(nameof(CanCancel));
        OnPropertyChanged(nameof(CanRetry));
        OnPropertyChanged(nameof(CanAccept));
        OnPropertyChanged(nameof(CanReject));
        OnPropertyChanged(nameof(CanResume));
        OnPropertyChanged(nameof(CanOpenLocation));
        OnPropertyChanged(nameof(DetailText));
    }

    /// <summary>从数据源解除订阅（行从视图移除时调用，避免长生命周期对象泄漏）。</summary>
    public void Detach()
    {
        if (Job is not null) Job.PropertyChanged -= OnSourceChanged;
        if (Session is not null) Session.PropertyChanged -= OnSourceChanged;
    }
}
