using System;
using System.Threading.Tasks;
using System.ComponentModel;
using Bond.Models;
using Bond.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Bond.ViewModels;

/// <summary>
/// 主窗口 ViewModel。
/// 侧边栏是 SukiUI 的静态 <c>SukiSideMenuItem</c>（分页内容由 XAML 的 PageContent 承载），
/// 因此这里不再维护自绘导航项：只负责账户区、跨页跳转事件与传输活动计数。
/// </summary>
public partial class MainWindowViewModel : ObservableObject
{
    private readonly IWindowCoordinator _shell;

    public MainWindowViewModel(
        IWindowCoordinator shell,
        ISessionService session,
        IInboxService inbox,
        FilesPageViewModel files,
        WorkspacePageViewModel workspaces,
        TransferPageViewModel transfers,
        DevicesPageViewModel devices,
        ChatPageViewModel chat,
        DocsPageViewModel docs,
        SettingsPageViewModel settings,
        PreviewViewModel preview)
    {
        _shell = shell;
        Session = session;
        Inbox = inbox;

        Files = files;
        Workspaces = workspaces;
        Transfers = transfers;
        Devices = devices;
        Chat = chat;
        Docs = docs;
        Settings = settings;
        Preview = preview;

        // 工作区卡片「进入」→ 切到文件页并载入该工作区（导航仍然走侧边栏，不自绘外壳）
        workspaces.OpenRequested += async (_, workspace) =>
        {
            await Files.SelectWorkspaceAsync(workspace);
            ShowFilesRequested?.Invoke(this, EventArgs.Empty);
        };

        // 传输页常驻单例：它刷新时同步计数，侧栏徽标不依赖是否打开过传输页
        transfers.PropertyChanged += OnTransfersPropertyChanged;
    }

    private void OnTransfersPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is not (nameof(TransferPageViewModel.ActiveCount)
            or nameof(TransferPageViewModel.IsBusy)))
            return;

        OnPropertyChanged(nameof(ActiveTransferCount));
        OnPropertyChanged(nameof(HasActiveTransfer));
        OnPropertyChanged(nameof(TransferBadgeText));
    }

    /// <summary>请求外壳把侧边栏选中项切到「文件」（由 MainWindow 订阅）。</summary>
    public event EventHandler? ShowFilesRequested;

    /// <summary>请求外壳把侧边栏选中项切到「传输」（悬浮球收件箱用）。</summary>
    public event EventHandler? ShowTransfersRequested;

    /// <summary>由悬浮球等外部入口调用：打开主窗口并跳到传输页。</summary>
    public void RequestTransfers() => ShowTransfersRequested?.Invoke(this, EventArgs.Empty);

    /// <summary>请求外壳把侧边栏选中项切到「会话」（悬浮球消息入口用）。</summary>
    public event EventHandler? ShowChatsRequested;

    /// <summary>由悬浮球等外部入口调用：打开主窗口并跳到会话页。</summary>
    public void RequestChats() => ShowChatsRequested?.Invoke(this, EventArgs.Empty);

    public ISessionService Session { get; }
    public IInboxService Inbox { get; }

    public FilesPageViewModel Files { get; }
    public WorkspacePageViewModel Workspaces { get; }
    public TransferPageViewModel Transfers { get; }
    public DevicesPageViewModel Devices { get; }
    public ChatPageViewModel Chat { get; }
    public DocsPageViewModel Docs { get; }
    public SettingsPageViewModel Settings { get; }
    public PreviewViewModel Preview { get; }

    // ── 侧栏「传输」活动徽标 ─────────────────────────────────
    public int ActiveTransferCount => Transfers.ActiveCount;

    public bool HasActiveTransfer => ActiveTransferCount > 0;

    public string TransferBadgeText => ActiveTransferCount > 9 ? "9+" : ActiveTransferCount.ToString();

    public string AccountName => Session.AccountName;

    public string AccountInitial =>
        string.IsNullOrEmpty(Session.AccountName) ? "?" : Session.AccountName[..1].ToUpperInvariant();

    public string AccountStatus => Session.PresenceText;

    /// <summary>服务端头像；为空时界面用首字母占位。</summary>
    public object? AvatarSource => Session.AvatarSource;

    public bool HasAvatar => Session.AvatarSource is not null;

    public int PendingCount => Inbox.PendingCount;
    public bool HasPending => PendingCount > 0;
    public string PendingText => PendingCount > 9 ? "9+" : PendingCount.ToString();

    /// <summary>会话数据回填后刷新账户区。</summary>
    public void RefreshSession()
    {
        OnPropertyChanged(nameof(AccountName));
        OnPropertyChanged(nameof(AccountInitial));
        OnPropertyChanged(nameof(AccountStatus));
        OnPropertyChanged(nameof(AvatarSource));
        OnPropertyChanged(nameof(HasAvatar));
        OnPropertyChanged(nameof(PendingCount));
        OnPropertyChanged(nameof(HasPending));
        OnPropertyChanged(nameof(PendingText));
    }

    [RelayCommand]
    private void MinimizeToBall() => _shell.MinimizeToBall();

    [RelayCommand]
    private void Quit() => _shell.Quit();
}
