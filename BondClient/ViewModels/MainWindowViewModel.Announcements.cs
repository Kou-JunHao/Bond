using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Threading;
using Bond.Models;
using Bond.Services;
using CommunityToolkit.Mvvm.Input;

namespace Bond.ViewModels;

/// <summary>
/// task-7：系统公告（主窗口右上角铃铛 + 非阻塞公告面板 + 标记已读）。
///
/// <para>拆分在独立的 partial 文件里，避免与同时进行的其它主窗口改动互相覆盖。</para>
/// <para>刷新策略：</para>
/// <list type="number">
///   <item>启动后加载一次未读数（<see cref="StartAnnouncementRefresh"/>，由 MainWindow 构造时调用一次）；</item>
///   <item>打开面板时拉列表（同时用列表里的 read 标记校正未读数）；</item>
///   <item>主窗口重新激活（Activated）或每 5 分钟刷新一次未读数（后台静默，失败保留旧值不弹错）。</item>
/// </list>
/// </summary>
public partial class MainWindowViewModel
{
    /// <summary>与后端 AnnouncementService.USER_LIST_LIMIT 对齐：列表不到该条数时，未读数可由列表精确重算。</summary>
    private const int AnnouncementListLimit = 50;

    private readonly Lazy<IAnnouncementService> _announcementService =
        new(ResolveAnnouncementService);

    private DispatcherTimer? _announcementTimer;
    private bool _announcementRefreshStarted;

    private int _unreadAnnouncementCount;
    private bool _isAnnouncementsOpen;
    private bool _isAnnouncementsLoading;
    private string? _announcementsError;

    private IAnnouncementService AnnouncementService => _announcementService.Value;

    /// <summary>面板里的公告列表（服务端按发布时间倒序）。</summary>
    public ObservableCollection<AnnouncementItem> Announcements { get; } = [];

    /// <summary>面板是否展开（非阻塞覆盖层：点遮罩 / Esc / 再点铃铛关闭）。</summary>
    public bool IsAnnouncementsOpen
    {
        get => _isAnnouncementsOpen;
        set => SetProperty(ref _isAnnouncementsOpen, value);
    }

    /// <summary>未读条数（铃铛徽标）。后台刷新失败时保留旧值，不显示假的 0。</summary>
    public int UnreadAnnouncementCount
    {
        get => _unreadAnnouncementCount;
        private set
        {
            var next = Math.Max(0, value);
            if (_unreadAnnouncementCount == next) return;
            _unreadAnnouncementCount = next;
            OnPropertyChanged();
            OnPropertyChanged(nameof(HasUnreadAnnouncements));
            OnPropertyChanged(nameof(UnreadAnnouncementBadgeText));
            OnPropertyChanged(nameof(AnnouncementsSummaryText));
        }
    }

    public bool HasUnreadAnnouncements => UnreadAnnouncementCount > 0;

    /// <summary>铃铛徽标文案（最多 9+，与侧栏未读徽标同口径）。</summary>
    public string UnreadAnnouncementBadgeText => UnreadAnnouncementCount > 9 ? "9+" : UnreadAnnouncementCount.ToString();

    /// <summary>首次加载中（面板显示进度条而不是「暂无公告」）。</summary>
    public bool IsAnnouncementsLoading
    {
        get => _isAnnouncementsLoading;
        private set
        {
            if (!SetProperty(ref _isAnnouncementsLoading, value)) return;
            OnPropertyChanged(nameof(HasAnnouncements));
            OnPropertyChanged(nameof(IsAnnouncementsEmpty));
            OnPropertyChanged(nameof(ShowAnnouncementsLoading));
            OnPropertyChanged(nameof(AnnouncementsSummaryText));
        }
    }

    /// <summary>面板空白时显示加载态；已有列表时的刷新只显示头部小进度（不盖住旧内容）。</summary>
    public bool ShowAnnouncementsLoading => IsAnnouncementsLoading && Announcements.Count == 0;

    /// <summary>最近一次加载/标记已读失败的可展示原因；成功时清空。</summary>
    public string? AnnouncementsError
    {
        get => _announcementsError;
        private set
        {
            if (!SetProperty(ref _announcementsError, value)) return;
            OnPropertyChanged(nameof(HasAnnouncementsError));
            OnPropertyChanged(nameof(IsAnnouncementsEmpty));
        }
    }

    public bool HasAnnouncementsError => !string.IsNullOrWhiteSpace(AnnouncementsError);

    public bool HasAnnouncements => Announcements.Count > 0;

    /// <summary>真空态（不是加载中、不是失败、确实没有公告）。</summary>
    public bool IsAnnouncementsEmpty => !IsAnnouncementsLoading && !HasAnnouncementsError && Announcements.Count == 0;

    /// <summary>面板头部摘要：未读数 + 总数。</summary>
    public string AnnouncementsSummaryText
    {
        get
        {
            if (IsAnnouncementsLoading) return "正在加载…";
            if (Announcements.Count == 0) return "暂无公告";
            return HasUnreadAnnouncements
                ? $"{UnreadAnnouncementCount} 条未读 · 共 {Announcements.Count} 条"
                : $"全部已读 · 共 {Announcements.Count} 条";
        }
    }

    // ── 刷新策略 ───────────────────────────────────────────────

    /// <summary>启动后调用一次（幂等）：加载未读数 + 每 5 分钟静默刷新未读数。</summary>
    public void StartAnnouncementRefresh()
    {
        if (_announcementRefreshStarted) return;
        _announcementRefreshStarted = true;

        _ = RefreshAnnouncementUnreadAsync();

        _announcementTimer = new DispatcherTimer { Interval = TimeSpan.FromMinutes(5) };
        _announcementTimer.Tick += (_, _) => _ = RefreshAnnouncementUnreadAsync();
        _announcementTimer.Start();
    }

    /// <summary>主窗口重新激活时调用：拉一次未读数（静默，失败保留旧值）。</summary>
    public void OnShellActivated() => _ = RefreshAnnouncementUnreadAsync();

    /// <summary>未读数后台刷新。失败不提示（公告不是核心路径，不打扰用户）。</summary>
    public async Task RefreshAnnouncementUnreadAsync()
    {
        try
        {
            var (unread, error) = await AnnouncementService.GetUnreadCountAsync();
            if (error is not null) return;

            // 服务端聚合计数为准；列表刷新时若未触到上限会再用 read 标记校正。
            UnreadAnnouncementCount = unread;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("[Bond] 公告未读数刷新失败：" + ex.Message);
        }
    }

    // ── 命令 ──────────────────────────────────────────────────

    /// <summary>铃铛点击：展开/收起面板；展开时拉一次列表。</summary>
    [RelayCommand]
    private void ToggleAnnouncements()
    {
        if (IsAnnouncementsOpen)
        {
            CloseAnnouncements();
            return;
        }

        IsAnnouncementsOpen = true;
        // 已有数据时静默刷新（不闪骨架），首次打开显示加载态。
        _ = RefreshAnnouncementsAsync(showLoading: Announcements.Count == 0);
    }

    /// <summary>关闭面板；顺手用服务端未读数校正一次（别的端可能已读）。</summary>
    [RelayCommand]
    private void CloseAnnouncements()
    {
        IsAnnouncementsOpen = false;
        if (_announcementRefreshStarted) _ = RefreshAnnouncementUnreadAsync();
    }

    /// <summary>刷新列表（面板刷新按钮 / 失败重试共用）。</summary>
    [RelayCommand]
    private Task RefreshAnnouncements() => RefreshAnnouncementsAsync(showLoading: true);

    /// <summary>
    /// 拉公告列表。成功时整体替换集合；<paramref name="showLoading"/> 为 true 时显示加载态
    /// （已有关闭/刷新场景用 false，避免列表闪烁）。
    /// </summary>
    public async Task RefreshAnnouncementsAsync(bool showLoading)
    {
        IsAnnouncementsLoading = showLoading;

        try
        {
            var (items, error) = await AnnouncementService.GetAsync();

            if (error is not null)
            {
                AnnouncementsError = error;
                return;
            }

            AnnouncementsError = null;

            Announcements.Clear();
            foreach (var item in items) Announcements.Add(item);

            // 列表没有触到服务端上限 → 未读数可以直接由列表重算（含已读状态），数量最一致。
            if (items.Count < AnnouncementListLimit)
                UnreadAnnouncementCount = items.Count(i => i.IsUnread);

            RaiseAnnouncementsState();
        }
        catch (Exception ex)
        {
            AnnouncementsError = "加载公告失败：" + ex.Message;
        }
        finally
        {
            IsAnnouncementsLoading = false;
        }
    }

    /// <summary>点击一条公告：调后端标已读 → 本地置为已读并递减未读数。</summary>
    [RelayCommand]
    private async Task MarkAnnouncementReadAsync(AnnouncementItem? item)
    {
        if (item is null || item.IsRead) return;

        try
        {
            var error = await AnnouncementService.MarkReadAsync(item.Id);
            if (error is not null)
            {
                AnnouncementsError = "标记已读失败：" + error;
                return;
            }

            AnnouncementsError = null;
            item.IsRead = true;
            if (UnreadAnnouncementCount > 0) UnreadAnnouncementCount--;
            RaiseAnnouncementsState();
        }
        catch (Exception ex)
        {
            AnnouncementsError = "标记已读失败：" + ex.Message;
        }
    }

    /// <summary>列表变化 / 已读状态变化后统一通知界面（空态、摘要、是否有数据）。</summary>
    private void RaiseAnnouncementsState()
    {
        OnPropertyChanged(nameof(HasAnnouncements));
        OnPropertyChanged(nameof(IsAnnouncementsEmpty));
        OnPropertyChanged(nameof(ShowAnnouncementsLoading));
        OnPropertyChanged(nameof(AnnouncementsSummaryText));
    }

    /// <summary>
    /// 组合根里优先取 DI 注册的公告服务；设计时/未初始化时退化为离线实现（界面显示可重试的错误，不崩溃）。
    /// 不在主构造函数上注入是为了把改动完全收敛在本 partial 文件里。
    /// </summary>
    private static IAnnouncementService ResolveAnnouncementService()
    {
        var services = App.Services;
        if (services?.GetService(typeof(IAnnouncementService)) is IAnnouncementService service)
            return service;

        return new UnavailableAnnouncementService();
    }

    /// <summary>DI 不可用时的兜底：所有调用返回可展示错误，绝不抛异常。</summary>
    private sealed class UnavailableAnnouncementService : IAnnouncementService
    {
        public Task<(IReadOnlyList<AnnouncementItem> Items, string? Error)> GetAsync(
            CancellationToken ct = default)
            => Task.FromResult<(IReadOnlyList<AnnouncementItem>, string?)>(
                (Array.Empty<AnnouncementItem>(), "公告服务不可用"));

        public Task<(int Unread, string? Error)> GetUnreadCountAsync(CancellationToken ct = default)
            => Task.FromResult<(int, string?)>((0, "公告服务不可用"));

        public Task<string?> MarkReadAsync(string id, CancellationToken ct = default)
            => Task.FromResult<string?>("公告服务不可用");
    }
}
