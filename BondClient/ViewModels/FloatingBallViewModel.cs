using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Input;
using Avalonia.Media;
using Bond.Core;
using Bond.Models;
using Bond.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Bond.ViewModels;

/// <summary>悬浮球快捷面板里的一个动作（图标 + 标题 + 可选计数徽标）。</summary>
public partial class QuickAction(string title, Material.Icons.MaterialIconKind icon, string hint, ICommand command)
    : ObservableObject
{
    public string Title { get; } = title;
    public Material.Icons.MaterialIconKind Icon { get; } = icon;
    public ICommand Command { get; } = command;

    /// <summary>副标题会随状态变化（例如收件箱的待接收数量）。</summary>
    [ObservableProperty]
    private string _hint = hint;

    /// <summary>徽标数字（0 表示不显示）。</summary>
    [ObservableProperty]
    private string _badge = string.Empty;

    [ObservableProperty]
    private bool _hasBadge;

    public void SetBadge(int count)
    {
        HasBadge = count > 0;
        Badge = count > 99 ? "99+" : count.ToString();
    }
}

/// <summary>悬浮球面板里的一台局域网设备（小行）。</summary>
public sealed class BallDeviceItem
{
    public required string Name { get; init; }

    public required bool IsOnline { get; init; }

    public required bool IsCurrentDevice { get; init; }

    /// <summary>
    /// 是否允许「发文件」。task-160 A：局域网发现的设备只展示，直连通道（B 阶段）就绪前不启用发送。
    /// </summary>
    public bool Sendable { get; init; } = true;

    public required IBrush DotBrush { get; init; }

    public required string Meta { get; init; }

    public required ICommand SendCommand { get; init; }

    /// <summary>本机：只能看，不能「发给自己」；局域网只读行也不可发。</summary>
    public bool CanSend => IsOnline && !IsCurrentDevice && Sendable;
}

/// <summary>悬浮球面板里的一条待接收任务。</summary>
public sealed class BallTaskItem
{
    public required string Name { get; init; }

    public required string Meta { get; init; }

    public required IncomingTaskInfo Task { get; init; }

    public required ICommand AcceptCommand { get; init; }

    public required ICommand RejectCommand { get; init; }
}

/// <summary>
/// 悬浮球 ViewModel。
/// <para>
/// 设计取舍：悬浮球只承担「随手可用的轻动作」，不复制主窗口的功能：
/// 上传到个人空间、把文件发给在线设备、看未读消息与待接收任务、实时看额度与传输进度，
/// 重型操作（整理文件、管理设备、改设置）仍然跳转主窗口。
/// </para>
/// </summary>
public partial class FloatingBallViewModel : ObservableObject
{
    private readonly IWindowCoordinator _shell;
    private readonly ISessionService _session;
    private readonly IInboxService _inbox;
    private readonly ITransferService _transfers;
    private readonly IDeviceService _devices;
    private readonly INotificationCenter _notify;
    private readonly IFileService _files;
    private readonly IChatService _chat;
    private readonly Bond.Services.Lan.LanDiscoveryService _lan;

    public FloatingBallViewModel(
        IWindowCoordinator shell,
        ISessionService session,
        IInboxService inbox,
        ITransferService transfers,
        IDeviceService devices,
        INotificationCenter notify,
        IFileService files,
        IChatService chat,
        Bond.Services.Lan.LanDiscoveryService lan)
    {
        _shell = shell;
        _session = session;
        _inbox = inbox;
        _transfers = transfers;
        _devices = devices;
        _notify = notify;
        _files = files;
        _chat = chat;
        _lan = lan;

        // 四个高频动作，一行排开（面板里不再重复「打开主界面」——头部与底部各有入口）
        QuickActions =
        [
            new QuickAction("上传", Material.Icons.MaterialIconKind.Upload, "到个人空间", UploadFilesCommand),
            new QuickAction("发设备", Material.Icons.MaterialIconKind.SendOutline, "在线设备", SendFilesCommand),
            new QuickAction("消息", Material.Icons.MaterialIconKind.ChatBubbleOutline, "未读会话", OpenMessagesCommand),
            new QuickAction("收件箱", Material.Icons.MaterialIconKind.Download, "待接收任务", OpenInboxCommand)
        ];

        AcceptTaskCommand = new AsyncRelayCommand<BallTaskItem>(AcceptTaskAsync);
        RejectTaskCommand = new AsyncRelayCommand<BallTaskItem>(RejectTaskAsync);
        SendToDeviceCommand = new RelayCommand<BallDeviceItem>(SendToDevice);

        _inbox.Changed += (_, _) => RefreshBadge();
        _transfers.Changed += (_, _) => RefreshTransfer();
        _devices.Changed += (_, _) => RefreshDeviceRows();

        // task-160 A：局域网发现结果并进「设备」小节（离线/未登录时服务端行为空，这里仍能看到同网段机器）。
        _lan.Changed += (_, _) => RefreshDeviceRows();

        RefreshBadge();
        RefreshTransfer();
        RefreshDeviceRows();

        // 预热一次额度 / 待接收 / 未读：面板首帧就能显示真实数值，
        // 不必等展开后的异步请求回来（否则会先空着再突然长出来一块）。
        _ = LoadQuotaAsync();
        _ = LoadTasksAsync();
        _ = LoadUnreadAsync();
    }

    /// <summary>只拉未读数（启动预热用，失败也不影响徽标）。</summary>
    private async Task LoadUnreadAsync()
    {
        try
        {
            await _chat.RefreshConversationsAsync().ConfigureAwait(true);
        }
        catch (Exception)
        {
            // 聊天服务不可用时未读徽标保持 0。
        }

        RefreshUnread();
    }

    /// <summary>窗口在面板展开时回调，拉一次设备 / 额度 / 待接收任务 / 未读数。</summary>
    public async Task RefreshPanelAsync()
    {
        // task-160 A：面板展开立即广播一次，局域网设备不必等 2s 心跳周期才出现。
        _lan.AnnounceNow();

        try
        {
            await _devices.RefreshAsync().ConfigureAwait(true);
        }
        catch (Exception)
        {
            // 离线时静默失败：面板仍要能用。
        }

        RefreshDeviceRows();
        await LoadQuotaAsync().ConfigureAwait(true);
        await LoadTasksAsync().ConfigureAwait(true);

        try
        {
            await _chat.RefreshConversationsAsync().ConfigureAwait(true);
        }
        catch (Exception)
        {
            // 聊天接口不可用时，未读徽标就不显示，不阻止面板其它功能。
        }

        RefreshUnread();
    }

    public ObservableCollection<QuickAction> QuickActions { get; }

    public ObservableCollection<BallDeviceItem> DeviceRows { get; } = [];

    public ObservableCollection<BallTaskItem> Tasks { get; } = [];

    public IRelayCommand<BallTaskItem> AcceptTaskCommand { get; }

    public IRelayCommand<BallTaskItem> RejectTaskCommand { get; }

    public ICommand SendToDeviceCommand { get; }

    /// <summary>「发设备」需要窗口弹出系统文件选择器。</summary>
    public event EventHandler<BallDeviceItem?>? SendFilesRequested;

    /// <summary>「上传」需要窗口弹出系统文件选择器（上传到个人空间）。</summary>
    public event EventHandler? UploadFilesRequested;

    // ── 账号身份（悬浮球面板左上角）────────────────────────────────

    public string AccountName => string.IsNullOrWhiteSpace(_session.AccountName) ? "未登录" : _session.AccountName;

    public string AccountInitial => AccountName.Length > 0
        ? AccountName[..1].ToUpperInvariant()
        : "?";

    public object? AvatarSource => _session.AvatarSource;

    public bool HasAvatar => _session.AvatarSource is not null;

    [ObservableProperty]
    private string _deviceName = Environment.MachineName;

    [ObservableProperty]
    private string _presenceText = "离线";

    [ObservableProperty]
    private int _pendingCount;

    [ObservableProperty]
    private bool _hasPending;

    [ObservableProperty]
    private string _pendingText = string.Empty;

    // ── 未读消息（会话页的未读总和）────────────────────────────────

    [ObservableProperty]
    private int _unreadTotal;

    [ObservableProperty]
    private bool _hasUnread;

    [ObservableProperty]
    private string _unreadText = string.Empty;

    // ── 传输进度 ────────────────────────────────────────────────

    [ObservableProperty]
    private bool _hasActiveTransfer;

    [ObservableProperty]
    private double _transferProgress;

    [ObservableProperty]
    private string _transferText = string.Empty;

    [ObservableProperty]
    private string _transferCountText = string.Empty;

    [ObservableProperty]
    private bool _isScanning;

    // ── 额度（GET /api/entitlements/me，失败隐藏整行） ────────────

    [ObservableProperty]
    private bool _hasQuota;

    [ObservableProperty]
    private double _quotaPercent;

    [ObservableProperty]
    private string _quotaText = string.Empty;

    [ObservableProperty]
    private string _quotaPercentText = string.Empty;

    // ── 拖拽上传进度 ──────────────────────────────────────────────

    [ObservableProperty]
    private bool _isUploading;

    [ObservableProperty]
    private string _uploadName = string.Empty;

    [ObservableProperty]
    private double _uploadProgress;

    [ObservableProperty]
    private string _uploadStatusText = string.Empty;

    /// <summary>文件拖到球上时窗口切到"松开上传"提示态。</summary>
    [ObservableProperty]
    private bool _isDragOver;

    // ── 待接收任务 ────────────────────────────────────────────────

    [ObservableProperty]
    private bool _hasTasks;

    [ObservableProperty]
    private string _tasksHeader = "待接收";

    /// <summary>在线状态色：球体的状态环 / 面板里的状态点用它。</summary>
    public IBrush PresenceBrush => _session.Presence switch
    {
        PresenceState.Online => Brush.Parse("#22A06B"),
        PresenceState.Relay => Brush.Parse("#4F7CFF"),
        PresenceState.LanOnly => Brush.Parse("#E8A33D"),
        _ => Brush.Parse("#9CA3AF")
    };

    public string DeviceCode => _session.DeviceCode;

    public string VersionText => "Bond 2.0 · 重构预览";

    public void RefreshBadge()
    {
        PendingCount = _inbox.PendingCount;
        HasPending = PendingCount > 0;
        PendingText = PendingCount > 9 ? "9+" : PendingCount.ToString();
        DeviceName = _session.DeviceName;
        PresenceText = _session.PresenceText;

        // 收件箱格子的副标题实时显示待接收数量（0 时回到默认文案）
        if (QuickActions.Count > 3)
        {
            QuickActions[3].Hint = PendingCount > 0 ? $"待接收 {PendingText} 项" : "待接收任务";
            QuickActions[3].SetBadge(PendingCount);
        }

        OnPropertyChanged(nameof(PresenceBrush));
        OnPropertyChanged(nameof(DeviceCode));
        OnPropertyChanged(nameof(AccountName));
        OnPropertyChanged(nameof(AccountInitial));
        OnPropertyChanged(nameof(AvatarSource));
        OnPropertyChanged(nameof(HasAvatar));
    }

    /// <summary>未读消息总数（会话列表由 IChatService 维护，推送时会整体替换）。</summary>
    public void RefreshUnread()
    {
        var total = 0;
        if (_chat.IsChatAvailable)
        {
            foreach (var thread in _chat.Threads)
            {
                total += Math.Max(0, thread.Unread);
            }
        }

        UnreadTotal = total;
        HasUnread = total > 0;
        UnreadText = total > 99 ? "99+" : total.ToString();

        if (QuickActions.Count > 2)
        {
            QuickActions[2].Hint = HasUnread ? $"未读 {UnreadText}" : "未读会话";
            QuickActions[2].SetBadge(total);
        }
    }

    private void RefreshTransfer()
    {
        HasActiveTransfer = _transfers.HasActive;
        TransferProgress = _transfers.OverallProgress;
        TransferText = HasActiveTransfer ? $"{TransferProgress * 100:0}%" : string.Empty;

        var active = _transfers.Items.Count(i => i.IsActive);
        TransferCountText = active > 0 ? $"{active} 个传输进行中" : string.Empty;
    }

    private void RefreshDeviceRows()
    {
        IsScanning = _devices.IsScanning;

        // 只列「别的设备」：本机状态已在头部（设备名 + 连接状态），列在设备表里既占位又容易误点「发给自己」。
        // 同一台机器可能因重复刷新出现多行（服务端返回重复行 / 并发刷新），按设备行 id 去重；在线优先。
        var rows = _devices.Peers
            .Where(p => !string.IsNullOrEmpty(p.Id))
            .GroupBy(p => p.Id, StringComparer.Ordinal)
            .Select(g => g.First())
            .Where(p => !p.IsCurrentDevice)
            .OrderByDescending(p => p.IsOnline)
            .ThenBy(p => p.Name, StringComparer.CurrentCultureIgnoreCase)
            .Select(peer => new BallDeviceItem
            {
                Name = peer.Name,
                IsOnline = peer.IsOnline,
                IsCurrentDevice = false,
                DotBrush = Brush.Parse(peer.IsOnline ? "#22A06B" : "#9CA3AF"),
                Meta = peer.IsOnline ? "在线 · 点击发送文件" : "离线",
                SendCommand = SendToDeviceCommand
            })
            .ToList();

        // task-160 A：把「只在局域网里看到、服务端还没有行」的设备补进来（按名字去重）。
        // 同一账号的两台机器即使后端设备行还没刷新，也能在悬浮球里立刻互见。
        var names = new HashSet<string>(rows.Select(r => r.Name), StringComparer.CurrentCultureIgnoreCase);
        foreach (var device in _lan.Devices)
        {
            if (string.IsNullOrWhiteSpace(device.DeviceName) || !names.Add(device.DeviceName)) continue;

            rows.Add(new BallDeviceItem
            {
                Name = device.DeviceName,
                IsOnline = true,
                IsCurrentDevice = false,
                Sendable = false,
                DotBrush = Brush.Parse("#22A06B"),
                Meta = $"局域网 · {device.Ip}",
                SendCommand = SendToDeviceCommand
            });
        }

        // 逐项同步而不是 Clear：Avalonia 12 收到 Reset 会残留旧容器，界面上看起来「设备变多了」。
        CollectionSync.Apply(DeviceRows, rows, SameDeviceRow);

        OnPropertyChanged(nameof(HasDevices));
        OnPropertyChanged(nameof(HasOnlineDevices));
        OnPropertyChanged(nameof(DeviceHintText));
    }

    private static bool SameDeviceRow(BallDeviceItem a, BallDeviceItem b) =>
        string.Equals(a.Name, b.Name, StringComparison.Ordinal)
        && a.IsOnline == b.IsOnline
        && a.Sendable == b.Sendable
        && string.Equals(a.Meta, b.Meta, StringComparison.Ordinal);

    public bool HasDevices => DeviceRows.Count > 0;

    /// <summary>除本机外至少一台在线设备：只有它们才允许点发送。</summary>
    public bool HasOnlineDevices => DeviceRows.Any(r => r.CanSend);

    /// <summary>
    /// 空提示：没设备 / 有设备但不能发 / 都不在线，文案分开。
    /// task-160 A：局域网发现开启后，没设备时引导用户检查「同一局域网 + 同一账号」。
    /// </summary>
    public string DeviceHintText
    {
        get
        {
            if (DeviceRows.Count == 0)
            {
                return _lan.IsRunning
                    ? "附近没有设备 · 请确认两台机器在同一局域网并登录同一账号"
                    : "还没有设备 · 登录同一账号的机器会出现在这里";
            }

            if (DeviceRows.Any(r => r.IsOnline && !r.Sendable))
                return "已发现局域网设备 · 直连发送将在后续版本开放";

            return "没有其它在线设备 · 在两台机器上同时登录同一账号";
        }
    }

    /// <summary>由悬浮球窗口在形变开始/结束时回调，便于外部感知面板开合。</summary>
    public void SetPanelOpen(bool open) => IsPanelOpen = open;

    [ObservableProperty]
    private bool _isPanelOpen;

    // ── 额度 / 任务加载 ──────────────────────────────────────────

    public async Task LoadQuotaAsync()
    {
        try
        {
            var info = await _files.GetEntitlementsAsync().ConfigureAwait(true);
            if (info is null || info.StorageLimitBytes <= 0)
            {
                HasQuota = false;
                return;
            }

            HasQuota = true;
            QuotaPercent = Math.Clamp(info.StorageBytes / (double)info.StorageLimitBytes, 0d, 1d);
            QuotaText = $"{FormatSize(info.StorageBytes)} / {FormatSize(info.StorageLimitBytes)}";
            // task-4：与文件页/工作区卡统一——已用不足 1% 显示「<1%」，不再被舍入成「0%」。
            QuotaPercentText = Bond.Models.QuotaFormat.Percent(
                info.StorageBytes, info.StorageLimitBytes, oneDecimal: true);
        }
        catch (Exception)
        {
            HasQuota = false;
        }
    }

    public async Task LoadTasksAsync()
    {
        try
        {
            var items = await _transfers.GetIncomingAsync().ConfigureAwait(true);

            var rows = items.Select(task => new BallTaskItem
            {
                Name = task.FileName,
                Meta = $"{FormatSize(task.Size)} · {task.Sender}",
                Task = task,
                AcceptCommand = AcceptTaskCommand,
                RejectCommand = RejectTaskCommand
            }).ToList();

            // 同上：避免 Reset 留下的旧容器让「待接收」里出现重复行。
            CollectionSync.Apply(Tasks, rows, (a, b) => a.Task.Id == b.Task.Id);
        }
        catch (Exception)
        {
            CollectionSync.Apply(Tasks, []);
        }

        HasTasks = Tasks.Count > 0;
        TasksHeader = HasTasks ? $"待接收 {Tasks.Count} 项" : "待接收";
    }

    private async Task AcceptTaskAsync(BallTaskItem? item)
    {
        if (item is null) return;

        if (await _transfers.AcceptIncomingAsync(item.Task.Id).ConfigureAwait(true))
        {
            _notify.Notify($"已接收 {item.Name}", NoticeKind.Success);
        }
        else
        {
            _notify.Notify($"接收 {item.Name} 失败", NoticeKind.Warning);
        }

        await LoadTasksAsync().ConfigureAwait(true);
        await LoadQuotaAsync().ConfigureAwait(true);
    }

    private async Task RejectTaskAsync(BallTaskItem? item)
    {
        if (item is null) return;

        if (await _transfers.RejectIncomingAsync(item.Task.Id).ConfigureAwait(true))
        {
            _notify.Notify($"已拒绝 {item.Name}", NoticeKind.Info);
        }

        await LoadTasksAsync().ConfigureAwait(true);
    }

    /// <summary>拖入或点选的文件直接上传到个人空间根目录（真实进度）。</summary>
    public async Task UploadPathsAsync(IReadOnlyList<string> paths)
    {
        if (paths.Count == 0) return;

        IsUploading = true;
        var ok = 0;
        var fail = 0;
        var index = 0;

        foreach (var path in paths)
        {
            index++;
            UploadName = System.IO.Path.GetFileName(path);
            UploadStatusText = paths.Count > 1 ? $"上传中 {index}/{paths.Count}" : "上传中…";
            UploadProgress = 0;

            var progress = new Progress<double>(value => UploadProgress = Math.Clamp(value, 0d, 1d));

            try
            {
                if (await _files.UploadQuickAsync(path, progress).ConfigureAwait(true))
                {
                    ok++;
                }
                else
                {
                    fail++;
                }
            }
            catch (Exception)
            {
                fail++;
            }
        }

        IsUploading = false;
        UploadProgress = 0;

        if (fail == 0)
        {
            _notify.Notify($"已上传 {ok} 个文件到个人空间", NoticeKind.Success);
        }
        else
        {
            _notify.Notify($"上传完成：成功 {ok}，失败 {fail}", NoticeKind.Warning);
        }

        await LoadQuotaAsync().ConfigureAwait(true);
    }

    /// <summary>把选中的本地文件发给一台设备（悬浮球窗口弹选择器后调用）。</summary>
    public async Task SendPathsToPeerAsync(BallDeviceItem? device, IReadOnlyList<string> paths)
    {
        if (paths.Count == 0) return;

        var peer = device is not null
            ? _devices.Peers.FirstOrDefault(p => p.Name == device.Name)
            : _devices.Peers.FirstOrDefault(p => p.IsOnline && !p.IsCurrentDevice);

        if (peer is null || !peer.IsOnline || peer.IsCurrentDevice)
        {
            _notify.Notify("没有其它在线设备 · 在两台机器上同时登录同一账号", NoticeKind.Warning);
            return;
        }

        await _transfers.SendToAsync(peer, paths).ConfigureAwait(true);
        _notify.Notify($"已发送 {paths.Count} 个文件到 {peer.Name}", NoticeKind.Success);
        RefreshTransfer();
    }

    /// <summary>旧版拖放到球 = 投递给设备；现在拖拽按需求改为上传到个人空间，此方法保留给兼容入口。</summary>
    public async Task DeliverAsync(IReadOnlyList<string> paths) => await UploadPathsAsync(paths);

    public static string FormatSize(long bytes) => bytes switch
    {
        < 0 => "0 B",
        < 1024 => $"{bytes} B",
        < 1024 * 1024 => $"{bytes / 1024d:0.#} KB",
        < 1024L * 1024 * 1024 => $"{bytes / (1024d * 1024):0.#} MB",
        _ => $"{bytes / (1024d * 1024 * 1024):0.##} GB"
    };

    // ── 快捷动作 ──────────────────────────────────────────────

    [RelayCommand]
    private void OpenMain()
    {
        _shell.ShowMain();
        _shell.CollapseBall();
    }

    [RelayCommand]
    private async Task OpenInbox()
    {
        await LoadTasksAsync().ConfigureAwait(true);

        // 收件箱 = 打开主界面并跳到传输页（悬浮球不重复实现整页 UI）
        if (App.Services?.GetService(typeof(MainWindowViewModel)) is MainWindowViewModel main)
        {
            main.RequestTransfers();
        }

        _shell.ShowMain();
        _shell.CollapseBall();
    }

    /// <summary>消息 = 打开主界面并跳到会话页（未读数在会话页里清）。</summary>
    [RelayCommand]
    private void OpenMessages()
    {
        if (App.Services?.GetService(typeof(MainWindowViewModel)) is MainWindowViewModel main)
        {
            main.RequestChats();
        }

        _shell.ShowMain();
        _shell.CollapseBall();
    }

    /// <summary>上传 = 弹文件选择器，选完直接进个人空间根目录。</summary>
    [RelayCommand]
    private void UploadFiles() => UploadFilesRequested?.Invoke(this, EventArgs.Empty);

    [RelayCommand]
    private void SendFiles()
    {
        if (!_devices.Peers.Any(p => p.IsOnline && !p.IsCurrentDevice))
        {
            _notify.Notify("没有其它在线设备 · 在两台机器上同时登录同一账号", NoticeKind.Warning);
            return;
        }

        SendFilesRequested?.Invoke(this, null);
    }

    private void SendToDevice(BallDeviceItem? device)
    {
        if (device is null) return;

        if (device.IsCurrentDevice)
        {
            _notify.Notify("这台就是本机，选别的设备发送", NoticeKind.Info);
            return;
        }

        if (!device.IsOnline)
        {
            _notify.Notify($"{device.Name} 当前离线，暂时无法发送", NoticeKind.Warning);
            return;
        }

        SendFilesRequested?.Invoke(this, device);
    }

    [RelayCommand]
    private async Task RefreshDevicesAsync()
    {
        await RefreshPanelAsync().ConfigureAwait(true);

        var total = _devices.Peers.Count;
        var online = _devices.Peers.Count(p => p.IsOnline && !p.IsCurrentDevice);
        var lan = _lan.Devices.Count;

        _notify.Notify(total == 0 && lan == 0
            ? "没有设备 · 登录同一账号的机器会出现在这里"
            : $"已发现 {total + lan} 台设备（{online} 台可发送）");
    }

    [RelayCommand]
    private void Collapse() => _shell.CollapseBall();

    [RelayCommand]
    private void Quit() => _shell.Quit();
}
