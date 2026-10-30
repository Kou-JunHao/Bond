using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Threading;
using Bond.Models;
using Bond.Services;
using Bond.Services.Updates;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Bond.ViewModels;

/// <summary>
/// task-86：设置页「关于与更新」。
/// task-141：检查频率 / 自动下载 / 通道交给用户选择（默认全部关闭、默认从不检查）；
/// <c>--auto-update</c> 与界面「立即检查」共用 <see cref="IUpdateService"/>，校验与安装只有一份实现。
/// </summary>
public partial class SettingsPageViewModel
{
    private static readonly UpdateFrequency[] FrequencyValues =
    [
        UpdateFrequency.Never,
        UpdateFrequency.OnLaunch,
        UpdateFrequency.Daily,
        UpdateFrequency.Weekly,
    ];

    private ISoftwareService? _softwareService;
    private IUpdateService? _updateService;
    private IUpdatePolicy? _updatePolicy;
    private SoftwareReleaseInfo? _pendingRelease;
    private CancellationTokenSource? _downloadCts;
    private bool _syncingUpdatePreferences;
    private DateTimeOffset _downloadStartedAt;
    private double _downloadLastPercent;

    private ISoftwareService? Software =>
        _softwareService ??= App.Services.GetService(typeof(ISoftwareService)) as ISoftwareService;

    private IUpdateService? Updates =>
        _updateService ??= App.Services.GetService(typeof(IUpdateService)) as IUpdateService;

    private IUpdatePolicy? Policy
    {
        get
        {
            if (_updatePolicy is not null) return _updatePolicy;
            _updatePolicy = App.Services.GetService(typeof(IUpdatePolicy)) as IUpdatePolicy;
            if (_updatePolicy is not null) SyncUpdatePreferences();
            return _updatePolicy;
        }
    }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsUpdateChecking))]
    [NotifyPropertyChangedFor(nameof(HasUpdate))]
    [NotifyPropertyChangedFor(nameof(IsUpToDate))]
    [NotifyPropertyChangedFor(nameof(HasNoPackage))]
    [NotifyPropertyChangedFor(nameof(IsDownloadingUpdate))]
    [NotifyPropertyChangedFor(nameof(HasVerifyFailed))]
    [NotifyPropertyChangedFor(nameof(HasUpdateError))]
    [NotifyPropertyChangedFor(nameof(HasDownloaded))]
    [NotifyPropertyChangedFor(nameof(IsUpdateIdle))]
    [NotifyPropertyChangedFor(nameof(IsUpdateIgnored))]
    [NotifyPropertyChangedFor(nameof(ShowUpdateCard))]
    private UpdateState _updateState = UpdateState.Idle;

    /// <summary>主状态文案（如「已是最新版本」「发现新版本 2.0.1」「暂无可用安装包」）。</summary>
    [ObservableProperty] private string _updateStatusText = "尚未检查更新";

    /// <summary>副文案：版本 / 大小 / 发布时间。</summary>
    [ObservableProperty] private string _updateDetailText = "";

    /// <summary>旧版本（已最新时显示；有更新时显示当前版本）。</summary>
    [ObservableProperty] private string _currentVersionText = "";

    [ObservableProperty] private string _latestVersionText = "";

    [ObservableProperty] private string _updateNotesText = "";

    [ObservableProperty] private bool _hasUpdateNotes;

    /// <summary>更新说明折叠。</summary>
    [ObservableProperty] private bool _areUpdateNotesExpanded;

    [ObservableProperty] private double _downloadProgress;

    [ObservableProperty] private string _downloadProgressText = "";

    [ObservableProperty] private string _downloadStatusText = "";

    [ObservableProperty] private string _downloadSpeedText = "";

    [ObservableProperty] private string? _downloadedPath;

    [ObservableProperty] private string _checkedAtText = "";

    /// <summary>强制更新横幅：不可关闭，只能去下载安装。</summary>
    [ObservableProperty] private bool _isForceUpdate;

    [ObservableProperty] private string _forceUpdateText = "";

    [ObservableProperty] private string _updateChecksumText = "";

    [ObservableProperty] private string _updateChecksumFull = "";

    [ObservableProperty] private bool _isInstallerStarted;

    /// <summary>检查频率：0 从不 / 1 每次启动 / 2 每天一次 / 3 每周一次。</summary>
    [ObservableProperty] private int _updateFrequencyIndex;

    [ObservableProperty] private bool _updateAutoDownload;

    /// <summary>更新通道：0 稳定版 / 1 测试通道。</summary>
    [ObservableProperty] private int _updateChannelIndex;

    [ObservableProperty] private bool _hasIgnoredVersion;

    [ObservableProperty] private string _ignoredVersionText = "";

    /// <summary>通道提示：测试通道暂未开放时如实说明（不做假 UI）。</summary>
    [ObservableProperty] private string _updateChannelHint = "";

    public string AboutVersionText => Software?.CurrentVersion ?? "—";

    public bool IsUpdateChecking => UpdateState == UpdateState.Checking;
    public bool HasUpdate => UpdateState == UpdateState.Available;
    public bool IsUpToDate => UpdateState == UpdateState.UpToDate;
    public bool HasNoPackage => UpdateState == UpdateState.NoPackage;
    public bool IsDownloadingUpdate => UpdateState == UpdateState.Downloading;
    public bool HasVerifyFailed => UpdateState == UpdateState.VerifyFailed;
    public bool HasUpdateError => UpdateState == UpdateState.Error;
    public bool HasDownloaded => UpdateState == UpdateState.Downloaded;
    public bool IsUpdateIdle => UpdateState == UpdateState.Idle;
    public bool IsUpdateIgnored => UpdateState == UpdateState.Ignored;

    /// <summary>有内容的状态卡片（从不检查 + 未查过时不占位）。</summary>
    public bool ShowUpdateCard => UpdateState != UpdateState.Idle || CheckedAtText.Length > 0;

    [RelayCommand]
    private async Task CheckUpdateAsync()
    {
        var update = Updates;
        if (update is null)
        {
            UpdateState = UpdateState.Error;
            UpdateStatusText = "更新服务不可用";
            UpdateDetailText = "";
            return;
        }

        if (UpdateState == UpdateState.Downloading) return;

        CheckedAtText = "";
        IsForceUpdate = false;
        ForceUpdateText = "";
        UpdateState = UpdateState.Checking;
        UpdateStatusText = "正在检查更新…";
        UpdateDetailText = "";
        UpdateNotesText = "";
        HasUpdateNotes = false;

        var result = await update.CheckAsync(Policy?.Channel ?? UpdateChannel.Stable).ConfigureAwait(true);
        _pendingRelease = result.Release;
        Policy?.RecordChecked(DateTimeOffset.Now);

        var currentVersion = result.CurrentVersion.Length > 0 ? result.CurrentVersion : update.CurrentVersion;
        CurrentVersionText = currentVersion;
        LatestVersionText = result.LatestVersion;
        CheckedAtText = $"上次检查：{DateTime.Now:HH:mm}";

        // 用户选择过「忽略此版本」且不是强制更新 → 保持安静，但仍允许手动查看。
        var ignored = Policy?.IgnoredVersion;
        if (result.State == UpdateState.Available
            && !result.ForceUpdate
            && !string.IsNullOrEmpty(ignored)
            && string.Equals(ignored, result.LatestVersion, StringComparison.Ordinal))
        {
            UpdateState = UpdateState.Ignored;
            UpdateStatusText = $"已忽略版本 {result.LatestVersion}";
            UpdateDetailText = $"当前版本 {currentVersion}；需要时点「恢复提示」即可重新看到该版本。";
            RefreshIgnoredVersion();
            return;
        }

        UpdateState = result.State;
        UpdateStatusText = result.Message;

        switch (result.State)
        {
            case UpdateState.Available:
                IsForceUpdate = result.ForceUpdate;
                ForceUpdateText = result.ForceUpdate
                    ? $"当前版本 {currentVersion} 已不再受支持，请更新到 {result.LatestVersion} 后继续使用。"
                    : "";
                UpdateDetailText = _pendingRelease is null
                    ? ""
                    : $"{_pendingRelease.Version} · {FormatSize(_pendingRelease.FileSize)} · {FormatDate(_pendingRelease.PublishedAt)}";
                UpdateChecksumFull = _pendingRelease?.Sha256 ?? "";
                UpdateChecksumText = UpdateChecksumFull.Length > 0
                    ? $"SHA-256 {UpdateChecksumFull[..Math.Min(16, UpdateChecksumFull.Length)]}…"
                    : "SHA-256 —";
                UpdateNotesText = _pendingRelease?.ReleaseNotes ?? "";
                HasUpdateNotes = !string.IsNullOrWhiteSpace(UpdateNotesText);
                break;

            case UpdateState.UpToDate:
                UpdateDetailText = $"当前版本 {currentVersion}";
                break;

            case UpdateState.NoPackage:
                UpdateDetailText = $"当前版本 {currentVersion}";
                break;

            default:
                UpdateDetailText = "";
                break;
        }

        RefreshIgnoredVersion();
        RefreshUpdateChannelHint();
    }

    [RelayCommand]
    private async Task DownloadUpdateAsync()
    {
        var update = Updates;
        var release = _pendingRelease;
        if (update is null || release is null || UpdateState == UpdateState.Downloading) return;

        _downloadCts?.Cancel();
        _downloadCts = new CancellationTokenSource();
        var ct = _downloadCts.Token;

        UpdateState = UpdateState.Downloading;
        DownloadProgress = 0;
        DownloadProgressText = "0%";
        DownloadStatusText = "准备下载…";
        DownloadSpeedText = "";
        _downloadStartedAt = DateTimeOffset.Now;
        _downloadLastPercent = 0;

        var progress = new Progress<double>(value =>
        {
            var clamped = Math.Clamp(value, 0, 100);
            DownloadProgress = clamped;
            DownloadProgressText = $"{Math.Round(clamped)}%";
            UpdateDownloadSpeed(release.FileSize, clamped);
        });

        UpdateStatusText = $"正在下载 {release.Version}";

        SoftwareDownloadResult result;
        try
        {
            result = await update.DownloadAsync(progress, ct).ConfigureAwait(true);
        }
        catch (OperationCanceledException)
        {
            UpdateState = UpdateState.Available;
            UpdateStatusText = $"已取消下载 {release.Version}";
            UpdateDetailText = "安装包未下载；需要时重新点击下载。";
            DownloadStatusText = "已取消";
            DownloadSpeedText = "";
            return;
        }

        DownloadSpeedText = "";

        if (result.Success)
        {
            DownloadedPath = result.Path;
            DownloadProgress = 100;
            DownloadProgressText = "100%";
            DownloadStatusText = result.Message;
            UpdateState = UpdateState.Downloaded;
            UpdateStatusText = $"已下载 {release.Version}";
            UpdateDetailText = $"文件：{Path.GetFileName(result.Path ?? "")}";
            return;
        }

        DownloadedPath = null;
        DownloadStatusText = result.Message;

        // 真假校验失败要分清：只有真拿到过实际 SHA-256 才是「校验失败」，
        // 网络/HTTP 失败（ActualSha256 为空）属于「下载失败」，不能误标成校验失败。
        if (result.ActualSha256 is not null)
        {
            UpdateState = UpdateState.VerifyFailed;
            UpdateStatusText = "安装包校验失败";
            UpdateDetailText = result.Message;
            return;
        }

        UpdateState = UpdateState.Error;
        UpdateStatusText = "下载失败";
        UpdateDetailText = result.Message;
    }

    [RelayCommand]
    private void CancelDownload() => _downloadCts?.Cancel();

    [RelayCommand]
    private void OpenUpdatesFolder()
    {
        var directory = SoftwareService.UpdatesDirectory;
        try
        {
            Directory.CreateDirectory(directory);
            Process.Start(new ProcessStartInfo("explorer.exe", directory) { UseShellExecute = true });
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException or IOException)
        {
            DownloadStatusText = $"无法打开文件夹：{ex.Message}";
        }
    }

    /// <summary>
    /// 打开已下载的安装包（交给系统执行，用户在安装向导里确认；本应用不静默安装）。
    /// 启动后提示用户安装完再退出 Bond，不偷偷退出进程。
    /// </summary>
    [RelayCommand]
    private void OpenInstaller()
    {
        var update = Updates;
        var result = update?.Install() ?? UpdateInstallResult.Failed("更新服务不可用");
        if (result.Started)
        {
            IsInstallerStarted = true;
            DownloadStatusText = "安装程序已启动";
            UpdateStatusText = "安装程序已启动";
            UpdateDetailText = "按安装向导完成后，点「退出 Bond」再重新打开即可使用新版本。";
            return;
        }

        UpdateState = UpdateState.Error;
        UpdateStatusText = "安装包不存在";
        UpdateDetailText = result.Message;
    }

    [RelayCommand]
    private void ExitForInstall()
    {
        if (Avalonia.Application.Current is App app)
        {
            app.ExitApplication();
        }
    }

    [RelayCommand]
    private void ToggleUpdateNotes() => AreUpdateNotesExpanded = !AreUpdateNotesExpanded;

    /// <summary>清除「忽略此版本」，让被忽略的版本重新出现在检查结果里。</summary>
    [RelayCommand]
    private void RestoreIgnoredVersion()
    {
        Policy?.IgnoreVersion(null);
        RefreshIgnoredVersion();
        if (UpdateState == UpdateState.Ignored)
        {
            UpdateState = UpdateState.Available;
            UpdateStatusText = _pendingRelease is null ? "已恢复提示" : $"发现新版本 {_pendingRelease.Version}，可下载安装包更新";
        }
    }

    /// <summary>把当前版本加入忽略列表（只忽略这一次的版本，不影响以后的版本）。</summary>
    [RelayCommand]
    private void IgnoreCurrentUpdate()
    {
        if (UpdateState != UpdateState.Available) return;
        var version = LatestVersionText.Length > 0 ? LatestVersionText : _pendingRelease?.Version;
        Policy?.IgnoreVersion(version);
        UpdateState = UpdateState.Ignored;
        UpdateStatusText = $"已忽略版本 {version}";
        UpdateDetailText = "手动「立即检查」仍可看到该版本；点「恢复提示」可撤销。";
        RefreshIgnoredVersion();
    }

    /// <summary>页面加载时刷新「关于」区版本号（task-86 要求从程序集读）。</summary>
    public void RefreshAbout()
    {
        OnPropertyChanged(nameof(AboutVersionText));
        if (CurrentVersionText.Length == 0)
        {
            CurrentVersionText = Software?.CurrentVersion ?? "";
        }

        SyncUpdatePreferences();
        RefreshIgnoredVersion();
        RefreshUpdateChannelHint();
        if (UpdateState == UpdateState.Idle)
        {
            UpdateStatusText = FrequencyText(UpdateFrequencyIndex);
        }

        OnPropertyChanged(nameof(ShowUpdateCard));
    }

    /// <summary>
    /// task-141：启动时按用户选择的频率决定是否自动检查。默认「从不」时什么也不做。
    /// </summary>
    public async Task RunScheduledCheckAsync()
    {
        var update = Updates;
        var policy = Policy;
        if (update is null || policy is null) return;
        if (!policy.IsDue(DateTimeOffset.Now)) return;

        await CheckUpdateAsync().ConfigureAwait(true);

        if (policy.AutoDownload && UpdateState == UpdateState.Available)
        {
            await DownloadUpdateAsync().ConfigureAwait(true);
        }
    }

    partial void OnUpdateFrequencyIndexChanged(int value)
    {
        if (_syncingUpdatePreferences) return;
        var policy = Policy;
        if (policy is null) return;

        var frequency = value >= 0 && value < FrequencyValues.Length ? FrequencyValues[value] : UpdateFrequency.Never;
        policy.SetFrequency(frequency);
        if (UpdateState == UpdateState.Idle)
        {
            UpdateStatusText = FrequencyText(value);
        }
    }

    partial void OnUpdateAutoDownloadChanged(bool value)
    {
        if (_syncingUpdatePreferences) return;
        Policy?.SetAutoDownload(value);
    }

    partial void OnUpdateChannelIndexChanged(int value)
    {
        if (_syncingUpdatePreferences) return;
        Policy?.SetChannel(value == 1 ? UpdateChannel.Beta : UpdateChannel.Stable);
        RefreshUpdateChannelHint();
    }

    private void SyncUpdatePreferences()
    {
        var policy = Policy;
        if (policy is null) return;

        _syncingUpdatePreferences = true;
        try
        {
            UpdateFrequencyIndex = IndexOf(FrequencyValues, policy.Frequency);
            UpdateAutoDownload = policy.AutoDownload;
            UpdateChannelIndex = policy.Channel == UpdateChannel.Beta ? 1 : 0;
        }
        finally
        {
            _syncingUpdatePreferences = false;
        }
    }

    private void RefreshIgnoredVersion()
    {
        var ignored = Policy?.IgnoredVersion;
        HasIgnoredVersion = !string.IsNullOrEmpty(ignored);
        IgnoredVersionText = HasIgnoredVersion ? $"已忽略版本 {ignored}（手动检查仍会显示）" : "";
    }

    private void RefreshUpdateChannelHint()
    {
        // 服务端目前只开放稳定通道；如实提示，不做假的 beta 下载。
        UpdateChannelHint = UpdateChannelIndex == 1
            ? "测试通道尚未开放，检查暂时仍按稳定版进行。"
            : "";
        OnPropertyChanged(nameof(ShowUpdateCard));
    }

    private void UpdateDownloadSpeed(long fileSize, double percent)
    {
        var elapsed = DateTimeOffset.Now - _downloadStartedAt;
        if (elapsed.TotalSeconds < 1 || fileSize <= 0 || percent <= _downloadLastPercent)
        {
            return;
        }

        var deltaBytes = (long)(fileSize * (percent - _downloadLastPercent) / 100.0);
        var speed = deltaBytes / elapsed.TotalSeconds;
        DownloadSpeedText = speed > 0 ? $"{FormatSize((long)speed)}/s" : "";
        _downloadStartedAt = DateTimeOffset.Now;
        _downloadLastPercent = percent;
    }

    private static int IndexOf(UpdateFrequency[] values, UpdateFrequency value)
    {
        for (var i = 0; i < values.Length; i++)
        {
            if (values[i] == value) return i;
        }

        return 0;
    }

    private static string FrequencyText(int index) => index switch
    {
        1 => "每次启动时检查更新",
        2 => "每天检查一次更新",
        3 => "每周检查一次更新",
        _ => "从不检查更新",
    };

    private static string FormatSize(long bytes)
    {
        if (bytes <= 0) return "—";
        string[] units = ["B", "KB", "MB", "GB"];
        double value = bytes;
        var unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }

        return unit == 0 ? $"{value:0} B" : $"{value:0.0} {units[unit]}";
    }

    private static string FormatDate(long millis) => TimeText.Local(millis);
}
