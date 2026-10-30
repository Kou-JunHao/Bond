using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Platform.Storage;
using Bond.Core;
using Bond.Models;
using Bond.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Bond.ViewModels;

// ═══════════════════════════════════════════════════════════════
//  页面 ViewModel
//  ── 只依赖服务接口；不含任何 UI 引用。可单独构造与测试。
// ═══════════════════════════════════════════════════════════════

/// <summary>面包屑的一段：文件页顶部每段都可点击回跳。</summary>
public sealed class Breadcrumb(string label, string path, bool isLast)
{
    public string Label { get; } = label;
    public string Path { get; } = path;
    public bool IsLast { get; } = isLast;
}

/// <summary>文件页排序键。文件夹永远排在文件前面，这里只决定组内顺序。</summary>
public enum FileSort
{
    NameAsc,
    NameDesc,
    SizeDesc,
    SizeAsc,
    DateDesc,
    DateAsc,
    Type
}

/// <summary>排序下拉项（Label 给界面，Sort 给 ViewModel）。</summary>
public sealed class SortOption(string label, FileSort sort)
{
    public string Label { get; } = label;
    public FileSort Sort { get; } = sort;
}

/// <summary>
/// 文件页排序比较器：文件夹永远排在文件前面，组内按选定的键排序，
/// 主键相等时用名称兜底保证顺序稳定（重启前后一致）。
/// </summary>
internal sealed class NodeComparer(FileSort sort) : IComparer<NodeItem>
{
    public static NodeComparer For(FileSort sort) => new(sort);

    public int Compare(NodeItem? x, NodeItem? y)
    {
        if (ReferenceEquals(x, y)) return 0;
        if (x is null) return 1;
        if (y is null) return -1;

        var xFolder = x.Kind == NodeKind.Folder;
        var yFolder = y.Kind == NodeKind.Folder;
        if (xFolder != yFolder) return xFolder ? -1 : 1;

        var c = sort switch
        {
            FileSort.NameAsc => Name(x, y),
            FileSort.NameDesc => -Name(x, y),
            FileSort.SizeDesc => y.Size.CompareTo(x.Size),
            FileSort.SizeAsc => x.Size.CompareTo(y.Size),
            FileSort.DateDesc => y.ModifiedAt.CompareTo(x.ModifiedAt),
            FileSort.DateAsc => x.ModifiedAt.CompareTo(y.ModifiedAt),
            FileSort.Type => TypeThenName(x, y),
            _ => Name(x, y)
        };

        return c != 0 ? c : Name(x, y);
    }

    private static int Name(NodeItem x, NodeItem y) =>
        string.Compare(x.Name, y.Name, StringComparison.CurrentCulture);

    private static int TypeThenName(NodeItem x, NodeItem y)
    {
        var c = string.Compare(x.Extension, y.Extension, StringComparison.OrdinalIgnoreCase);
        return c != 0 ? c : Name(x, y);
    }
}

public partial class FilesPageViewModel : ObservableObject
{
    private readonly IFileService _files;
    private readonly IDeviceService _devices;
    private readonly ITransferService _transfers;
    private readonly INotificationCenter _notify;
    private readonly PreviewViewModel _preview;
    private readonly ITrashService _trash;
    private readonly IUploadService _uploads;
    private bool _syncingSpace;

    public FilesPageViewModel(
        IFileService files,
        IDeviceService devices,
        ITransferService transfers,
        INotificationCenter notify,
        PreviewViewModel preview,
        ITrashService trash,
        IUploadService uploads)
    {
        _files = files;
        _devices = devices;
        _transfers = transfers;
        _notify = notify;
        _preview = preview;
        _trash = trash;
        _uploads = uploads;

        SortOptions =
        [
            new SortOption("名称 A → Z", FileSort.NameAsc),
            new SortOption("名称 Z → A", FileSort.NameDesc),
            new SortOption("大小 从大到小", FileSort.SizeDesc),
            new SortOption("大小 从小到大", FileSort.SizeAsc),
            new SortOption("时间 最新优先", FileSort.DateDesc),
            new SortOption("时间 最早优先", FileSort.DateAsc),
            new SortOption("类型（扩展名）", FileSort.Type)
        ];

        var prefs = LocalPrefs.Current;
        _isTileView = string.Equals(prefs.FilesViewMode, "tile", StringComparison.OrdinalIgnoreCase);
        _selectedSort = SortOptions.FirstOrDefault(o =>
            string.Equals(o.Sort.ToString(), prefs.FileSortBy, StringComparison.OrdinalIgnoreCase))
            ?? SortOptions[0];

        _files.Changed += (_, _) => SyncFromService();
        _uploads.UploadsChanged += (_, _) =>
        {
            SyncUploadOverlays();
            SyncUploadRows();
        };

        // 只通过 Changed 刷新：HttpBondStore 在后台线程增删 Nodes，
        // 直接订阅 Nodes.CollectionChanged 会导致 View 在非 UI 线程被修改（会崩）。
        SyncFromService();
        SyncUploadRows();

        // task-164：分享管理（筛选/搜索/编辑壳）的初始化。
        InitializeSharesManagement();
    }

    /// <summary>当前展示的（已过滤 + 已排序）文件。</summary>
    /// <remarks>
    /// task-93：与 <see cref="Breadcrumbs"/> 同因——这里若用 <c>ObservableCollection</c>，
    /// 启动期 <c>Changed</c> 回调用 <c>Clear()+Add()</c> 重建列表时会派发 <c>CollectionChanged</c>，
    /// 而文件页的两个 ItemsControl 在首次挂载时正建立弱事件订阅，Avalonia 12 的
    /// <c>WeakEvent.Subscription.OnEvent</c> 会在这条路径上解引用空项（原生 0xC0000005 / NRE 崩溃）。
    /// 列表本身每次都整体重算，改成只读列表整体替换即可，绑定侧只走 <c>PropertyChanged</c> 重绑 ItemsSource。
    /// </remarks>
    public IReadOnlyList<NodeItem> View
    {
        get => _view;
        private set
        {
            _view = value;
            OnPropertyChanged(nameof(View));
        }
    }

    private IReadOnlyList<NodeItem> _view = [];

    public ObservableCollection<NodeItem> Recent => _files.Recent;

    /// <summary>文件页顶部的空间切换器数据源（个人空间 + 团队工作区）。</summary>
    public ObservableCollection<FileSpace> Spaces => _files.Spaces;

    private IReadOnlyList<Breadcrumb> _breadcrumbs = [];

    /// <summary>
    /// 面包屑：工作区名 / 一级目录 / 二级目录…
    /// <para>
    /// task-93：这里故意**不用** <c>ObservableCollection</c>，而是每次整体替换只读列表。
    /// 02:46 出现过一次启动即崩：<c>NullReferenceException at Avalonia.Utilities.WeakHashList.GetAlive</c>
    /// ← <c>WeakEvent.Subscription.OnEvent</c> ← <c>ObservableCollection.OnCollectionChanged</c>
    /// ← <c>RebuildBreadcrumbs</c> ← <c>SyncFromService</c>。
    /// 原因是启动期 FilesPage 第一次挂载视觉树、面包屑 ItemsControl 正在建立自己的弱事件订阅时，
    /// bootstrap 的 <c>Changed</c> 回调恰好重建面包屑，对同一个集合派发 <c>CollectionChanged</c>；
    /// Avalonia 12 的 WeakEvents 在这个「订阅列表正在被遍历/对象半初始化」的窗口里会在
    /// <c>WeakHashList.GetAlive</c> 解引用空项。换成不可观察的只读列表后，ItemsControl 只订阅
    /// <c>PropertyChanged</c>（重新绑定 ItemsSource），不再订阅集合变更，该弱事件路径完全不参与，
    /// 崩溃窗口消失。数据量是几个面包屑，整体替换的代价可以忽略。
    /// </para>
    /// </summary>
    public IReadOnlyList<Breadcrumb> Breadcrumbs
    {
        get => _breadcrumbs;
        private set
        {
            _breadcrumbs = value;
            OnPropertyChanged(nameof(Breadcrumbs));
        }
    }

    public StorageQuota Quota => _files.Quota;

    /// <summary>排序下拉数据源。</summary>
    public IReadOnlyList<SortOption> SortOptions { get; }

    [ObservableProperty]
    private string _path = "/";

    [ObservableProperty]
    private string _keyword = string.Empty;

    /// <summary>平铺视图（卡片网格）；否则为列表视图。</summary>
    [ObservableProperty]
    private bool _isTileView;

    /// <summary>视图切换按钮的提示：当前是列表就提示切平铺，反之亦然。</summary>
    public string ViewToggleTip => IsTileView ? "切换到列表视图" : "切换到平铺视图";

    /// <summary>图标显示"将会切到的视图"。</summary>
    public Material.Icons.MaterialIconKind ViewToggleIcon => IsTileView
        ? Material.Icons.MaterialIconKind.ViewList
        : Material.Icons.MaterialIconKind.ViewGrid;

    [ObservableProperty]
    private SortOption _selectedSort;

    [ObservableProperty]
    private NodeItem? _selected;

    [ObservableProperty]
    private FileSpace? _selectedSpace;

    [ObservableProperty]
    private bool _isLoading;

    [ObservableProperty]
    private bool _hasError;

    [ObservableProperty]
    private string _errorText = string.Empty;

    [ObservableProperty]
    private NodeItem? _pendingDelete;

    public string CountText => IsSearchActive ? $"{View.Count} 个结果" : $"{View.Count} 个项目";

    // ── 多选（Ctrl 点选 / Ctrl+A 全选；Selected 是最近点击的「锚点」）──
    private readonly HashSet<NodeItem> _selection = new();
    private readonly List<NodeItem> _pendingDeleteMany = new();
    private bool _syncingSelection;

    public int SelectionCount => _selection.Count;

    public bool HasSelection => _selection.Count > 0;

    public bool HasSingleSelection => _selection.Count == 1;

    public bool CanRenameSelection => _selection.Count == 1;

    public IReadOnlyList<NodeItem> SelectedNodes => _selection.ToList();

    /// <summary>操作条上的选中摘要：1 项显示「文件名 · 大小」，多项显示数量与总大小。</summary>
    public string SelectionBarText
    {
        get
        {
            if (_selection.Count == 0) return string.Empty;
            if (_selection.Count == 1)
            {
                var node = Selected ?? _selection.First();
                return node.IsFolder ? node.Name : $"{node.Name} · {NodeItem.FormatSize(node.Size)}";
            }

            var total = _selection.Sum(n => n.Size);
            return $"已选 {_selection.Count} 项 · 共 {NodeItem.FormatSize(total)}";
        }
    }

    /// <summary>空态：加载完成、无错误且没有任何条目（错误态与空态必须互斥）。</summary>
    public bool IsEmpty => !IsLoading && !IsSearching && !HasError && !HasSearchError && View.Count == 0;

    partial void OnHasErrorChanged(bool value) => OnPropertyChanged(nameof(IsEmpty));

    /// <summary>没有任何可用空间（未连上服务器）时的空状态；失败时不再伪装成「没有空间」。</summary>
    public bool HasNoWorkspace => _files.Spaces.Count == 0
        && !IsLoading
        && !_files.IsSpacesLoading
        && _files.LastSpacesError is null;

    /// <summary>空态分两种文案：搜索无结果 / 当前目录为空。</summary>
    public bool IsSearchEmpty => IsEmpty && !HasNoWorkspace && !string.IsNullOrWhiteSpace(Keyword);

    public bool IsFolderEmpty => IsEmpty && !HasNoWorkspace && string.IsNullOrWhiteSpace(Keyword);

    /// <summary>首屏骨架：正在加载且还没有任何数据。</summary>
    public bool ShowSkeleton => IsLoading && View.Count == 0;

    /// <summary>当前正在行内重命名。</summary>
    public bool IsRenaming => View.Any(n => n.IsEditing);

    /// <summary>正在编辑的那一行（Esc 取消用）。</summary>
    public NodeItem? EditingNode => View.FirstOrDefault(n => n.IsEditing);

    public bool IsConfirmingDelete => PendingDelete is not null || _pendingDeleteMany.Count > 0;

    public string DeleteConfirmText => PendingDelete is not null
        ? $"确认删除「{PendingDelete.Name}」？"
        : _pendingDeleteMany.Count > 0
            ? $"确认删除选中的 {_pendingDeleteMany.Count} 项？"
            : "";

    public string SelectionText => _selection.Count switch
    {
        0 => $"共 {View.Count} 个项目",
        1 => "已选 1 项",
        _ => $"已选 {_selection.Count} 项"
    };

    // ── 当前空间（个人空间 / 团队工作区）的展示 ─────────────
    public bool IsPersonalSpace => _files.IsPersonalSpace;

    public string CurrentSpaceName => _files.CurrentSpace?.Name ?? "个人空间";

    /// <summary>当前空间图标（个人 / 团队）。</summary>
    public Material.Icons.MaterialIconKind SpaceIcon => _files.CurrentSpace?.Icon
        ?? Material.Icons.MaterialIconKind.AccountOutline;

    /// <summary>页面头副标题：标明当前空间与可见范围。</summary>
    public string SpaceSubtitle => IsPersonalSpace
        ? "个人空间 · 文件仅自己可见，可上传、下载与整理"
        : $"{CurrentSpaceName} · 团队共享空间";

    public string FolderEmptyTitle => IsPersonalSpace ? "个人空间还没有文件" : "该工作区还没有文件";

    public string FolderEmptyHint => IsPersonalSpace
        ? "点击「上传」或把文件拖进来，文件仅自己可见"
        : "上传或拖入文件，与工作区成员共享";

    /// <summary>真实配额（个人空间走 /api/files/quota，团队用工作区配额）。</summary>
    /// <remarks>
    /// task-4：已用不足 1% 时显示「&lt;1%」而不是「0%」——0.x% 的用量被四舍五入成 0% 会让人误以为没占用空间。
    /// 只有真的一字节都没用时才显示 0%。
    /// </remarks>
    public string QuotaCaption => Quota.TotalBytes > 0
        ? $"共 {Quota.TotalText} · 已用 {QuotaFormat.Percent(Quota.UsedBytes, Quota.TotalBytes)}"
        : $"当前目录合计 {Quota.UsedText}";

    public bool ShowQuotaBar => Quota.TotalBytes > 0;

    /// <summary>行右键菜单「发送到设备」的子菜单数据源。</summary>
    public IReadOnlyList<DevicePeer> OnlinePeers =>
        _devices.Peers.Where(p => p.IsOnline).ToList();

    /// <summary>
    /// task-123：关键词变化 = 进入/退出服务端搜索态。
    /// 清空立即退出；非空则 300ms 防抖后请求 <c>GET /api/files/search</c>。
    /// </summary>
    partial void OnKeywordChanged(string value)
    {
        OnPropertyChanged(nameof(HasKeyword));
        OnPropertyChanged(nameof(SearchEmptyTitle));

        if (string.IsNullOrWhiteSpace(value))
        {
            ExitSearch();
            return;
        }

        IsSearchActive = true;
        _ = SearchDebouncedAsync(value.Trim());
    }

    // ── task-123：服务端搜索状态 ──────────────────────────────────
    private CancellationTokenSource? _searchCts;
    private int _searchSeq;
    private bool _searchRunning;
    private string? _pendingSearchQuery;
    private string _searchQuery = string.Empty;
    private string? _searchNextCursor;
    private IReadOnlyList<NodeItem> _searchResults = [];

    [ObservableProperty] private bool _isSearchActive;
    [ObservableProperty] private bool _isSearching;
    [ObservableProperty] private bool _hasSearchError;
    [ObservableProperty] private string _searchErrorText = string.Empty;
    [ObservableProperty] private bool _searchHasMore;
    [ObservableProperty] private long _searchTotal;

    /// <summary>task-127：失败是否可重试（后端/网络来源才显示「重试」，本地校验错误只给「清除」）。</summary>
    [ObservableProperty] private bool _searchErrorRetryable;

    /// <summary>搜索框里有内容（用于显示「清除」按钮）。</summary>
    public bool HasKeyword => !string.IsNullOrWhiteSpace(Keyword);

    /// <summary>空结果面板标题，例如「没有找到「report」」。</summary>
    public string SearchEmptyTitle => $"没有找到「{Keyword.Trim()}」";

    /// <summary>结果列表底部分页统计。</summary>
    public string SearchSummary => SearchHasMore
        ? $"已显示 {View.Count} / 共 {SearchTotal} 个结果"
        : $"共 {SearchTotal} 个结果";

    /// <summary>是否显示结果列表底部（分页 / 总数）：task-127 只在还有下一页时显示，避免三处计数重复。</summary>
    public bool ShowSearchFooter => IsSearchActive && !HasSearchError && SearchHasMore;

    partial void OnIsSearchActiveChanged(bool value)
    {
        OnPropertyChanged(nameof(CountText));
        OnPropertyChanged(nameof(ShowSearchFooter));
        OnPropertyChanged(nameof(SearchEmptyTitle));
        OnPropertyChanged(nameof(IsEmpty));
        OnPropertyChanged(nameof(IsSearchEmpty));
        OnPropertyChanged(nameof(IsFolderEmpty));
    }

    partial void OnIsSearchingChanged(bool value)
    {
        OnPropertyChanged(nameof(IsEmpty));
        OnPropertyChanged(nameof(IsSearchEmpty));
    }

    partial void OnHasSearchErrorChanged(bool value)
    {
        OnPropertyChanged(nameof(IsEmpty));
        OnPropertyChanged(nameof(IsSearchEmpty));
        OnPropertyChanged(nameof(ShowSearchFooter));
    }

    private async Task SearchDebouncedAsync(string query)
    {
        var seq = ++_searchSeq;
        _searchCts?.Cancel();
        var cts = new CancellationTokenSource();
        _searchCts = cts;

        try
        {
            await Task.Delay(300, cts.Token).ConfigureAwait(true);
        }
        catch (OperationCanceledException)
        {
            return;
        }

        if (seq != _searchSeq || !IsSearchActive) return;
        await RunSearchAsync(query, append: false).ConfigureAwait(true);
    }

    /// <summary>唯一真正发请求的入口：首页与「加载更多」共用。</summary>
    private async Task RunSearchAsync(string query, bool append)
    {
        // 同一时刻只允许一个请求；期间再输入的新词记为 pending，当前请求结束后立刻补跑，
        // 避免「请求 A 在飞时输入 B、B 被直接丢掉」导致结果停在旧关键词。
        if (_searchRunning)
        {
            if (!append) _pendingSearchQuery = query;
            return;
        }

        if (append && (!SearchHasMore || string.IsNullOrEmpty(_searchNextCursor))) return;

        if (!append)
        {
            _searchQuery = query;
            _searchNextCursor = null;
            HasSearchError = false;
            SearchErrorText = string.Empty;
        }

        _searchRunning = true;
        IsSearching = true;

        try
        {
            var outcome = await _files
                .SearchFilesAsync(query, workspaceId: null, limit: 50, append ? _searchNextCursor : null)
                .ConfigureAwait(true);

            // 期间又输入了新词：丢弃这次结果，避免旧响应覆盖新列表。
            if (!string.Equals(query, _searchQuery, StringComparison.Ordinal)) return;

            if (!outcome.IsOk || outcome.Page is null)
            {
                if (!append)
                {
                    _searchResults = [];
                    ApplySearchView();
                }

                HasSearchError = true;
                SearchErrorText = LoadRetry.Humanize(outcome.Error, "搜索失败，请检查网络后重试");
                SearchErrorRetryable = outcome.Retryable;
                return;
            }

            var page = outcome.Page;
            _searchNextCursor = string.IsNullOrEmpty(page.NextCursor) ? null : page.NextCursor;
            SearchHasMore = page.HasMore;
            SearchTotal = page.Total;
            _searchResults = append ? [.. _searchResults, .. page.Items] : page.Items;
            HasSearchError = false;
            SearchErrorText = string.Empty;
            SearchErrorRetryable = false;
            ApplySearchView();
        }
        finally
        {
            _searchRunning = false;
            IsSearching = false;

            // 请求期间又输入了新关键词：补跑一次（保留最新输入，不丢请求）。
            if (_pendingSearchQuery is { Length: > 0 } pending)
            {
                _pendingSearchQuery = null;
                if (IsSearchActive && !string.Equals(pending, _searchQuery, StringComparison.Ordinal))
                    _ = RunSearchAsync(pending, append: false);
            }
        }
    }

    /// <summary>把搜索结果写进 <see cref="View"/>（整体替换，沿用 task-93 的不可变列表约定）。</summary>
    private void ApplySearchView()
    {
        if (!Avalonia.Threading.Dispatcher.UIThread.CheckAccess())
        {
            Avalonia.Threading.Dispatcher.UIThread.Post(ApplySearchView);
            return;
        }

        var list = _searchResults;

        _selection.RemoveWhere(node => !list.Contains(node));
        if (Selected is not null && !list.Contains(Selected))
        {
            _syncingSelection = true;
            Selected = null;
            _syncingSelection = false;
        }

        foreach (var node in list) node.IsSelected = _selection.Contains(node);

        View = list;
        RaiseListStateChanged();
    }

    /// <summary>列表相关派生属性集中通知（目录列表与搜索结果共用）。</summary>
    private void RaiseListStateChanged()
    {
        OnPropertyChanged(nameof(CountText));
        OnPropertyChanged(nameof(SelectionText));
        OnPropertyChanged(nameof(HasSelection));
        OnPropertyChanged(nameof(Quota));
        OnPropertyChanged(nameof(IsEmpty));
        OnPropertyChanged(nameof(HasNoWorkspace));
        OnPropertyChanged(nameof(IsSearchEmpty));
        OnPropertyChanged(nameof(IsFolderEmpty));
        OnPropertyChanged(nameof(ShowSkeleton));
        OnPropertyChanged(nameof(IsRenaming));
        OnPropertyChanged(nameof(SearchSummary));
        OnPropertyChanged(nameof(ShowSearchFooter));
        OnPropertyChanged(nameof(SearchHasMore));
        OnPropertyChanged(nameof(SearchTotal));
    }

    private void ExitSearch()
    {
        _searchSeq++;
        _searchCts?.Cancel();
        _searchCts = null;
        _searchRunning = false;
        _searchQuery = string.Empty;
        _searchNextCursor = null;
        _searchResults = [];
        SearchHasMore = false;
        SearchTotal = 0;
        HasSearchError = false;
        SearchErrorText = string.Empty;
        SearchErrorRetryable = false;
        IsSearching = false;
        if (IsSearchActive) IsSearchActive = false;
        ApplyFilter();
    }

    [RelayCommand]
    private void ClearSearch() => Keyword = string.Empty;

    [RelayCommand]
    private async Task RetrySearchAsync()
    {
        var query = (Keyword ?? string.Empty).Trim();
        if (query.Length == 0) return;
        _searchNextCursor = null;
        await RunSearchAsync(query, append: false).ConfigureAwait(true);
    }

    [RelayCommand]
    private async Task LoadMoreSearchAsync()
    {
        if (!IsSearchActive || !SearchHasMore || IsSearching || HasSearchError) return;
        await RunSearchAsync(_searchQuery, append: true).ConfigureAwait(true);
    }

    /// <summary>列表滚到底部时由视图调用（与「加载更多」按钮等价）。</summary>
    public void MaybeLoadMoreSearch()
    {
        if (!IsSearchActive || !SearchHasMore || IsSearching || HasSearchError) return;
        if (string.IsNullOrEmpty(_searchNextCursor)) return;
        _ = RunSearchAsync(_searchQuery, append: true);
    }

    /// <summary>
    /// 打开一条搜索结果：文件走预览；目录切到它所在空间并进入该目录，同时退出搜索态。
    /// </summary>
    public async Task OpenSearchResultAsync(NodeItem? node)
    {
        if (node is null) return;

        if (!node.IsFolder)
        {
            await OpenPreviewAsync(node).ConfigureAwait(true);
            return;
        }

        var space = _files.Spaces.FirstOrDefault(s => s.IsPersonal && node.SpaceId == "personal")
                    ?? _files.Spaces.FirstOrDefault(s => s.WorkspaceId == node.SpaceId)
                    ?? _files.Spaces.FirstOrDefault(s => s.Id == node.SpaceId);

        if (space is not null && !ReferenceEquals(space, _files.CurrentSpace))
            await _files.SelectSpaceAsync(space).ConfigureAwait(true);

        var target = node.FullPath ?? node.ParentPath;
        Keyword = string.Empty;   // 退出搜索态
        _files.NavigateTo(target);
        Path = _files.CurrentPath;
        ClearSelection();
        SyncFromService();
    }

    partial void OnIsTileViewChanged(bool value)
    {
        LocalPrefs.Current.FilesViewMode = value ? "tile" : "list";
        LocalPrefs.Current.Save();
        OnPropertyChanged(nameof(ViewToggleTip));
        OnPropertyChanged(nameof(ViewToggleIcon));
    }

    partial void OnSelectedSortChanged(SortOption value)
    {
        if (value is null) return;
        LocalPrefs.Current.FileSortBy = value.Sort.ToString();
        LocalPrefs.Current.Save();
        ApplyFilter();
    }

    partial void OnSelectedChanged(NodeItem? value)
    {
        // 外部把 Selected 设为某一行时，默认收敛为「只选它」；
        // Ctrl 多选 / 全选走 ToggleSelection / SelectAll，它们会临时用 _syncingSelection 抑制这次收敛。
        if (!_syncingSelection)
        {
            _selection.Clear();
            if (value is not null) _selection.Add(value);
        }

        SyncSelectionVisuals();
        NotifySelectionChanged();
    }

    /// <summary>把多选集合映射到每个节点的 IsSelected（列表/平铺共用一套视觉状态）。</summary>
    private void SyncSelectionVisuals()
    {
        foreach (var node in View) node.IsSelected = _selection.Contains(node);
    }

    private void NotifySelectionChanged()
    {
        OnPropertyChanged(nameof(SelectionCount));
        OnPropertyChanged(nameof(HasSelection));
        OnPropertyChanged(nameof(HasSingleSelection));
        OnPropertyChanged(nameof(CanRenameSelection));
        OnPropertyChanged(nameof(CanShareSelection));
        OnPropertyChanged(nameof(CanMoveSelection));
        OnPropertyChanged(nameof(CanCopyLinkSelection));
        OnPropertyChanged(nameof(ShareSelectionHint));
        OnPropertyChanged(nameof(MoveSelectionHint));
        OnPropertyChanged(nameof(CopyLinkSelectionHint));
        OnPropertyChanged(nameof(SelectedNodes));
        OnPropertyChanged(nameof(SelectionBarText));
        OnPropertyChanged(nameof(SelectionText));
        OnPropertyChanged(nameof(IsConfirmingDelete));
        OnPropertyChanged(nameof(DeleteConfirmText));
    }

    /// <summary>Ctrl+单击：加入 / 移出选中集合（锚点跟随最后一次点击）。</summary>
    public void ToggleSelection(NodeItem? node)
    {
        if (node is null) return;

        foreach (var item in View) item.IsEditing = false;

        if (!_selection.Remove(node)) _selection.Add(node);

        _syncingSelection = true;
        Selected = _selection.Contains(node) ? node : _selection.FirstOrDefault();
        _syncingSelection = false;

        SyncSelectionVisuals();
        NotifySelectionChanged();
    }

    [RelayCommand]
    private void SelectAll()
    {
        if (View.Count == 0) return;

        _selection.Clear();
        foreach (var node in View) _selection.Add(node);

        _syncingSelection = true;
        Selected = View[0];
        _syncingSelection = false;

        SyncSelectionVisuals();
        NotifySelectionChanged();
    }

    [RelayCommand]
    private void ClearSelection()
    {
        _selection.Clear();

        _syncingSelection = true;
        Selected = null;
        _syncingSelection = false;

        SyncSelectionVisuals();
        NotifySelectionChanged();
    }

    // ═══════════════════════════════════════════════════════════
    //  分享链接（task-33）：右键创建 + 「我的分享」面板
    // ═══════════════════════════════════════════════════════════

    /// <summary>「我的分享」列表（真实接口 GET /api/shares）。</summary>
    public ObservableCollection<ShareLink> Shares { get; } = [];

    public IReadOnlyList<ShareExpiryOption> ShareExpiryOptions { get; } =
    [
        new("1 天", 1),
        new("7 天", 7),
        new("30 天", 30),
        new("永久", 0)
    ];

    [ObservableProperty] private bool _isSharesOpen;
    [ObservableProperty] private bool _isSharesLoading;
    [ObservableProperty] private string? _sharesError;
    [ObservableProperty] private ShareLink? _pendingRevoke;

    [ObservableProperty] private bool _isCreateShareOpen;
    [ObservableProperty] private NodeItem? _shareTarget;
    [ObservableProperty] private ShareExpiryOption? _shareExpiry;
    [ObservableProperty] private bool _shareRequiresCode;
    [ObservableProperty] private string _shareCode = string.Empty;
    [ObservableProperty] private string? _createShareError;
    [ObservableProperty] private bool _isCreatingShare;
    [ObservableProperty] private ShareLink? _createdShare;

    /// <summary>
    /// 创建成功卡片上展示的提取码。服务端 <c>ShareLinkView</c> 出于安全不回传明码
    /// （只有 requiresCode），所以这里保存用户刚输入的那个值供结果卡展示/转告；
    /// 关闭卡片即清空，不做任何本地持久化。
    /// </summary>
    [ObservableProperty] private string? _createdShareCode;

    /// <summary>永久链接的下载次数上限（服务端要求 maxDownloads &gt; 0，默认 100）。</summary>
    [ObservableProperty] private string _shareMaxDownloads = "100";

    /// <summary>永久链接（expiresDays=0，服务端约定 0=永久）：必须填下载次数上限。</summary>
    public bool IsSharePermanent => ShareExpiry?.Days is 0;

    partial void OnShareExpiryChanged(ShareExpiryOption? value)
        => OnPropertyChanged(nameof(IsSharePermanent));

    /// <summary>复制请求交给 View（ViewModel 不直接碰剪贴板）。</summary>
    public event EventHandler<string>? CopyRequested;

    /// <summary>View 写剪贴板成功后回调：给一条轻提示。</summary>
    public void NotifyCopied() => _notify.Notify("链接已复制", NoticeKind.Success);

    public bool ShowSharesEmpty => !IsSharesLoading && SharesError is null && Shares.Count == 0;

    public bool ShowSharesError => !IsSharesLoading && !string.IsNullOrEmpty(SharesError);

    public bool CanCreateShare => ShareTarget is not null;

    /// <summary>右键菜单用：单个文件或文件夹可分享；多选且包含该行时禁用（后端 POST /api/shares 是单条目接口）。</summary>
    public bool CanCreateShareFor(NodeItem node) => !(SelectionCount > 1 && _selection.Contains(node));

    /// <summary>task-183：分享目标是文件夹（匿名 /download 返回流式 ZIP）。</summary>
    public bool IsShareFolder => ShareTarget?.IsFolder == true;

    /// <summary>分享对话框标题：文件夹/文件区分，避免在文件夹上仍写“文件”。</summary>
    public string ShareDialogTitle => IsShareFolder ? "创建文件夹分享链接" : "创建分享链接";

    /// <summary>分享目标条目类型文案（对话框信息行）。</summary>
    public string ShareTargetKindText => IsShareFolder ? "文件夹" : "文件";

    /// <summary>分享目标大小文案：文件夹后端 fileSize 恒为 0，显示「ZIP 打包下载」而不是「0 B」。</summary>
    public string ShareTargetMetaText => IsShareFolder
        ? "文件夹 · ZIP 打包下载"
        : ShareTarget?.SizeText ?? string.Empty;

    /// <summary>文件夹分享的下载方式提示（对话框内展示）。</summary>
    public string ShareZipHint =>
        "文件夹会打包为 ZIP 下载：匿名访问者可查看首层子项并「下载全部」，实际体积以下行 ZIP 为准。";

    partial void OnIsSharesLoadingChanged(bool value)
    {
        OnPropertyChanged(nameof(ShowSharesEmpty));
        OnPropertyChanged(nameof(ShowSharesError));
        OnPropertyChanged(nameof(ShowSharesFilterEmpty));
        OnPropertyChanged(nameof(ShowSharesList));
    }

    partial void OnSharesErrorChanged(string? value)
    {
        OnPropertyChanged(nameof(ShowSharesEmpty));
        OnPropertyChanged(nameof(ShowSharesError));
        OnPropertyChanged(nameof(ShowSharesFilterEmpty));
        OnPropertyChanged(nameof(ShowSharesList));
    }

    partial void OnShareTargetChanged(NodeItem? value)
    {
        OnPropertyChanged(nameof(CanCreateShare));
        OnPropertyChanged(nameof(IsShareFolder));
        OnPropertyChanged(nameof(ShareDialogTitle));
        OnPropertyChanged(nameof(ShareTargetKindText));
        OnPropertyChanged(nameof(ShareTargetMetaText));
    }

    /// <summary>右键「创建分享链接」入口：单个文件或文件夹（task-183 文件夹下载为流式 ZIP）。</summary>
    public void BeginCreateShare()
    {
        var node = Selected;
        if (node is null || SelectionCount > 1)
        {
            _notify.Notify("请先选中单个文件或文件夹（多选不支持创建分享链接）", NoticeKind.Warning);
            return;
        }

        ShareTarget = node;
        ShareExpiry = ShareExpiryOptions[1];
        ShareRequiresCode = false;
        ShareCode = string.Empty;
        ShareMaxDownloads = "100";
        CreateShareError = null;
        CreatedShare = null;
        CreatedShareCode = null;
        IsCreateShareOpen = true;
    }

    [RelayCommand]
    private void CloseCreateShare()
    {
        // 「完成」/「取消」共用：必须同时清掉成功卡（卡片可见性 = CreatedShare != null，见 FilesPage.axaml:919），
        // 否则点了「完成」只关表单、结果卡永久留在屏幕上（task-107 缺陷）。
        IsCreateShareOpen = false;
        CreatedShare = null;
        CreatedShareCode = null;
        ShareTarget = null;
        CreateShareError = null;
    }

    [RelayCommand]
    private async Task CreateShareAsync()
    {
        if (ShareTarget is null || IsCreatingShare) return;

        var code = ShareRequiresCode ? ShareCode.Trim() : null;
        if (ShareRequiresCode
            && (code is null || code.Length is < 4 or > 8 || !code.All(char.IsLetterOrDigit)))
        {
            CreateShareError = "提取码需为 4–8 位字母或数字";
            return;
        }

        IsCreatingShare = true;
        CreateShareError = null;
        try
        {
            // 永久链接：服务端要求 maxDownloads > 0；非永久不限制（传 0）。
            var maxDownloads = 0;
            if (IsSharePermanent)
            {
                if (!int.TryParse(ShareMaxDownloads.Trim(), out maxDownloads) || maxDownloads <= 0)
                {
                    CreateShareError = "永久链接必须填写下载次数上限（正整数，默认 100）";
                    return;
                }
            }

            var (share, error) = await _files
                .CreateShareAsync(ShareTarget, ShareRequiresCode, code, ShareExpiry?.Days, maxDownloads)
                .ConfigureAwait(true);

            if (share is null)
            {
                CreateShareError = LoadRetry.Humanize(error, "创建失败，请稍后重试");
                return;
            }

            CreatedShare = share;
            // 服务端不回传明码：结果卡展示用户刚输入的提取码（没启用则为 null）。
            CreatedShareCode = ShareRequiresCode ? code : null;
            await LoadSharesAsync(silent: true).ConfigureAwait(true);
        }
        finally
        {
            IsCreatingShare = false;
        }
    }

    [RelayCommand]
    private void CopyShareUrl(string? url)
    {
        if (!string.IsNullOrWhiteSpace(url)) CopyRequested?.Invoke(this, url);
    }

    [RelayCommand]
    private void OpenShareUrl(string? url)
    {
        if (string.IsNullOrWhiteSpace(url)) return;
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = url,
                UseShellExecute = true
            });
        }
        catch
        {
            _notify.Notify("无法在浏览器中打开链接", NoticeKind.Warning);
        }
    }

    [RelayCommand]
    private async Task OpenSharesAsync()
    {
        IsSharesOpen = true;
        await LoadSharesAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private void CloseShares() => IsSharesOpen = false;

    [RelayCommand]
    private Task RefreshSharesAsync() => LoadSharesAsync();

    private DateTimeOffset _sharesLoadedAt;

    /// <summary>
    /// 「分享」独立管理页显示时调用：10 秒内不重复请求，避免页面来回切换反复打接口；
    /// 页面上的「刷新」按钮仍走 <see cref="RefreshSharesAsync"/> 强制重拉。
    /// </summary>
    public async Task EnsureSharesLoadedAsync()
    {
        if (IsSharesLoading) return;
        if (DateTimeOffset.UtcNow - _sharesLoadedAt < TimeSpan.FromSeconds(10)) return;
        await LoadSharesAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private void RequestRevoke(ShareLink share) => PendingRevoke = share;

    [RelayCommand]
    private void CancelRevoke() => PendingRevoke = null;

    [RelayCommand]
    private async Task ConfirmRevokeAsync()
    {
        if (PendingRevoke is null) return;
        var share = PendingRevoke;
        PendingRevoke = null;

        var error = await _files.RevokeShareAsync(share.Id).ConfigureAwait(true);
        if (error is null)
        {
            Shares.Remove(share);
            RefreshFilteredShares();
            _notify.Notify("分享链接已撤销", NoticeKind.Success);
            OnPropertyChanged(nameof(ShowSharesEmpty));
        }
        else
        {
            _notify.Notify(error, NoticeKind.Error);
        }
    }

    private async Task LoadSharesAsync(bool silent = false)
    {
        if (!silent) IsSharesLoading = true;
        SharesError = null;
        try
        {
            var (shares, error) = await _files.GetSharesAsync().ConfigureAwait(true);
            if (shares is null)
            {
                SharesError = LoadRetry.Humanize(error, "加载分享记录失败，请稍后重试");
                return;
            }

            // task-135：分享记录刷新走逐项同步，避免 Clear 的 Reset 残留行。
            CollectionSync.Apply(Shares, shares);
            RefreshFilteredShares();
            OnPropertyChanged(nameof(ShowSharesEmpty));
        }
        finally
        {
            _sharesLoadedAt = DateTimeOffset.UtcNow;
            IsSharesLoading = false;
        }
    }

    partial void OnPendingDeleteChanged(NodeItem? value)
    {
        OnPropertyChanged(nameof(IsConfirmingDelete));
        OnPropertyChanged(nameof(DeleteConfirmText));
    }

    partial void OnSelectedSpaceChanged(FileSpace? value)
    {
        if (_syncingSpace || value is null) return;

        // 通过切换器换空间：路径复位到根目录并重新拉取。
        _ = SwitchSpaceAsync(value);
    }

    private async Task SwitchSpaceAsync(FileSpace space)
    {
        await _files.SelectSpaceAsync(space).ConfigureAwait(true);
        Keyword = string.Empty;
        ClearSelection();
        SyncFromService();
    }

    /// <summary>供工作区卡片「进入」调用：找到该工作区对应的空间并切换。</summary>
    public Task SelectWorkspaceAsync(WorkspaceItem workspace)
    {
        var space = _files.Spaces.FirstOrDefault(s =>
            string.Equals(s.WorkspaceId, workspace.Id, StringComparison.Ordinal));
        return space is null ? Task.CompletedTask : SwitchSpaceAsync(space);
    }

    // ── 定位到网盘里的某个文件（task-4：传输中心「定位」/ 回收站恢复后跳转） ──

    /// <summary>
    /// 切到指定空间 → 打开指定目录 → 选中（行/卡片高亮）并滚动到可见。
    /// <paramref name="spaceId"/> 为 null/未知时沿用当前空间；<c>"personal"</c> 表示个人空间。
    /// 返回是否真的在列表里定位到了该条目（同名自动改名、目标已被移动时可能定位不到）。
    /// </summary>
    public async Task<bool> RevealCloudLocationAsync(string? spaceId, string? parentPath, string? fileName)
    {
        var space = ResolveSpace(spaceId);
        if (space is not null && !ReferenceEquals(space, _files.CurrentSpace))
            await _files.SelectSpaceAsync(space).ConfigureAwait(true);

        return await RevealCloudFileAsync(parentPath, fileName).ConfigureAwait(true);
    }

    /// <summary>
    /// 在当前空间内打开 <paramref name="parentPath"/> 并选中 <paramref name="fileName"/>。
    /// 目录路径规范化与后端一致：<c>/</c> 或 <c>/a/b</c>（无尾斜杠）。
    /// </summary>
    public async Task<bool> RevealCloudFileAsync(string? parentPath, string? fileName)
    {
        // 目标目录可能就在搜索结果里，先退出搜索态，避免列表还是结果集。
        if (IsSearchActive || !string.IsNullOrWhiteSpace(Keyword)) Keyword = string.Empty;

        var target = NormalizeFolderPath(parentPath);
        if (NormalizeFolderPath(_files.CurrentPath) != target) _files.NavigateTo(target);

        // NavigateTo 的刷新是不可等待的；再 await 一次确保列表已是目标目录的真实数据。
        await _files.ReloadListAsync().ConfigureAwait(true);
        // 服务层 Changed 的界面同步可能还排在调度队列里，这里直接同步一次再读 View。
        SyncFromService();

        ClearSelection();
        if (string.IsNullOrWhiteSpace(fileName)) return false;

        var node = View.FirstOrDefault(n =>
            string.Equals(n.Name, fileName, StringComparison.OrdinalIgnoreCase));
        if (node is null) return false;

        Selected = node;                 // 行/卡片选中态（高亮）
        RevealRequested?.Invoke(node);   // 滚到可见位置
        return true;
    }

    /// <summary>按 id/workspaceId 找空间；找不到返回 null（保持当前空间，绝不乱切）。</summary>
    private FileSpace? ResolveSpace(string? spaceId)
    {
        if (string.IsNullOrWhiteSpace(spaceId)) return null;
        if (string.Equals(spaceId, "personal", StringComparison.Ordinal))
            return _files.Spaces.FirstOrDefault(s => s.IsPersonal);

        return _files.Spaces.FirstOrDefault(s => string.Equals(s.Id, spaceId, StringComparison.Ordinal))
               ?? _files.Spaces.FirstOrDefault(s =>
                   string.Equals(s.WorkspaceId, spaceId, StringComparison.Ordinal));
    }

    /// <summary>目录路径规范化：空/根 → <c>/</c>，其余去掉尾斜杠、补前导斜杠。</summary>
    private static string NormalizeFolderPath(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return "/";
        var trimmed = path.Trim().Replace('\\', '/');
        if (trimmed == "/") return "/";
        trimmed = trimmed.TrimEnd('/');
        return trimmed.Length == 0 ? "/" : trimmed.StartsWith('/') ? trimmed : "/" + trimmed;
    }


    /// <summary>把服务层的路径 / 加载态 / 错误态同步到界面。</summary>
    private void SyncFromService()
    {
        // 服务层可能在后台线程拉取数据后触发 Changed，集合属性必须在 UI 线程更新。
        if (!Avalonia.Threading.Dispatcher.UIThread.CheckAccess())
        {
            Avalonia.Threading.Dispatcher.UIThread.Post(SyncFromService);
            return;
        }

        Path = _files.CurrentPath;
        IsLoading = _files.IsLoading;

        // task-43：文件列表错误与空间列表错误分开记录，但页面上用同一个提示条；
        // 空间加载失败时绝不能把「没有可用空间」的空态摆出来。
        var filesError = _files.LastError;
        var spacesError = _files.LastSpacesError;
        // task-151：页面级错误统一 Humanize，断网/5xx/401/403 等显示同一句中文。
        HasError = filesError is not null || spacesError is not null;
        ErrorText = LoadRetry.Humanize(filesError ?? spacesError, "加载失败，请稍后重试");

        var current = _files.CurrentSpace;
        if (current is null || !ReferenceEquals(SelectedSpace, current))
        {
            // 服务端刷新后会重建 FileSpace 对象，按 Id 对齐
            var matched = current is null
                ? _files.Spaces.FirstOrDefault()
                : _files.Spaces.FirstOrDefault(s => s.Id == current.Id);
            _syncingSpace = true;
            SelectedSpace = matched;
            _syncingSpace = false;
        }

        RebuildBreadcrumbs();
        ApplyFilter();
        OnPropertyChanged(nameof(Quota));
        OnPropertyChanged(nameof(IsPersonalSpace));
        OnPropertyChanged(nameof(CurrentSpaceName));
        OnPropertyChanged(nameof(SpaceIcon));
        OnPropertyChanged(nameof(SpaceSubtitle));
        OnPropertyChanged(nameof(FolderEmptyTitle));
        OnPropertyChanged(nameof(FolderEmptyHint));
        OnPropertyChanged(nameof(QuotaCaption));
        OnPropertyChanged(nameof(ShowQuotaBar));

        // 回收站跟随当前空间（task-4）：切换空间后头部说明要跟着变。
        OnPropertyChanged(nameof(TrashSpaceCaption));
        OnPropertyChanged(nameof(TrashSpaceHint));

        SyncUploadOverlays();
    }

    /// <summary>
    /// 把上传队列里的真实进度映射到列表行 / 平铺卡片的 2px 覆盖层（task-42）。
    /// 同名的多个任务取最新一条；不在队列里的节点清空覆盖层。
    /// </summary>
    private void SyncUploadOverlays()
    {
        if (!Avalonia.Threading.Dispatcher.UIThread.CheckAccess())
        {
            Avalonia.Threading.Dispatcher.UIThread.Post(SyncUploadOverlays);
            return;
        }

        var active = new Dictionary<(string Name, string Path), Bond.Models.UploadTaskItem>();
        foreach (var task in _uploads.Uploads)
        {
            if (task.Status is not (Bond.Models.UploadTaskStatus.Uploading or Bond.Models.UploadTaskStatus.Hashing))
                continue;
            active[(task.FileName, task.ParentPath)] = task;
        }

        foreach (var node in View)
        {
            if (active.TryGetValue((node.Name, node.ParentPath), out var task))
            {
                node.IsUploading = true;
                node.UploadProgress = Math.Round(task.Progress * 100, 1);
            }
            else if (node.IsUploading)
            {
                node.IsUploading = false;
                node.UploadProgress = 0;
            }
        }

        SyncUploadRows();
    }

    private void RebuildBreadcrumbs()
    {
        var workspaceName = _files.CurrentSpace?.Name ?? "个人空间";
        var segments = Path.Split('/', StringSplitOptions.RemoveEmptyEntries);

        // 先算好每一段再写入：避免对集合做"替换"操作（ItemsControl 对 Replace 的处理会留旧容器）
        var items = new List<Breadcrumb>(segments.Length + 1);
        if (segments.Length == 0)
        {
            items.Add(new Breadcrumb(workspaceName, "/", true));
        }
        else
        {
            items.Add(new Breadcrumb(workspaceName, "/", false));
            var accumulated = string.Empty;
            for (var i = 0; i < segments.Length; i++)
            {
                accumulated += "/" + segments[i];
                items.Add(new Breadcrumb(segments[i], accumulated, i == segments.Length - 1));
            }
        }

        // task-93：整体替换而不是 Clear()+Add()，避免派发 CollectionChanged（见 Breadcrumbs 注释）。
        // 内容没变就不重绑，减少每次 Changed 的无谓 ItemsSource 重建。
        var changed = Breadcrumbs.Count != items.Count;
        if (!changed)
        {
            for (var i = 0; i < items.Count; i++)
            {
                var old = Breadcrumbs[i];
                var now = items[i];
                if (old.Label != now.Label || old.Path != now.Path || old.IsLast != now.IsLast)
                {
                    changed = true;
                    break;
                }
            }
        }

        if (changed) Breadcrumbs = items;
    }

    private void ApplyFilter()
    {
        // 保险：View 绑定给两个 ItemsControl，必须在 UI 线程重建
        if (!Avalonia.Threading.Dispatcher.UIThread.CheckAccess())
        {
            Avalonia.Threading.Dispatcher.UIThread.Post(ApplyFilter);
            return;
        }

        // task-123：搜索态下目录列表不参与过滤，View 由服务端结果驱动。
        if (IsSearchActive)
        {
            ApplySearchView();
            return;
        }

        var keyword = Keyword?.Trim() ?? string.Empty;

        // 目录导航：只显示当前路径下的节点（后端按 parentPath 返回）
        var scoped = _files.Nodes.Where(n =>
            string.IsNullOrEmpty(n.ParentPath)
            || string.Equals(n.ParentPath, Path, StringComparison.OrdinalIgnoreCase));

        var filtered = string.IsNullOrEmpty(keyword)
            ? scoped
            : scoped.Where(n => n.Name.Contains(keyword, StringComparison.OrdinalIgnoreCase));

        var list = filtered.ToList();
        list.Sort(NodeComparer.For(SelectedSort?.Sort ?? FileSort.NameAsc));

        // 目录/搜索变化后，选中集合里已不可见的节点自动剔除，锚点也随之失效
        _selection.RemoveWhere(n => !list.Contains(n));
        if (Selected is not null && !list.Contains(Selected))
        {
            _syncingSelection = true;
            Selected = null;
            _syncingSelection = false;
        }

        // task-93：整体替换而非 Clear()+Add()，避免派发 CollectionChanged（见 View 注释）。
        // 选中态先在节点上写好，再一次性换列表，界面只会重绑一次 ItemsSource。
        foreach (var node in list)
        {
            node.IsSelected = _selection.Contains(node);
        }

        View = list;
        RaiseListStateChanged();
    }

    // ── 导航 ──────────────────────────────────────────────────

    [RelayCommand]
    private void GoUp()
    {
        _files.GoUp();
        Path = _files.CurrentPath;
        Keyword = string.Empty;
        ClearSelection();
        SyncFromService();
    }

    [RelayCommand]
    private void NavigateCrumb(Breadcrumb? crumb)
    {
        if (crumb is null || crumb.IsLast) return;
        _files.NavigateTo(crumb.Path);
        Path = _files.CurrentPath;
        Keyword = string.Empty;
        ClearSelection();
        SyncFromService();
    }

    /// <summary>双击文件 → 打开预览覆盖层（文件夹仍进入下一级）。</summary>
    public async Task OpenPreviewAsync(NodeItem? node)
    {
        if (node is null) return;
        if (node.Kind == NodeKind.Folder)
        {
            Activate(node);
            return;
        }

        await _preview.OpenAsync(node, View);
    }

    /// <summary>由 View 的双击事件调用（文件夹进入下一级）。</summary>
    public void Activate(NodeItem? node)
    {
        if (node is null || node.Kind != NodeKind.Folder) return;

        _files.EnterFolder(node);
        Path = _files.CurrentPath;
        Keyword = string.Empty;
        ClearSelection();
        SyncFromService();
    }

    [RelayCommand]
    private void ToggleView() => IsTileView = !IsTileView;

    /// <summary>错误态里的「重试」和列表刷新都走这里。</summary>
    [RelayCommand]
    private void Refresh()
    {
        // task-43：重试同时重拉空间列表（此前只有文件列表会重试，空间失败后一直空着）。
        _ = _files.RefreshSpacesAsync();
        _files.NavigateTo(_files.CurrentPath);
        SyncFromService();
    }

    // ── 上传 / 下载 ───────────────────────────────────────────

    [RelayCommand]
    private async Task UploadAsync()
    {
        var paths = await PickUploadFilesAsync("选择要上传的文件").ConfigureAwait(true);
        if (paths.Length == 0) return;

        // 走统一上传队列：秒传/分片/断点续传 + 逐文件真实进度（task-42）
        _uploads.Enqueue(paths);
        _notify.Notify($"已加入上传队列：{paths.Length} 个文件 → {(_files.CurrentPath == "/" ? "根目录" : _files.CurrentPath)}",
            NoticeKind.Success);
        SyncUploadOverlays();
    }

    public async Task DownloadAsync(NodeItem? node)
    {
        if (node is null) return;
        if (node.Kind == NodeKind.Folder)
        {
            _notify.Notify("文件夹请进入后逐项下载", NoticeKind.Warning);
            return;
        }

        var directory = LocalPrefs.Current.DownloadDirectory;
        var saved = await _files.DownloadAsync(node, directory).ConfigureAwait(true);

        if (saved is null)
            _notify.Notify($"下载失败：{node.Name}", NoticeKind.Error);
        else
            _notify.Notify($"已下载到 {saved}", NoticeKind.Success);
    }

    [RelayCommand]
    private Task DownloadSelectedAsync() => DownloadAsync(Selected);

    /// <summary>操作条「下载」：顺序下载所有选中的文件（文件夹跳过并提示）。</summary>
    [RelayCommand]
    private async Task DownloadSelectionAsync()
    {
        var files = _selection.Where(n => n.IsFile).ToList();
        var folders = _selection.Count - files.Count;

        if (files.Count == 0)
        {
            _notify.Notify(folders > 0 ? "文件夹请进入后逐项下载" : "请先选择要下载的文件",
                NoticeKind.Warning);
            return;
        }

        var directory = LocalPrefs.Current.DownloadDirectory;
        var ok = 0;
        foreach (var file in files)
        {
            var saved = await _files.DownloadAsync(file, directory).ConfigureAwait(true);
            if (saved is not null) ok++;
        }

        if (ok == files.Count)
            _notify.Notify(files.Count == 1 ? $"已下载到 {directory}" : $"已下载 {ok} 个文件到 {directory}",
                NoticeKind.Success);
        else
            _notify.Notify($"已下载 {ok}/{files.Count} 个文件，部分失败", NoticeKind.Warning);
    }

    [RelayCommand]
    private void RenameSelected() => BeginRename(Selected);

    [RelayCommand]
    private void RenameSelection() => BeginRename(_selection.Count == 1 ? _selection.First() : null);

    /// <summary>
    /// task-133：F2 快捷键入口。单选（或仅锚点选中）直接进入行内重命名；
    /// 多选时明确提示而不是静默失败，避免「按了没反应」。
    /// </summary>
    public void RequestRenameSelection()
    {
        if (_selection.Count > 1)
        {
            _notify.Notify("重命名一次只能改一个，请先取消多选", NoticeKind.Warning);
            return;
        }

        BeginRename(_selection.Count == 1 ? _selection.First() : Selected);
    }

    /// <summary>task-133：Delete / Ctrl+D 的统一入口（多选走集合命令，单选走单项）。</summary>
    public void RequestDeleteFromKeyboard()
    {
        if (_selection.Count > 1) RequestDeleteSelectionCommand.Execute(null);
        else RequestDelete(Selected);
    }

    /// <summary>task-133：Ctrl+D 下载入口，多选逐个入队（与操作条「下载」一致）。</summary>
    public void RequestDownloadFromKeyboard()
    {
        if (_selection.Count > 1) DownloadSelectionCommand.Execute(null);
        else DownloadSelectedCommand.Execute(null);
    }

    /// <summary>操作条「更多」→ 发送到设备：逐个取回云端文件后交给传输服务。</summary>
    public async Task SendSelectionToDeviceAsync(DevicePeer? peer)
    {
        if (peer is null) return;
        var files = _selection.Where(n => n.IsFile).ToList();
        if (files.Count == 0)
        {
            _notify.Notify("请先选择要发送的文件", NoticeKind.Warning);
            return;
        }

        foreach (var file in files)
            await SendToDeviceAsync(file, peer).ConfigureAwait(true);
    }

    /// <summary>右键菜单「发送到设备」：先把云端文件取回本机，再交给传输服务。</summary>
    public async Task SendToDeviceAsync(NodeItem? node, DevicePeer? peer)
    {
        if (node is null || peer is null) return;
        if (!peer.IsOnline)
        {
            _notify.Notify($"{peer.Name} 当前离线", NoticeKind.Warning);
            return;
        }

        var temp = System.IO.Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Bond", "send-cache");
        var local = await _files.DownloadAsync(node, temp).ConfigureAwait(true);

        if (local is null)
        {
            _notify.Notify($"取回文件失败：{node.Name}", NoticeKind.Error);
            return;
        }

        await _transfers.SendToAsync(peer, [local]).ConfigureAwait(true);
        _notify.Notify($"正在发送 {node.Name} 到 {peer.Name}", NoticeKind.Success);
    }

    // ── 重命名（行内编辑：Enter 提交 / Esc 取消 / 失焦提交）────

    /// <summary>进入重命名：自动聚焦、预选主文件名由 View 负责。</summary>
    public void BeginRename(NodeItem? node)
    {
        if (node is null) return;
        PendingDelete = null;

        // 同一时间只允许一行处于编辑态
        foreach (var item in View) item.IsEditing = false;

        node.EditName = node.Name;
        node.EditError = null;
        node.IsEditing = true;
        Selected = node;
        OnPropertyChanged(nameof(IsRenaming));
    }

    /// <summary>输入时实时校验：空名 / 非法字符就地在行内标红。</summary>
    public void ValidateRename(NodeItem? node)
    {
        if (node is null || !node.IsEditing) return;
        node.EditError = ValidateName(node.EditName);
    }

    /// <summary>提交重命名（Enter / 失焦都走这里）。</summary>
    public async Task CommitRenameAsync(NodeItem? node)
    {
        if (node is null || !node.IsEditing) return;

        var name = (node.EditName ?? string.Empty).Trim();
        var error = ValidateName(name);
        if (error is not null)
        {
            node.EditError = error;
            return;
        }

        if (string.Equals(name, node.Name, StringComparison.Ordinal))
        {
            ExitRename(node);
            return;
        }

        try
        {
            await _files.RenameAsync(node, name).ConfigureAwait(true);
            _notify.Notify($"已重命名为「{name}」", NoticeKind.Success);
            ExitRename(node);
            Selected = null;
        }
        catch (Exception ex)
        {
            node.EditError = ex.Message;
        }
    }

    public void CancelRename(NodeItem? node)
    {
        if (node is null) return;
        ExitRename(node);
    }

    private void ExitRename(NodeItem node)
    {
        node.IsEditing = false;
        node.EditError = null;
        OnPropertyChanged(nameof(IsRenaming));
    }

    /// <summary>返回 null 表示合法；否则为行内错误文案。</summary>
    private static string? ValidateName(string? raw)
    {
        var name = (raw ?? string.Empty).Trim();
        if (name.Length == 0) return "名称不能为空";
        if (name is "." or "..") return "名称不合法";
        if (name.IndexOfAny(System.IO.Path.GetInvalidFileNameChars()) >= 0) return "包含非法字符";
        return null;
    }

    // ── 删除（二次确认）───────────────────────────────────────

    public void RequestDelete(NodeItem? node)
    {
        if (node is null) return;
        foreach (var item in View) item.IsEditing = false;
        _pendingDeleteMany.Clear();
        PendingDelete = node;
        NotifySelectionChanged();
    }

    /// <summary>操作条「删除」：对当前多选集合做二次确认。</summary>
    [RelayCommand]
    private void RequestDeleteSelection()
    {
        if (_selection.Count == 0) return;
        foreach (var item in View) item.IsEditing = false;
        PendingDelete = null;
        _pendingDeleteMany.Clear();
        _pendingDeleteMany.AddRange(_selection);
        NotifySelectionChanged();
    }

    [RelayCommand]
    private void DeleteSelected() => RequestDelete(Selected);

    [RelayCommand]
    private async Task ConfirmDeleteAsync()
    {
        var nodes = PendingDelete is not null
            ? new List<NodeItem> { PendingDelete }
            : _pendingDeleteMany.ToList();

        if (nodes.Count == 0) return;

        PendingDelete = null;
        _pendingDeleteMany.Clear();
        NotifySelectionChanged();

        var ok = 0;
        Exception? firstError = null;
        foreach (var node in nodes)
        {
            try
            {
                await _files.DeleteAsync(node).ConfigureAwait(true);
                _selection.Remove(node);
                if (ReferenceEquals(Selected, node)) Selected = null;
                ok++;
            }
            catch (Exception ex)
            {
                firstError ??= ex;
            }
        }

        SyncSelectionVisuals();
        NotifySelectionChanged();

        if (ok == nodes.Count)
            _notify.Notify(nodes.Count == 1 ? $"已删除 {nodes[0].Name}" : $"已删除 {ok} 项",
                NoticeKind.Success);
        else
            _notify.Notify(firstError?.Message ?? $"已删除 {ok}/{nodes.Count} 项，部分失败",
                NoticeKind.Error);
    }

    [RelayCommand]
    private void CancelDelete()
    {
        PendingDelete = null;
        _pendingDeleteMany.Clear();
        NotifySelectionChanged();
    }
}

/// <summary>传输中心筛选（task-14）：设备中继任务 / 上传会话 / 下载会话。</summary>
public enum TransferFilter
{
    All,
    Devices,
    Uploads,
    Downloads
}

public partial class TransferPageViewModel : ObservableObject
{
    private readonly ITransferService _transfers;
    private readonly IUploadService _uploads;
    private readonly INotificationCenter _notify;

    public TransferPageViewModel(ITransferService transfers, IUploadService uploads, INotificationCenter notify)
    {
        _transfers = transfers;
        _uploads = uploads;
        _notify = notify;
        _transfers.Changed += (_, _) => Refresh();
        _transfers.SessionsChanged += (_, _) => Refresh();
        _uploads.UploadsChanged += (_, _) => RefreshUploads();

        if (_transfers.Items is System.Collections.Specialized.INotifyCollectionChanged incc)
            incc.CollectionChanged += (_, _) => Refresh();

        if (_transfers.Sessions is System.Collections.Specialized.INotifyCollectionChanged sessionsChanged)
            sessionsChanged.CollectionChanged += (_, _) => Refresh();

        if (_uploads.Uploads is System.Collections.Specialized.INotifyCollectionChanged uploadsChanged)
            uploadsChanged.CollectionChanged += (_, _) => Refresh();

        Refresh();
        RefreshUploads();
    }

    /// <summary>上传队列（真实进度；与「传输」列表独立）。</summary>
    public ObservableCollection<Bond.Models.UploadTaskItem> Uploads => _uploads.Uploads;

    /// <summary>是否有上传任务（含已完成，便于展示结果）。</summary>
    [ObservableProperty]
    private bool _hasUploads;

    /// <summary>进行中的上传任务数。</summary>
    [ObservableProperty]
    private int _uploadingCount;

    [RelayCommand]
    private void CancelUpload(Bond.Models.UploadTaskItem? task)
    {
        if (task is not null) _uploads.Cancel(task);
    }

    [RelayCommand]
    private async Task ResumeUploadAsync(Bond.Models.UploadTaskItem? task)
    {
        if (task is not null) await _uploads.ResumeAsync(task).ConfigureAwait(true);
    }

    [RelayCommand]
    private void RemoveUpload(Bond.Models.UploadTaskItem? task)
    {
        if (task is not null) _uploads.Remove(task);
    }

    private void RefreshUploads()
    {
        if (!Avalonia.Threading.Dispatcher.UIThread.CheckAccess())
        {
            Avalonia.Threading.Dispatcher.UIThread.Post(RefreshUploads);
            return;
        }

        HasUploads = _uploads.Uploads.Count > 0;
        UploadingCount = _uploads.Uploads.Count(t =>
            t.Status is Bond.Models.UploadTaskStatus.Uploading or Bond.Models.UploadTaskStatus.Hashing);
    }

    /// <summary>行对象缓存：同一数据源始终复用同一 <see cref="TransferRow"/>，避免列表容器抖动。</summary>
    private readonly Dictionary<string, TransferRow> _rows = new(StringComparer.Ordinal);

    /// <summary>上传队列任务 → 会话镜像（task-14：上传会话也算传输中心的一类会话）。</summary>
    private readonly Dictionary<string, TransferSession> _uploadSessions = new(StringComparer.Ordinal);

    private readonly Dictionary<string, Bond.Models.UploadTaskItem> _uploadSources = new(StringComparer.Ordinal);

    // ── task-4：传输中心「定位」到客户端内的云端位置 ──────────────
    // 网盘上传/下载会话记住「空间 + 云端目录」的快照；设备传输任务才打开系统资源管理器。
    private readonly Dictionary<string, TransferCloudTarget> _cloudTargets = new(StringComparer.Ordinal);

    /// <summary>文件页 ViewModel（单例；用于快照当前空间/目录，取不到时安全返回 null）。</summary>
    private static FilesPageViewModel? ShellFiles
        => App.Services?.GetService(typeof(FilesPageViewModel)) as FilesPageViewModel;

    /// <summary>请求视图切到文件页并定位到该云端条目（TransferPage code-behind 订阅）。</summary>
    public event Action<TransferCloudTarget>? RevealInFilesRequested;

    /// <summary>筛选后的列表（页签切换只影响这里）。</summary>
    public ObservableCollection<TransferRow> View { get; } = [];

    [ObservableProperty]
    private TransferFilter _filter = TransferFilter.All;

    [ObservableProperty]
    private double _overall;

    [ObservableProperty]
    private bool _hasActive;

    [ObservableProperty]
    private int _runningCount;

    [ObservableProperty]
    private int _doneCount;

    [ObservableProperty]
    private int _failedCount;

    [ObservableProperty]
    private string _totalTraffic = "0 B";

    [ObservableProperty]
    private bool _isLoading;

    /// <summary>task-43：传输列表加载失败时的提示（不再是静默空态）。</summary>
    [ObservableProperty]
    private string? _errorText;

    public bool HasError => !string.IsNullOrEmpty(ErrorText);

    partial void OnErrorTextChanged(string? value) => OnPropertyChanged(nameof(HasError));

    public bool IsFilterAll => Filter == TransferFilter.All;
    public bool IsFilterDevices => Filter == TransferFilter.Devices;
    public bool IsFilterUploads => Filter == TransferFilter.Uploads;
    public bool IsFilterDownloads => Filter == TransferFilter.Downloads;

    public string QueueSummary => $"{View.Count} 项";

    public bool IsEmpty => !IsLoading && View.Count == 0;

    /// <summary>进行中（含等待）的项数：侧栏「传输」徽标用，跨页面常驻。</summary>
    public int ActiveCount => _transfers.Items.Count(t => t.IsActive)
        + _transfers.Sessions.Count(s => s.IsActive)
        + _uploads.Uploads.Count(t => t.IsActive);

    public bool IsBusy => ActiveCount > 0;

    /// <summary>右上角总体进度只在有真实进度数据时出现；全 0 的空灰条看起来像加载没完成（task-101）。</summary>
    public bool HasOverallProgress => HasActive && Overall > 0.1;

    /// <summary>有进行中项但还没有任何字节进度时，显示「等待数据」而不是空进度条（task-101）。</summary>
    public bool ShowOverallWaiting => HasActive && !HasOverallProgress;

    public string EmptyTitle => Filter switch
    {
        TransferFilter.Devices => "没有设备传输任务",
        TransferFilter.Uploads => "没有上传会话",
        TransferFilter.Downloads => "没有下载会话",
        _ => "暂无传输任务"
    };

    public string EmptyHint => Filter switch
    {
        TransferFilter.Devices => "在「设备」里选一台在线设备即可开始发送",
        TransferFilter.Uploads => "在文件页上传文件后，会话会出现在这里",
        TransferFilter.Downloads => "在文件页下载文件后，会话会出现在这里",
        _ => "在设备页选一台在线设备发送文件；或在文件页上传 / 下载文件"
    };

    partial void OnFilterChanged(TransferFilter value)
    {
        Refresh();
        OnPropertyChanged(nameof(IsFilterAll));
        OnPropertyChanged(nameof(IsFilterDevices));
        OnPropertyChanged(nameof(IsFilterUploads));
        OnPropertyChanged(nameof(IsFilterDownloads));
        OnPropertyChanged(nameof(EmptyTitle));
        OnPropertyChanged(nameof(EmptyHint));
    }

    [RelayCommand]
    private void SetFilter(string? filter)
    {
        if (Enum.TryParse<TransferFilter>(filter, out var parsed)) Filter = parsed;
    }

    [RelayCommand]
    private async Task RefreshAsync()
    {
        await _transfers.RefreshAsync().ConfigureAwait(true);
        Refresh();
    }

    /// <summary>
    /// 重建传输中心的合并视图：服务端任务（设备传输）+ 本机下载会话 + 上传会话，
    /// 统一成 <see cref="TransferRow"/> 后按页签过滤。行对象复用缓存，刷新时容器不重复。
    /// </summary>
    public void Refresh()
    {
        // Changed 事件可能来自后台线程，集合必须在 UI 线程更新
        if (!Avalonia.Threading.Dispatcher.UIThread.CheckAccess())
        {
            Avalonia.Threading.Dispatcher.UIThread.Post(Refresh);
            return;
        }

        var live = new HashSet<string>(StringComparer.Ordinal);

        foreach (var job in _transfers.Items)
        {
            var key = JobKey(job);
            live.Add(key);
            if (!_rows.TryGetValue(key, out var row)) _rows[key] = row = TransferRow.ForJob(job);
        }

        foreach (var session in _transfers.Sessions)
        {
            var key = SessionKey(session);
            live.Add(key);
            if (!_rows.TryGetValue(key, out var row)) _rows[key] = row = TransferRow.ForSession(session);

            // task-4：下载会话首次出现时，文件页还停在它的云端来源目录（下载入口就在文件页），
            // 记下「空间 + 目录」快照，之后「定位」不会再随用户切目录而漂移。
            if (!_cloudTargets.ContainsKey(key))
            {
                var files = ShellFiles;
                _cloudTargets[key] = new TransferCloudTarget(
                    files?.SelectedSpace?.Id, files?.Path, session.FileName, session.LocalPath);
            }
        }

        foreach (var upload in _uploads.Uploads)
        {
            var key = UploadKey(upload);
            live.Add(key);

            if (!_uploadSessions.TryGetValue(key, out var session))
            {
                session = new TransferSession(
                    TransferSessionKind.Upload, key, upload.FileName, upload.ParentPath, upload.TotalBytes)
                {
                    LocalPath = upload.LocalPath
                };
                _uploadSessions[key] = session;

                // task-4：上传任务的云端位置 = 入队时文件页的当前空间 + ParentPath。
                _cloudTargets[key] = new TransferCloudTarget(
                    ShellFiles?.SelectedSpace?.Id, upload.ParentPath, upload.FileName, upload.LocalPath);
            }

            session.SetTotal(upload.TotalBytes);
            session.LocalPath = upload.LocalPath;
            session.ApplyUpload(upload);

            // task-14：上传器在后台线程推进 SentBytes/Status，只有订阅才能让传输中心实时显示进度与速度
            if (!_uploadSources.TryGetValue(key, out var previous) || !ReferenceEquals(previous, upload))
            {
                if (previous is not null) previous.PropertyChanged -= OnUploadProgressChanged;
                upload.PropertyChanged += OnUploadProgressChanged;
                _uploadSources[key] = upload;
            }

            if (!_rows.TryGetValue(key, out var row)) _rows[key] = row = TransferRow.ForSession(session);
        }

        // 数据源里已经消失的行：退订并清理缓存
        foreach (var stale in _rows.Keys.Where(k => !live.Contains(k)).ToList())
        {
            _rows[stale].Detach();
            _rows.Remove(stale);
        }

        foreach (var stale in _uploadSessions.Keys.Where(k => !live.Contains(k)).ToList())
        {
            _uploadSessions.Remove(stale);
            if (_uploadSources.Remove(stale, out var source)) source.PropertyChanged -= OnUploadProgressChanged;
        }

        foreach (var stale in _cloudTargets.Keys.Where(k => !live.Contains(k)).ToList())
            _cloudTargets.Remove(stale);

        var ordered = new List<TransferRow>(_rows.Count);
        foreach (var job in _transfers.Items) ordered.Add(_rows[JobKey(job)]);
        foreach (var session in _transfers.Sessions) ordered.Add(_rows[SessionKey(session)]);
        foreach (var upload in _uploads.Uploads) ordered.Add(_rows[UploadKey(upload)]);

        var filtered = Filter switch
        {
            TransferFilter.Devices => ordered.Where(r => r.IsJob),
            TransferFilter.Uploads => ordered.Where(r => r.Kind == TransferRowKind.Upload),
            TransferFilter.Downloads => ordered.Where(r => r.Kind == TransferRowKind.Download),
            _ => ordered
        };

        // 就地同步 ObservableCollection：不 Clear，避免 ItemsControl 保留旧容器导致行重复
        var index = 0;
        foreach (var row in filtered)
        {
            if (index < View.Count)
            {
                if (!ReferenceEquals(View[index], row)) View[index] = row;
            }
            else
            {
                View.Add(row);
            }

            index++;
        }

        while (View.Count > index) View.RemoveAt(View.Count - 1);

        // task-101：总体进度按字节加权，避免 130 MB 与 4 KB 的任务权重相同；无有效字节时退回行数平均
        var active = ordered.Where(r => r.IsActive).ToList();
        var activeBytes = active.Sum(r => Math.Max(r.Size, 0L));
        Overall = active.Count == 0
            ? 0
            : Math.Clamp(
                activeBytes > 0
                    ? active.Sum(r => r.Progress * Math.Max(r.Size, 0L)) / activeBytes
                    : active.Average(r => r.Progress), 0d, 1d) * 100;
        HasActive = active.Count > 0;
        OnPropertyChanged(nameof(HasOverallProgress));
        OnPropertyChanged(nameof(ShowOverallWaiting));
        IsLoading = _transfers.IsLoading;
        // task-151：页面级错误统一 Humanize（断网/5xx/401/403 全应用同一句中文）。
        // task-155 修 bug：Humanize(null, fallback) 在「没有错误」时返回 fallback，
        // 直接赋值会让成功刷新后仍显示一条假错误 → 无错误时必须清空。
        ErrorText = string.IsNullOrWhiteSpace(_transfers.LastError)
            ? string.Empty
            : LoadRetry.Humanize(_transfers.LastError, "传输列表加载失败，请稍后重试");
        // task-101 复核：「进行中」只统计真正在传输的行；「等待接收」（PENDING）与「已暂停」不算，
        // 否则卡片会把一个还没开始传的任务说成「进行中」，与行内状态徽标自相矛盾。
        RunningCount = ordered.Count(r => r.IsJob
            ? r.Job!.State == TransferState.Running
            : r.Session is { State: TransferSessionState.Running });
        DoneCount = ordered.Count(r => r.StateText == "已完成");
        FailedCount = ordered.Count(r => r.StateText is "失败" or "已取消" or "已过期" or "已拒绝");
        TotalTraffic = NodeItem.FormatSize(ordered.Sum(r => r.Size));

        OnPropertyChanged(nameof(QueueSummary));
        OnPropertyChanged(nameof(IsEmpty));
        OnPropertyChanged(nameof(EmptyTitle));
        OnPropertyChanged(nameof(EmptyHint));
        OnPropertyChanged(nameof(ActiveCount));
        OnPropertyChanged(nameof(IsBusy));
    }

    private static string JobKey(TransferItem job) => "job:" + job.Id;

    private static string SessionKey(TransferSession session) => "session:" + session.Id;

    private static string UploadKey(Bond.Models.UploadTaskItem upload) => "upload:" + upload.Key;

    /// <summary>上传任务在后台线程推进时，把进度/状态变化转成一次视图刷新（task-14）。</summary>
    private void OnUploadProgressChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (e.PropertyName is not (nameof(Bond.Models.UploadTaskItem.SentBytes)
            or nameof(Bond.Models.UploadTaskItem.Status)
            or nameof(Bond.Models.UploadTaskItem.SpeedBytesPerSecond)))
        {
            return;
        }

        if (!Avalonia.Threading.Dispatcher.UIThread.CheckAccess())
        {
            Avalonia.Threading.Dispatcher.UIThread.Post(Refresh);
            return;
        }

        Refresh();
    }

    [RelayCommand]
    private async Task CancelRowAsync(TransferRow? row)
    {
        if (row is null) return;

        if (row.Job is { } job)
        {
            var ok = await _transfers.CancelAsync(job).ConfigureAwait(true);
            if (!ok) _notify.Notify("取消失败，请稍后重试", NoticeKind.Warning);
            Refresh();
            return;
        }

        if (row.Session is not { } session) return;

        if (session.Kind == TransferSessionKind.Download)
        {
            _transfers.CancelSession(session);
            return;
        }

        if (_uploadSources.TryGetValue(session.Id, out var upload)) _uploads.Cancel(upload);
    }

    [RelayCommand]
    private async Task AcceptRowAsync(TransferRow? row)
    {
        if (row?.Job is not { } job) return;

        // task-94：接受走 POST /api/transfer/tasks/{id}/accept（PENDING → UPLOADING）。
        var ok = await _transfers.AcceptAsync(job).ConfigureAwait(true);
        if (!ok) _notify.Notify("接受失败，请稍后重试", NoticeKind.Warning);
        Refresh();
    }

    [RelayCommand]
    private async Task RejectRowAsync(TransferRow? row)
    {
        if (row?.Job is not { } job) return;

        // task-94：拒绝走 POST /api/transfer/tasks/{id}/reject（PENDING → REJECTED）。
        var ok = await _transfers.RejectAsync(job).ConfigureAwait(true);
        if (!ok) _notify.Notify("拒绝失败，请稍后重试", NoticeKind.Warning);
        Refresh();
    }

    [RelayCommand]
    private async Task RetryRowAsync(TransferRow? row)
    {
        if (row?.Job is not { } job) return;

        var ok = await _transfers.RetryAsync(job).ConfigureAwait(true);
        if (!ok) _notify.Notify("无法重试：本机源文件已不在，或网络异常", NoticeKind.Warning);
        Refresh();
    }

    [RelayCommand]
    private async Task ResumeRowAsync(TransferRow? row)
    {
        if (row?.Session is not { } session) return;
        if (session.Kind != TransferSessionKind.Upload) return;

        if (_uploadSources.TryGetValue(session.Id, out var upload))
            await _uploads.ResumeAsync(upload).ConfigureAwait(true);
    }

    /// <summary>
    /// 传输中心的「定位」：
    /// <list type="bullet">
    ///   <item><description>网盘上传 / 下载会话 → 切到文件页，打开对应的空间与云端目录并选中该文件；</description></item>
    ///   <item><description>设备传输任务（本机文件）→ 打开系统资源管理器定位；</description></item>
    ///   <item><description>拿不到任何位置 → 明确提示，不再「点了没反应」。</description></item>
    /// </list>
    /// </summary>
    [RelayCommand]
    private void OpenLocation(TransferRow? row)
    {
        if (row is null) return;

        if (row.Session is { } session)
        {
            if (TryGetCloudTarget(row, session, out var target) && RevealInFilesRequested is { } reveal)
            {
                reveal(target);
                return;
            }

            // 极端情况（老会话 / 文件页 ViewModel 尚未初始化）：退回本地文件所在目录。
            if (OpenInExplorer(session.LocalPath)) return;
            _notify.Notify("找不到该文件的位置（可能已被移动或删除）", NoticeKind.Warning);
            return;
        }

        if (OpenInExplorer(row.Job?.LocalPath)) return;
        _notify.Notify("本机源文件已不在，无法打开所在位置", NoticeKind.Warning);
    }

    /// <summary>取网盘会话的云端位置（优先入队/下载时的快照，缺失时按上传会话的目标目录兜底）。</summary>
    private bool TryGetCloudTarget(TransferRow row, TransferSession session, out TransferCloudTarget target)
    {
        if (_cloudTargets.TryGetValue(row.Id, out var snapshot))
        {
            target = snapshot;
            return true;
        }

        // 上传会话的 TargetLabel 就是入队时的云端目录（TransferSession 由 upload.ParentPath 构造）。
        if (session.Kind == TransferSessionKind.Upload)
        {
            target = new TransferCloudTarget(
                ShellFiles?.SelectedSpace?.Id,
                string.IsNullOrWhiteSpace(session.TargetLabel) ? "/" : session.TargetLabel,
                session.FileName,
                session.LocalPath);
            return true;
        }

        target = new TransferCloudTarget(null, "/", session.FileName, session.LocalPath);
        return false;
    }

    /// <summary>用资源管理器定位本地文件（设备任务 / 云端位置未知时的兜底）。返回是否真的打开了。</summary>
    private static bool OpenInExplorer(string? path)
    {
        if (string.IsNullOrEmpty(path)) return false;

        try
        {
            var full = System.IO.Path.GetFullPath(path);
            if (System.IO.File.Exists(full))
            {
                System.Diagnostics.Process.Start(
                    new System.Diagnostics.ProcessStartInfo("explorer.exe", $"/select,\"{full}\"")
                    {
                        UseShellExecute = true
                    });
                return true;
            }

            var directory = System.IO.Path.GetDirectoryName(full);
            if (!string.IsNullOrEmpty(directory) && System.IO.Directory.Exists(directory))
            {
                System.Diagnostics.Process.Start(
                    new System.Diagnostics.ProcessStartInfo("explorer.exe", directory)
                    {
                        UseShellExecute = true
                    });
                return true;
            }
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[Bond] 打开所在位置失败: {ex.Message}");
        }

        return false;
    }
}

/// <summary>
/// 网盘文件在客户端内的位置快照（task-4）：空间 + 云端目录 + 文件名。
/// <paramref name="SpaceId"/> 是文件页空间 id（<c>personal</c> 或工作区 id）；null = 沿用当前空间。
/// </summary>
public sealed record TransferCloudTarget(
    string? SpaceId,
    string? ParentPath,
    string? FileName,
    string? LocalPath);

public partial class DevicesPageViewModel : ObservableObject
{
    private readonly IDeviceService _devices;
    private readonly ITransferService _transfers;
    private readonly INotificationCenter _notify;
    private readonly Bond.Services.Lan.LanDiscoveryService _lan;

    public DevicesPageViewModel(
        IDeviceService devices,
        ITransferService transfers,
        INotificationCenter notify,
        Bond.Services.Lan.LanDiscoveryService lan)
    {
        _devices = devices;
        _transfers = transfers;
        _notify = notify;
        _lan = lan;

        _devices.Changed += (_, _) =>
        {
            IsScanning = _devices.IsScanning;
            // task-151：页面级错误统一 Humanize（断网/5xx/401/403 全应用同一句中文）。
            // task-155 修 bug：无错误时 Humanize(null, fallback) 会返回 fallback → 必须清空而不是赋值。
            ErrorText = string.IsNullOrWhiteSpace(_devices.LastError)
                ? string.Empty
                : LoadRetry.Humanize(_devices.LastError, "设备列表加载失败，请稍后重试");
            OnPropertyChanged(nameof(IsEmpty));
            OnPropertyChanged(nameof(OnlineCount));
        };

        if (_devices.Peers is System.Collections.Specialized.INotifyCollectionChanged incc)
            incc.CollectionChanged += (_, _) =>
            {
                OnPropertyChanged(nameof(IsEmpty));
                OnPropertyChanged(nameof(OnlineCount));
            };

        // task-160 A：局域网发现结果独立成区（服务端设备行仍是服务端行，不混排）。
        _lan.Changed += (_, _) => RebuildLanPeers();
        RebuildLanPeers();

        OnPropertyChanged(nameof(IsEmpty));
    }

    public ObservableCollection<DevicePeer> Peers => _devices.Peers;

    // ── task-160 A：局域网发现 ────────────────────────────────

    /// <summary>局域网里发现的同账号设备（UDP 组播，不经服务端）。</summary>
    public ObservableCollection<LanPeerItem> LanPeers { get; } = [];

    public bool HasLanPeers => LanPeers.Count > 0;

    /// <summary>发现服务是否在跑（设置里的「局域网发现」开关 + 登录态共同决定）。</summary>
    public bool IsLanEnabled => _lan.IsRunning;

    public string LanCountText => $"{LanPeers.Count} 台";

    public string LanStatusText
    {
        get
        {
            if (!LocalPrefs.Current.AllowLanDiscovery)
                return "局域网发现已关闭 · 可在 设置 → 传输与下载 打开";

            if (!_lan.IsRunning) return "未启动 · 登录后自动开始";

            return HasLanPeers
                ? "在线 · 实时心跳刷新，12 秒无心跳自动移除"
                : "扫描中 · 同一局域网、同一账号的设备会出现在这里";
        }
    }

    private void RebuildLanPeers()
    {
        var rows = _lan.Devices.Select(device => new LanPeerItem(device)).ToList();

        // 逐项同步而不是 Clear：Avalonia 12 收到 Reset 会残留旧容器（同 task-135 的教训）。
        Bond.Core.CollectionSync.Apply(LanPeers, rows, (a, b) => a.DeviceId == b.DeviceId);

        OnPropertyChanged(nameof(HasLanPeers));
        OnPropertyChanged(nameof(IsLanEnabled));
        OnPropertyChanged(nameof(LanCountText));
        OnPropertyChanged(nameof(LanStatusText));
    }

    /// <summary>task-43：设备列表加载失败时的提示（不再是静默空态）。</summary>
    [ObservableProperty]
    private string? _errorText;

    public bool HasError => !string.IsNullOrEmpty(ErrorText);

    partial void OnErrorTextChanged(string? value) => OnPropertyChanged(nameof(HasError));

    public bool IsEmpty => Peers.Count == 0;

    public int OnlineCount => Peers.Count(p => p.IsOnline);

    [ObservableProperty]
    private bool _isScanning;

    [RelayCommand]
    private async Task RefreshAsync()
    {
        IsScanning = true;

        // task-160 A：刷新同时立即广播一次，不等下一个 2s 周期（双机调试时点一下就互见）。
        _lan.AnnounceNow();

        await _devices.RefreshAsync().ConfigureAwait(true);
        IsScanning = false;
        ErrorText = _devices.LastError;
        RebuildLanPeers();
        OnPropertyChanged(nameof(IsEmpty));
        OnPropertyChanged(nameof(OnlineCount));
    }

    /// <summary>选真实文件后发送（此前用的是占位路径，功能等于不可用）。</summary>
    public async Task SendToAsync(DevicePeer? peer)
    {
        if (peer is null || !peer.IsOnline) return;

        var top = App.ShellTopLevel;
        if (top is null) return;

        var picked = await top.StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = $"发送到 {peer.Name}",
            AllowMultiple = true
        }).ConfigureAwait(true);

        var paths = picked.Where(f => f.TryGetLocalPath() is not null)
                          .Select(f => f.TryGetLocalPath()!)
                          .ToArray();

        if (paths.Length == 0) return;

        await _transfers.SendToAsync(peer, paths).ConfigureAwait(true);
        _notify.Notify($"正在发送 {paths.Length} 个文件到 {peer.Name}", NoticeKind.Success);
    }

    // ── task-35：移除离线设备（二次确认）──────────────────────

    [ObservableProperty]
    private DevicePeer? _pendingRemove;

    [ObservableProperty]
    private bool _isRemoveConfirmOpen;

    public string RemoveConfirmText => PendingRemove is null
        ? "确认移除这台设备？"
        : $"确认移除「{PendingRemove.Name}」？移除后对方下次登录仍会重新出现在列表里。";

    partial void OnPendingRemoveChanged(DevicePeer? value) =>
        OnPropertyChanged(nameof(RemoveConfirmText));

    /// <summary>行内「移除」：本机行只提示不可移除；离线行弹二次确认。</summary>
    public void RequestRemove(DevicePeer? peer)
    {
        if (peer is null) return;

        if (peer.IsCurrentDevice)
        {
            _notify.Notify("当前设备不能移除", NoticeKind.Warning);
            return;
        }

        PendingRemove = peer;
        IsRemoveConfirmOpen = true;
    }

    [RelayCommand]
    private void CancelRemove()
    {
        IsRemoveConfirmOpen = false;
        PendingRemove = null;
    }

    [RelayCommand]
    private async Task ConfirmRemoveAsync()
    {
        var peer = PendingRemove;
        IsRemoveConfirmOpen = false;
        PendingRemove = null;
        if (peer is null) return;

        var error = await _devices.RemoveAsync(peer).ConfigureAwait(true);
        if (error is null)
        {
            _notify.Notify($"已移除 {peer.Name}", NoticeKind.Success);
        }
        else
        {
            _notify.Notify(error, NoticeKind.Warning);
        }

        ErrorText = _devices.LastError;
        OnPropertyChanged(nameof(IsEmpty));
        OnPropertyChanged(nameof(OnlineCount));
    }
}

/// <summary>
/// task-160 A：局域网里发现的一台设备（设备页专用行）。
/// <para>
/// A 阶段只负责「发现 + 展示」，所以这里没有发送命令；B 阶段直连通道就绪后再挂发送。
/// </para>
/// </summary>
public sealed class LanPeerItem
{
    public LanPeerItem(Bond.Services.Lan.LanDevice device)
    {
        DeviceId = device.DeviceId;
        Name = device.DeviceName;
        Initial = device.Initial;
        Ip = device.Ip;
        Nickname = string.IsNullOrWhiteSpace(device.Nickname) ? "—" : device.Nickname;
        Meta = string.IsNullOrWhiteSpace(device.Nickname)
            ? $"局域网 · {device.Ip}"
            : $"{device.Nickname} · 局域网 {device.Ip}";
        LastSeenText = "局域网在线";
    }

    public string DeviceId { get; }

    public string Name { get; }

    public string Initial { get; }

    public string Ip { get; }

    public string Nickname { get; }

    public string Meta { get; }

    public string LastSeenText { get; }
}

/// <summary>消息区「滚到底」请求：<see cref="Force"/> 为 true 时无视当前滚动位置（自己发消息 / 切会话）。</summary>
public sealed class ChatScrollRequestedEventArgs(bool force) : EventArgs
{
    public bool Force { get; } = force;
}

/// <summary>
/// 会话页：真实聊天（REST + WebSocket）。
/// 数据与实时合并都在 <see cref="IChatService"/> 里做，这里只管三态、选中、发送与已读。
/// </summary>
public partial class ChatPageViewModel : ObservableObject
{
    /// <summary>首屏 / 向上翻页一次拉多少条（后端上限 100）。</summary>
    public const int MessagePageSize = 30;

    /// <summary>后端正文长度上限（chat.message_max_chars）。</summary>
    public const int MaxMessageLength = 4000;

    private readonly IChatService _chat;
    private readonly ISessionService _session;
    private readonly PreviewViewModel _preview;

    /// <summary>task-3：聊天附件下载能力（HttpBondStore 实现；Demo 数据源没有时界面明确提示）。</summary>
    private readonly IChatAttachmentDownloader? _attachmentDownloads;

    /// <summary>每个会话最近一次成功上报的 messageId，避免重复 POST /read。</summary>
    private readonly Dictionary<string, string> _readReported = new(StringComparer.Ordinal);

    /// <summary>task-165：每个「上传中的占位消息」一个取消令牌（可独立取消，互不影响）。</summary>
    private readonly Dictionary<ChatMessage, CancellationTokenSource> _uploadTokens = [];

    private bool _windowActive;
    private bool _pageVisible;
    private bool _loadingHistory;
    private bool _hasMoreHistory;
    private bool _autoSelecting;
    private int _conversationToken;
    private int _initializing;

    public ChatPageViewModel(IChatService chat, ISessionService session, PreviewViewModel preview)
    {
        _chat = chat;
        _session = session;
        _preview = preview;
        _attachmentDownloads = chat as IChatAttachmentDownloader;

        _chat.ChatChanged += OnChatChanged;
        _chat.ConnectionChanged += OnConnectionChanged;
        _chat.MessageReceived += OnMessageReceived;
        _chat.UnreadChanged += OnUnreadChanged;
        _session.Changed += OnSessionChanged;

        RebuildVisibleThreads();
        _ = InitializeAsync();
    }

    // ── 会话列表 ──────────────────────────────────────────────

    /// <summary>按搜索词过滤后的会话列表（绑定用）。</summary>
    public ObservableCollection<ChatThread> VisibleThreads { get; } = [];

    public bool IsChatAvailable => _chat.IsChatAvailable;

    /// <summary>未读总数（服务端聚合 GET /api/chat/unread；侧栏徽标与列表合计一致）。</summary>
    public int UnreadTotal => _chat.UnreadTotal;

    public bool HasUnreadTotal => UnreadTotal > 0;

    public string UnreadTotalText => UnreadTotal > 99 ? "99+" : UnreadTotal.ToString();

    public string ThreadCountText => $"{VisibleThreads.Count} 个会话";

    public bool ShowThreadSkeleton => IsThreadListLoading && VisibleThreads.Count == 0;

    public bool ShowThreadEmpty => !IsThreadListLoading && !HasThreadListError && VisibleThreads.Count == 0;

    [ObservableProperty]
    private string _searchText = string.Empty;

    [ObservableProperty]
    private bool _isThreadListLoading;

    [ObservableProperty]
    private bool _hasThreadListError;

    [ObservableProperty]
    private string _threadListError = string.Empty;

    // ── 当前会话 / 消息 ───────────────────────────────────────

    [ObservableProperty]
    private ChatThread? _selectedThread;

    [ObservableProperty]
    private ObservableCollection<ChatMessage> _messages = [];

    [ObservableProperty]
    private bool _isLoading;

    [ObservableProperty]
    private bool _hasError;

    [ObservableProperty]
    private string _errorText = string.Empty;

    public bool HasSelection => SelectedThread is not null;

    public bool ShowNoSelection => !HasSelection;

    /// <summary>task-166：会话副标题 = 会话类型 + 实时通道状态（后端无 presence，不伪造“在线”）。</summary>
    public string ThreadSubtitle
    {
        get
        {
            var kind = SelectedThread?.KindText ?? "选择会话";
            if (SelectedThread is null) return kind;
            var state = _chat.ConnectionState switch
            {
                ChatConnectionState.Connected => "实时连接",
                ChatConnectionState.Connecting => "正在连接…",
                ChatConnectionState.Polling => "连接中断 · 轮询中",
                _ => "离线"
            };
            return $"{kind} · {state}";
        }
    }

    /// <summary>
    /// 对方真实头像：私聊取会话 DTO 的 peerAvatarUrl（ChatThread.AvatarUrl）。
    /// 工作区频道的消息来自多人，DTO 没有 senderAvatarUrl，这里返回 null → 界面回退首字母占位。
    /// </summary>
    public string? PeerAvatarUrl => SelectedThread is { IsChannel: false } ? SelectedThread.AvatarUrl : null;

    /// <summary>自己的真实头像（ISessionService 已解码好的位图；未设置头像时为 null → 回退首字母）。</summary>
    public object? MyAvatarSource => _session.AvatarSource;

    /// <summary>自己的名字：消息头像没有 URL 时取首字母。</summary>
    public string MyAvatarName => string.IsNullOrWhiteSpace(_session.AccountName) ? "我" : _session.AccountName;

    /// <summary>task-166：气泡最大宽度（消息区宽度 × 62%），由 ChatPage 在尺寸变化时写入。</summary>
    [ObservableProperty]
    private double _bubbleMaxWidth = 520;

    public bool ShowSkeleton => IsLoading && Messages.Count == 0;

    public bool ShowEmptyMessages => HasSelection && !IsLoading && !HasError && Messages.Count == 0;

    public bool HasMoreHistory => _hasMoreHistory && Messages.Count > 0;

    // ── 会话详情栏（task-166：三栏骨架的右栏） ──────────────────

    [ObservableProperty]
    private bool _showDetails;

    public string DetailsTitle => SelectedThread?.Title ?? "会话详情";

    public string DetailsSubtitle => SelectedThread switch
    {
        null => "",
        { IsChannel: true } => "工作区频道",
        _ => "私聊"
    };

    /// <summary>本会话已加载的图片 / 文件（详情栏「文件」列表）。</summary>
    public ObservableCollection<ChatMessage> FilesInConversation { get; } = [];

    public bool ShowDetailsFilesEmpty => FilesInConversation.Count == 0;

    public string DetailsFilesCountText => FilesInConversation.Count == 0
        ? "暂无文件"
        : $"{FilesInConversation.Count} 个文件";

    [RelayCommand]
    private void ToggleDetails() => ShowDetails = !ShowDetails;

    [RelayCommand]
    private void CloseDetails() => ShowDetails = false;

    [ObservableProperty]
    private bool _isLoadingHistory;

    // ── 输入区 ────────────────────────────────────────────────

    [ObservableProperty]
    private string _draft = string.Empty;

    public int MaxLength => MaxMessageLength;

    public bool CanSend =>
        HasSelection && !IsSending && !string.IsNullOrWhiteSpace(Draft) && Draft.Length <= MaxMessageLength;

    public bool IsSendTooLong => Draft.Length > MaxMessageLength;

    /// <summary>
    /// task-151：实时通道断开时输入框**保持可发**（REST 兜底）。
    /// <para>理由：发消息走 <c>POST /api/chat/conversations/{id}/messages</c>，与 WS 通道完全独立；
    /// WS 只负责「即时到达」。断开时 REST 仍能成功入库（服务端幂等、clientMsgId 可重试），
    /// 禁用输入只会让弱网用户白丢一条消息；因此这里只给一条状态提示，不禁用输入。</para>
    /// </summary>
    public bool ShowSendFallbackHint => HasSelection && !IsConnected;

    public string SendFallbackHint => IsOffline
        ? "实时通道未连接 · 消息仍可发送，恢复后自动补发/送达"
        : "实时通道重连中 · 消息仍可发送（走 REST 兜底）";

    [ObservableProperty]
    private bool _isSending;

    [ObservableProperty]
    private bool _hasSendError;

    [ObservableProperty]
    private string _sendError = string.Empty;

    // ── 好友选择器（发起会话） ────────────────────────────────

    public ObservableCollection<ChatPeer> Peers { get; } = [];

    [ObservableProperty]
    private bool _isPeerPickerOpen;

    [ObservableProperty]
    private bool _isPeerPickerLoading;

    [ObservableProperty]
    private bool _hasPeerPickerError;

    [ObservableProperty]
    private string _peerPickerError = string.Empty;

    // ── task-139：置顶 / 免打扰 / 会话内搜索 / 撤回 / 已读回执 / 附件 ──

    public bool IsPinned => SelectedThread?.Pinned == true;

    public bool IsMuted => SelectedThread?.Muted == true;

    public string PinText => IsPinned ? "取消置顶" : "置顶会话";

    public string MuteText => IsMuted ? "关闭免打扰" : "免打扰";

    [ObservableProperty]
    private bool _isSearchOpen;

    [ObservableProperty]
    private string _searchQuery = string.Empty;

    [ObservableProperty]
    private bool _isSearching;

    [ObservableProperty]
    private bool _hasSearchError;

    [ObservableProperty]
    private string _searchError = string.Empty;

    [ObservableProperty]
    private bool _isAttaching;

    /// <summary>会话内搜索结果（GET .../messages/search）。</summary>
    public ObservableCollection<ChatSearchResult> SearchResults { get; } = [];

    public bool ShowSearchPanel => IsSearchOpen && HasSelection;

    public bool ShowSearchEmpty => IsSearchOpen && HasSelection && !IsSearching && !HasSearchError
        && SearchQuery.Trim().Length > 0 && SearchResults.Count == 0;

    public string SearchCountText => $"命中 {SearchResults.Count} 条";

    public string AttachHint => IsAttaching ? "正在上传附件…" : string.Empty;

    /// <summary>需要在已加载的消息里定位某条搜索结果时触发（视图负责滚动与临时高亮）。</summary>
    public event EventHandler<ChatMessage>? ScrollToMessageRequested;

    partial void OnSearchQueryChanged(string value)
    {
        OnPropertyChanged(nameof(ShowSearchEmpty));
        OnPropertyChanged(nameof(SearchCountText));
    }

    partial void OnIsSearchOpenChanged(bool value) => OnPropertyChanged(nameof(ShowSearchPanel));

    partial void OnIsAttachingChanged(bool value) => OnPropertyChanged(nameof(AttachHint));

    private void NotifyThreadFlags()
    {
        OnPropertyChanged(nameof(IsPinned));
        OnPropertyChanged(nameof(IsMuted));
        OnPropertyChanged(nameof(PinText));
        OnPropertyChanged(nameof(MuteText));
    }

    private void NotifySearchState()
    {
        OnPropertyChanged(nameof(ShowSearchPanel));
        OnPropertyChanged(nameof(ShowSearchEmpty));
        OnPropertyChanged(nameof(SearchCountText));
    }

    // ── 连接状态细条 ──────────────────────────────────────────

    public bool IsConnected => _chat.ConnectionState == ChatConnectionState.Connected;

    public bool IsConnecting => _chat.ConnectionState == ChatConnectionState.Connecting;

    public bool IsPolling => _chat.ConnectionState == ChatConnectionState.Polling;

    public bool IsOffline => _chat.ConnectionState == ChatConnectionState.Offline;

    public string ConnectionText => _chat.ConnectionState switch
    {
        ChatConnectionState.Connected => "实时连接 · 新消息即时到达",
        ChatConnectionState.Connecting => "正在连接实时通道…",
        ChatConnectionState.Polling => "实时通道不可用 · 已降级为每 5 秒轮询",
        _ => "实时通道未连接 · 登录后自动恢复"
    };

    /// <summary>消息区滚动到底的请求（由视图处理真正的滚动与「贴近底部才跟随」判断）。</summary>
    public event EventHandler<ChatScrollRequestedEventArgs>? ScrollToEndRequested;

    partial void OnSearchTextChanged(string value) => RebuildVisibleThreads();

    partial void OnSelectedThreadChanged(ChatThread? value)
    {
        OnPropertyChanged(nameof(HasSelection));
        OnPropertyChanged(nameof(ShowNoSelection));
        OnPropertyChanged(nameof(ThreadSubtitle));
        OnPropertyChanged(nameof(PeerAvatarUrl));
        OnPropertyChanged(nameof(CanSend));
        OnPropertyChanged(nameof(ShowSendFallbackHint));
        OnPropertyChanged(nameof(DetailsTitle));
        OnPropertyChanged(nameof(DetailsSubtitle));
        NotifyThreadFlags();
        UpdateMessageStates();
    }

    partial void OnDraftChanged(string value)
    {
        OnPropertyChanged(nameof(CanSend));
        OnPropertyChanged(nameof(IsSendTooLong));
    }

    partial void OnIsSendingChanged(bool value) => OnPropertyChanged(nameof(CanSend));

    partial void OnIsLoadingChanged(bool value) => UpdateMessageStates();

    partial void OnMessagesChanged(ObservableCollection<ChatMessage> value) => UpdateMessageStates();

    private void UpdateMessageStates()
    {
        OnPropertyChanged(nameof(ShowSkeleton));
        OnPropertyChanged(nameof(ShowEmptyMessages));
        OnPropertyChanged(nameof(HasMoreHistory));
        OnPropertyChanged(nameof(HasSelection));
        OnPropertyChanged(nameof(ShowNoSelection));
        RefreshFilesInConversation();
    }

    /// <summary>刷新详情栏的「文件」列表（task-166）：只列本会话已加载的图片/文件消息。</summary>
    private void RefreshFilesInConversation()
    {
        var files = Messages
            .Where(message => message.HasFile && !message.IsRecalled && !message.IsDeleted)
            .Reverse()
            .ToList();

        CollectionSync.Apply(FilesInConversation, files);
        OnPropertyChanged(nameof(ShowDetailsFilesEmpty));
        OnPropertyChanged(nameof(DetailsFilesCountText));
    }

    private void RebuildVisibleThreads()
    {
        var filter = SearchText.Trim();
        var list = filter.Length == 0
            ? _chat.Threads.ToList()
            : _chat.Threads
                .Where(thread => thread.Title.Contains(filter, StringComparison.OrdinalIgnoreCase))
                .ToList();

        // task-135：会话列表（含搜索过滤）用逐项同步，避免 Reset 残留会话行。
        CollectionSync.Apply(VisibleThreads, list);

        OnPropertyChanged(nameof(ThreadCountText));
        OnPropertyChanged(nameof(UnreadTotal));
        OnPropertyChanged(nameof(HasUnreadTotal));
        OnPropertyChanged(nameof(UnreadTotalText));
        OnPropertyChanged(nameof(ShowThreadSkeleton));
        OnPropertyChanged(nameof(ShowThreadEmpty));
    }

    // ── 生命周期 / 事件 ───────────────────────────────────────

    private async Task InitializeAsync()
    {
        if (Interlocked.Exchange(ref _initializing, 1) == 1) return;
        try
        {
            OnPropertyChanged(nameof(IsChatAvailable));
            if (!_chat.IsChatAvailable)
            {
                IsLoading = false;
                IsThreadListLoading = false;
                return;
            }

            if (VisibleThreads.Count == 0)
            {
                IsThreadListLoading = true;
                try
                {
                    await _chat.StartChatAsync().ConfigureAwait(true);
                }
                catch (Exception ex)
                {
                    HasThreadListError = true;
                    ThreadListError = LoadRetry.Humanize(ex.Message, "会话加载失败，请稍后重试");
                }
                finally
                {
                    IsThreadListLoading = false;
                }
            }

            if (VisibleThreads.Count == 0 && !string.IsNullOrEmpty(_chat.ChatError))
            {
                HasThreadListError = true;
                ThreadListError = LoadRetry.Humanize(_chat.ChatError, "会话加载失败，请稍后重试");
            }

            if (SelectedThread is null && VisibleThreads.Count > 0)
            {
                await SelectThreadAsync(VisibleThreads[0]).ConfigureAwait(true);
            }

            IsLoading = false;
            UpdateMessageStates();
        }
        finally
        {
            Interlocked.Exchange(ref _initializing, 0);
        }
    }

    private void OnChatChanged(object? sender, EventArgs e)
    {
        RebuildVisibleThreads();
        OnPropertyChanged(nameof(IsChatAvailable));

        // 断线轮询/重连补拉回来的新消息不走 MessageReceived；只要页面可见、窗口在前台、
        // 当前会话确实有未读，也必须把已读补上报（否则角标会一直挂着）。
        if (_windowActive && _pageVisible && SelectedThread is { Unread: > 0 })
        {
            _ = ReportReadAsync();
        }

        if (VisibleThreads.Count > 0 && HasThreadListError)
        {
            HasThreadListError = false;
            ThreadListError = string.Empty;
        }
        else if (VisibleThreads.Count == 0 && !string.IsNullOrEmpty(_chat.ChatError))
        {
            HasThreadListError = true;
            ThreadListError = _chat.ChatError!;
        }

        if (!_autoSelecting && SelectedThread is null && VisibleThreads.Count > 0 && _chat.IsChatAvailable)
        {
            _ = AutoSelectFirstAsync();
        }
    }

    private async Task AutoSelectFirstAsync()
    {
        if (_autoSelecting || SelectedThread is not null || VisibleThreads.Count == 0) return;
        _autoSelecting = true;
        try
        {
            await SelectThreadAsync(VisibleThreads[0]).ConfigureAwait(true);
        }
        finally
        {
            _autoSelecting = false;
        }
    }

    private void OnConnectionChanged(object? sender, EventArgs e)
    {
        OnPropertyChanged(nameof(IsConnected));
        OnPropertyChanged(nameof(IsConnecting));
        OnPropertyChanged(nameof(IsPolling));
        OnPropertyChanged(nameof(IsOffline));
        OnPropertyChanged(nameof(ConnectionText));
        OnPropertyChanged(nameof(ShowSendFallbackHint));
        OnPropertyChanged(nameof(SendFallbackHint));
    }

    private void OnMessageReceived(object? sender, ChatMessage message)
    {
        if (SelectedThread is null ||
            !string.Equals(message.ConversationId, SelectedThread.Id, StringComparison.Ordinal))
        {
            return;
        }

        UpdateMessageStates();
        if (message.IsMine)
        {
            RequestScrollToEnd(force: true);
        }
        else
        {
            RequestScrollToEnd(force: false);
            _ = ReportReadAsync();
        }
    }

    private void OnSessionChanged(object? sender, EventArgs e)
    {
        OnPropertyChanged(nameof(IsChatAvailable));
        OnPropertyChanged(nameof(IsConnected));
        OnPropertyChanged(nameof(IsConnecting));
        OnPropertyChanged(nameof(IsPolling));
        OnPropertyChanged(nameof(IsOffline));
        OnPropertyChanged(nameof(ConnectionText));
        OnPropertyChanged(nameof(ShowSendFallbackHint));
        OnPropertyChanged(nameof(SendFallbackHint));
        OnPropertyChanged(nameof(MyAvatarSource));
        OnPropertyChanged(nameof(MyAvatarName));

        if (_session.IsSignedIn)
        {
            _ = InitializeAsync();
            return;
        }

        // 登出：清掉当前会话，避免把上一个账号的消息留在界面上。
        SelectedThread = null;
        Messages = [];
        _readReported.Clear();
        _hasMoreHistory = false;
        HasError = false;
        ErrorText = string.Empty;
        IsLoading = false;
        UpdateMessageStates();
    }

    /// <summary>窗口激活状态：只有「可见且聚焦」时才上报已读（设计文档 §6.4）。</summary>
    public void SetWindowActive(bool active)
    {
        _windowActive = active;
        ApplyViewingState();
    }

    /// <summary>
    /// 会话页是否真的显示在屏幕上（侧栏切到其它页面时 IsVisible=false）。
    /// 只有「页面可见 + 窗口聚焦」才算用户正在看，否则新消息必须累计未读。
    /// </summary>
    public void SetPageVisible(bool visible)
    {
        if (_pageVisible == visible) return;
        _pageVisible = visible;
        ApplyViewingState();
    }

    private void ApplyViewingState()
    {
        var viewing = _windowActive && _pageVisible;
        Bond.Services.HttpBondStore.ChatDiag($"viewing={viewing} windowActive={_windowActive} pageVisible={_pageVisible} selected={SelectedThread?.Id}");
        _chat.ActiveConversationId = viewing ? SelectedThread?.Id : null;
        if (viewing)
        {
            _ = ReportReadAsync();
            // task-139：切回会话页/窗口重新聚焦时用服务端聚合校准一次未读（不逐会话轮询）。
            _ = _chat.RefreshUnreadAsync();
        }
    }

    // ── 选中会话 / 加载消息 ───────────────────────────────────

    [RelayCommand]
    private async Task SelectThreadAsync(ChatThread? thread)
    {
        if (thread is null) return;

        foreach (var item in _chat.Threads)
        {
            if (!ReferenceEquals(item, thread) && item.IsSelected) item.IsSelected = false;
        }

        thread.IsSelected = true;
        SelectedThread = thread;
        await OpenThreadAsync(thread).ConfigureAwait(true);
    }

    private async Task OpenThreadAsync(ChatThread thread)
    {
        var token = ++_conversationToken;
        var unreadBefore = thread.Unread;
        _chat.ActiveConversationId = (_windowActive && _pageVisible) ? thread.Id : null;
        Messages = _chat.MessagesFor(thread.Id);
        thread.Unread = 0;
        _readReported.Remove(thread.Id);
        _hasMoreHistory = false;
        HasError = false;
        ErrorText = string.Empty;
        HasSendError = false;
        SendError = string.Empty;
        IsLoading = Messages.Count == 0;
        UpdateMessageStates();

        try
        {
            var page = await _chat.LoadMessagesAsync(thread.Id, limit: MessagePageSize).ConfigureAwait(true);
            if (token != _conversationToken) return;

            if (!string.IsNullOrEmpty(page.Error))
            {
                // task-151：加载失败文案统一走 Humanize（不再原样抛「请求失败（HTTP 500）。」）。
                HasError = true;
                ErrorText = LoadRetry.Humanize(page.Error, "消息加载失败，请稍后重试");
            }
            else
            {
                _hasMoreHistory = page.HasMore;
                // task-166：未读分割线（首屏加载完成后按「进入前的未读数」标注）。
                _chat.MarkUnreadDivider(thread.Id, unreadBefore);
            }
        }
        catch (Exception ex)
        {
            if (token != _conversationToken) return;
            HasError = true;
            ErrorText = LoadRetry.Humanize(ex.Message, "消息加载失败，请稍后重试");
        }
        finally
        {
            if (token == _conversationToken)
            {
                IsLoading = false;
                UpdateMessageStates();
            }
        }

        if (token != _conversationToken) return;
        RequestScrollToEnd(force: true);
        await ReportReadAsync().ConfigureAwait(true);
        _ = RefreshReadStateAsync();
    }

    /// <summary>向上翻页（滚动到顶部时由视图调用）。返回本次新增的条数，视图据此保持滚动位置。</summary>
    public async Task<int> LoadHistoryAsync()
    {
        if (_loadingHistory || !_hasMoreHistory) return 0;
        var thread = SelectedThread;
        if (thread is null) return 0;

        var firstId = Messages.FirstOrDefault(message => !string.IsNullOrEmpty(message.Id))?.Id;
        if (string.IsNullOrEmpty(firstId)) return 0;

        _loadingHistory = true;
        IsLoadingHistory = true;
        try
        {
            var page = await _chat.LoadMessagesAsync(thread.Id, beforeId: firstId, limit: MessagePageSize)
                .ConfigureAwait(true);
            if (!string.IsNullOrEmpty(page.Error))
            {
                SendError = LoadRetry.Humanize(page.Error, "历史消息加载失败，请稍后重试");
                HasSendError = true;
                return 0;
            }

            _hasMoreHistory = page.HasMore;
            return page.Messages.Count;
        }
        catch (Exception ex)
        {
            SendError = LoadRetry.Humanize(ex.Message, "历史消息加载失败，请稍后重试");
            HasSendError = true;
            return 0;
        }
        finally
        {
            _loadingHistory = false;
            IsLoadingHistory = false;
            UpdateMessageStates();
        }
    }

    [RelayCommand]
    private Task LoadMoreAsync() => LoadHistoryAsync();

    [RelayCommand]
    private Task RetryLoadAsync() => RefreshThreadAsync();

    private async Task RefreshThreadAsync()
    {
        var thread = SelectedThread;
        if (thread is null)
        {
            HasError = false;
            await InitializeAsync().ConfigureAwait(true);
            return;
        }

        HasError = false;
        ErrorText = string.Empty;
        await OpenThreadAsync(thread).ConfigureAwait(true);
    }

    [RelayCommand]
    private async Task RefreshAsync()
    {
        HasThreadListError = false;
        ThreadListError = string.Empty;
        IsThreadListLoading = true;
        try
        {
            await _chat.StartChatAsync().ConfigureAwait(true);
            await _chat.RefreshConversationsAsync().ConfigureAwait(true);
        }
        catch (Exception ex)
        {
            HasThreadListError = true;
            ThreadListError = LoadRetry.Humanize(ex.Message, "会话加载失败，请稍后重试");
        }
        finally
        {
            IsThreadListLoading = false;
            RebuildVisibleThreads();
        }

        if (SelectedThread is null && VisibleThreads.Count > 0)
        {
            await SelectThreadAsync(VisibleThreads[0]).ConfigureAwait(true);
        }
    }

    // ── 发送 / 重试 ───────────────────────────────────────────

    [RelayCommand]
    private async Task SendAsync()
    {
        if (!CanSend) return;

        var thread = SelectedThread;
        if (thread is null) return;

        var text = Draft.Trim();
        if (text.Length == 0) return;
        if (text.Length > MaxMessageLength)
        {
            HasSendError = true;
            SendError = $"消息过长（最多 {MaxMessageLength} 字）";
            return;
        }

        var pending = _chat.AddPendingMessage(thread.Id, text, Guid.NewGuid().ToString("N"));
        Draft = string.Empty;
        HasSendError = false;
        SendError = string.Empty;
        _chat.ActiveConversationId = (_windowActive && _pageVisible) ? thread.Id : null;
        RequestScrollToEnd(force: true);

        IsSending = true;
        try
        {
            var result = await _chat.SendMessageAsync(pending).ConfigureAwait(true);
            if (!result.Success)
            {
                // task-151：失败文案统一走 LoadRetry.Humanize（断网/超时/5xx/401/403/409/413/429 → 全应用同一句中文）。
                HasSendError = true;
                SendError = LoadRetry.Humanize(result.Error, "发送失败，请稍后重试");
            }
        }
        catch (Exception ex)
        {
            HasSendError = true;
            SendError = LoadRetry.Humanize(ex.Message, "发送失败，请稍后重试");
        }
        finally
        {
            IsSending = false;
            UpdateMessageStates();
        }
    }

    [RelayCommand]
    private async Task RetryMessageAsync(ChatMessage? message)
    {
        if (message is null || message.SendState != ChatSendState.Failed) return;

        HasSendError = false;
        SendError = string.Empty;
        _chat.ActiveConversationId = (_windowActive && _pageVisible) ? message.ConversationId : null;

        // task-165：附件在上传阶段失败时本地文件还在，重试要重走「上传 → 发送」完整链路
        // （复用同一 clientMsgId，服务端幂等去重），而不是只重发一条没有 fileId 的消息。
        if (message.HasLocalFile)
        {
            message.SendState = ChatSendState.Sending;
            message.IsUploading = true;
            message.UploadProgress = 0;
            await UploadAndSendAttachmentAsync(message.ConversationId, message, message.LocalPath)
                .ConfigureAwait(true);
            return;
        }

        var result = await _chat.SendMessageAsync(message).ConfigureAwait(true);
        if (!result.Success)
        {
            HasSendError = true;
            // task-151：重试复用同一 clientMsgId（服务端幂等），失败文案同样统一。
            SendError = LoadRetry.Humanize(result.Error, "重试失败，请稍后重试");
        }

        RequestScrollToEnd(force: true);
        UpdateMessageStates();
    }

    /// <summary>点击图片消息打开既有预览覆盖层（MainWindow 承载），预览面板里可另存。</summary>
    [RelayCommand]
    private void OpenAttachment(ChatMessage? message)
    {
        if (message?.FileNode is not { } node) return;

        // 复用 MainWindow 里的预览覆盖层；聊天文件按 messageId 签发短时令牌，收发双方同一路径。
        // 取流失败（撤回/移出会话/删除统一 404）由预览层显示中性错误，不在这里假装成功。
        _ = _preview.OpenAsync(node);
    }

    /// <summary>
    /// task-3：点击文件消息 → 下载到「设置 → 传输与下载」的下载目录（QQ/微信默认行为）。
    /// <para>气泡下方显示真实字节进度；成功后写出完整保存路径并弹通知，失败显示原因、点击可重试。</para>
    /// <para>对方发来的文件走 /api/chat/files/{messageId}/tokens（旧路径 /api/files/{fileId}/download 必 404）。</para>
    /// </summary>
    [RelayCommand]
    private async Task DownloadAttachmentAsync(ChatMessage? message)
    {
        if (message is null || !message.CanDownload) return;

        if (_attachmentDownloads is null)
        {
            message.DownloadError = "当前数据源不支持下载";
            return;
        }

        message.IsDownloading = true;
        message.DownloadError = string.Empty;
        message.DownloadSavedPath = string.Empty;
        message.DownloadProgress = 0;

        try
        {
            var directory = LocalPrefs.Current.DownloadDirectory;
            var progress = new Progress<double>(value => message.DownloadProgress = value);
            var result = await _attachmentDownloads
                .DownloadChatAttachmentAsync(message, directory, progress)
                .ConfigureAwait(true);

            if (string.IsNullOrEmpty(result.Path))
            {
                message.DownloadError = string.IsNullOrWhiteSpace(result.Error)
                    ? "下载失败，请稍后重试"
                    : result.Error!;
                NotifyChat($"下载失败：{message.FileName}（{message.DownloadError}）", NoticeKind.Error);
            }
            else
            {
                message.DownloadSavedPath = result.Path!;
                NotifyChat($"已下载到 {result.Path}", NoticeKind.Success);
            }
        }
        catch (Exception ex)
        {
            message.DownloadError = LoadRetry.Humanize(ex.Message, "下载失败，请稍后重试");
            NotifyChat($"下载失败：{message.FileName}", NoticeKind.Error);
        }
        finally
        {
            message.IsDownloading = false;
        }
    }

    /// <summary>
    /// 聊天页构造签名不在本任务改动范围（AppServices.cs），按既有做法从组合根取通知中心；
    /// 取不到时只靠气泡内提示，不影响下载本身。
    /// </summary>
    private static void NotifyChat(string text, NoticeKind kind = NoticeKind.Info)
    {
        if (App.Services?.GetService(typeof(INotificationCenter)) is INotificationCenter notify)
        {
            notify.Notify(text, kind);
        }
    }

    // ── 已读上报 ──────────────────────────────────────────────

    private async Task ReportReadAsync()
    {
        if (!_windowActive || !_pageVisible) return;

        var thread = SelectedThread;
        if (thread is null) return;

        ChatMessage? latest = null;
        foreach (var message in Messages)
        {
            if (!string.IsNullOrEmpty(message.Id) && message.SendState == ChatSendState.Sent) latest = message;
        }

        if (latest is null) return;
        if (_readReported.TryGetValue(thread.Id, out var reported) &&
            string.Equals(reported, latest.Id, StringComparison.Ordinal))
        {
            return;
        }

        var ok = await _chat.MarkReadAsync(thread.Id, latest.Id).ConfigureAwait(true);
        if (!ok) return;

        _readReported[thread.Id] = latest.Id;
        thread.Unread = 0;
    }

    private void RequestScrollToEnd(bool force) =>
        ScrollToEndRequested?.Invoke(this, new ChatScrollRequestedEventArgs(force));

    // ── task-139：置顶 / 免打扰 / 搜索 / 撤回 / 已读回执 / 附件 ──

    private void OnUnreadChanged(object? sender, EventArgs e)
    {
        // task-139：未读总数来自服务端聚合，不再由本地逐会话累加。
        OnPropertyChanged(nameof(UnreadTotal));
        OnPropertyChanged(nameof(HasUnreadTotal));
        OnPropertyChanged(nameof(UnreadTotalText));
    }

    /// <summary>置顶 / 取消置顶（每人最多 20 个，超限由服务端返回 400）。</summary>
    [RelayCommand]
    private async Task TogglePinAsync()
    {
        var thread = SelectedThread;
        if (thread is null) return;

        var target = !thread.Pinned;
        if (!await _chat.SetPinnedAsync(thread.Id, target).ConfigureAwait(true))
        {
            HasSendError = true;
            SendError = LoadRetry.Humanize(_chat.ChatError, "置顶失败，请稍后重试");
            return;
        }

        NotifyThreadFlags();
    }

    /// <summary>免打扰开关（只影响未读徽标显示，不影响消息到达）。</summary>
    [RelayCommand]
    private async Task ToggleMuteAsync()
    {
        var thread = SelectedThread;
        if (thread is null) return;

        var target = !thread.Muted;
        if (!await _chat.SetMutedAsync(thread.Id, target).ConfigureAwait(true))
        {
            HasSendError = true;
            SendError = LoadRetry.Humanize(_chat.ChatError, "免打扰设置失败，请稍后重试");
            return;
        }

        NotifyThreadFlags();
    }

    [RelayCommand]
    private void ToggleSearch()
    {
        if (!HasSelection) return;

        IsSearchOpen = !IsSearchOpen;
        if (!IsSearchOpen) CloseSearch();
    }

    [RelayCommand]
    private void CloseSearch()
    {
        IsSearchOpen = false;
        SearchQuery = string.Empty;
        HasSearchError = false;
        SearchError = string.Empty;
        // SearchResults 是 UI 绑定的 ObservableCollection：直接 Clear() 会发 Reset，Avalonia 12 的列表会残留旧容器。
        CollectionSync.Apply(SearchResults, []);
        NotifySearchState();
    }

    /// <summary>会话内搜索（GET .../messages/search；前缀匹配、大小写不敏感、最多 20 条）。</summary>
    [RelayCommand]
    private async Task SearchAsync()
    {
        var thread = SelectedThread;
        if (thread is null) return;

        var keyword = SearchQuery.Trim();
        if (keyword.Length == 0)
        {
            // 同上：清空也要逐项通知，避免 Reset 残留。
            CollectionSync.Apply(SearchResults, []);
            NotifySearchState();
            return;
        }

        IsSearching = true;
        HasSearchError = false;
        SearchError = string.Empty;
        try
        {
            var results = await _chat.SearchMessagesAsync(thread.Id, keyword).ConfigureAwait(true);
            CollectionSync.Apply(SearchResults, results);
        }
        catch (Exception ex)
        {
            HasSearchError = true;
            SearchError = $"搜索失败：{ex.Message}";
        }
        finally
        {
            IsSearching = false;
            NotifySearchState();
        }
    }

    /// <summary>点击某条搜索结果：关闭搜索面板并在已加载消息中定位（未加载时给明确提示）。</summary>
    [RelayCommand]
    private void JumpToSearchResult(ChatSearchResult? result)
    {
        if (result is null) return;

        CloseSearch();

        var target = Messages.FirstOrDefault(message =>
            !string.IsNullOrEmpty(message.Id) && string.Equals(message.Id, result.Id, StringComparison.Ordinal));
        if (target is null)
        {
            HasSendError = true;
            SendError = "这条结果不在当前已加载的消息里：先向上滚动加载更早的消息，再搜索。";
            return;
        }

        foreach (var message in Messages) message.Highlighted = false;
        target.Highlighted = true;
        _ = ClearHighlightAsync(target);
        ScrollToMessageRequested?.Invoke(this, target);
    }

    private static async Task ClearHighlightAsync(ChatMessage message)
    {
        await Task.Delay(2500).ConfigureAwait(true);
        if (message.Highlighted) message.Highlighted = false;
    }

    /// <summary>撤回自己的消息（本人 2 分钟内；服务端超时 400、非本人 403、已删除 404）。</summary>
    [RelayCommand]
    private async Task RecallAsync(ChatMessage? message)
    {
        if (message is null) return;

        HasSendError = false;
        SendError = string.Empty;
        var result = await _chat.RecallMessageAsync(message).ConfigureAwait(true);
        if (!result.Success)
        {
            HasSendError = true;
            SendError = result.Error ?? "撤回失败，请重试";
        }

        UpdateMessageStates();
    }

    /// <summary>
    /// task-165：QQ 式附件发送 —— 选中/拖入/粘贴的**瞬间**先插入本地占位消息（图片渲染本地字节），
    /// 再上传（真实进度）→ 上传成功后补 fileId → POST messages 替换为服务端消息。
    /// 多文件各自独立并发，互不阻塞；发送按钮/输入框不因上传而禁用。
    /// </summary>
    public Task AttachFilesAsync(IReadOnlyList<string> paths, bool asImage)
    {
        var thread = SelectedThread;
        if (thread is null || paths.Count == 0) return Task.CompletedTask;

        foreach (var path in paths)
        {
            if (string.IsNullOrWhiteSpace(path) || !System.IO.File.Exists(path)) continue;

            var fileName = System.IO.Path.GetFileName(path);
            var image = asImage || IsImageFile(fileName);
            var kind = image ? "image" : "file";
            var extension = System.IO.Path.GetExtension(path).TrimStart('.');
            var contentType = image ? ContentTypeForImage(extension) : null;

            long size;
            try { size = new System.IO.FileInfo(path).Length; }
            catch { size = 0; }

            // 1) 立即占位（灰色气泡 + 本地缩略图），此时还没有 fileId，绝不会先调 POST messages。
            var pending = _chat.AddPendingAttachmentMessage(
                thread.Id, path, kind, Guid.NewGuid().ToString("N"), size, contentType);

            HasSendError = false;
            SendError = string.Empty;
            _chat.ActiveConversationId = (_windowActive && _pageVisible) ? thread.Id : null;
            RequestScrollToEnd(force: true);

            // 2) 各文件独立上传 + 发送（并发；进度回调直接写回占位消息）。
            _ = UploadAndSendAttachmentAsync(thread.Id, pending, path);
        }

        return Task.CompletedTask;
    }

    /// <summary>兼容旧入口：单个文件。</summary>
    public Task AttachAsync(string localPath, bool asImage)
        => AttachFilesAsync([localPath], asImage);

    private async Task UploadAndSendAttachmentAsync(string conversationId, ChatMessage pending, string localPath)
    {
        var cts = new CancellationTokenSource();
        _uploadTokens[pending] = cts;
        UpdateAttachState();

        try
        {
            var progress = new Progress<double>(value =>
            {
                pending.UploadProgress = value;
            });

            // 3) 真正上传（分片/流式写入的真实字节进度）。工作区频道必须传到该工作区空间，
            //    否则服务端会以「文件不在该会话可访问的空间内」拒绝后续消息。
            var workspaceId = _chat.Threads.FirstOrDefault(t => t.Id == conversationId)?.WorkspaceId;
            var node = await _chat
                .UploadChatAttachmentAsync(localPath, workspaceId, progress, cts.Token)
                .ConfigureAwait(true);

            if (cts.IsCancellationRequested)
            {
                _chat.RemovePendingMessage(conversationId, pending);
                return;
            }

            if (node is null)
            {
                pending.IsUploading = false;
                pending.SendState = ChatSendState.Failed;
                HasSendError = true;
                SendError = LoadRetry.Humanize(_chat.ChatError, "附件上传失败，请稍后重试");
                return;
            }

            // 4) 上传成功：补 fileId（FileNode 从本地切换到服务端），再 POST messages。
            pending.FileId = node.Id;
            pending.FileName = node.Name;
            pending.FileSize = node.Size;
            pending.FileContentType = node.ContentType;
            pending.LocalPath = string.Empty;
            pending.IsUploading = false;
            pending.UploadProgress = 1;

            var result = await _chat.SendFileMessageAsync(pending, cts.Token).ConfigureAwait(true);
            if (!result.Success)
            {
                pending.SendState = ChatSendState.Failed;
                HasSendError = true;
                SendError = LoadRetry.Humanize(result.Error, "发送失败，请稍后重试");
            }
        }
        catch (OperationCanceledException)
        {
            _chat.RemovePendingMessage(conversationId, pending);
        }
        catch (Exception ex)
        {
            pending.IsUploading = false;
            pending.SendState = ChatSendState.Failed;
            HasSendError = true;
            SendError = LoadRetry.Humanize(ex.Message, "附件发送失败，请稍后重试");
        }
        finally
        {
            _uploadTokens.Remove(pending);
            UpdateAttachState();
            UpdateMessageStates();
            RequestScrollToEnd(force: true);
        }
    }

    /// <summary>取消某个正在上传的附件（气泡直接移除，不留空壳；已发送的消息走撤回）。</summary>
    [RelayCommand]
    private void CancelUpload(ChatMessage? message)
    {
        if (message is null) return;

        if (_uploadTokens.TryGetValue(message, out var cts))
        {
            cts.Cancel();
            _uploadTokens.Remove(message);
        }

        _chat.RemovePendingMessage(message.ConversationId, message);
        UpdateAttachState();
        UpdateMessageStates();
    }

    private static bool IsImageFile(string fileName)
    {
        var extension = System.IO.Path.GetExtension(fileName);
        return extension.ToLowerInvariant() is ".png" or ".jpg" or ".jpeg" or ".gif" or ".webp" or ".bmp";
    }

    private static string ContentTypeForImage(string extension) => extension.ToLowerInvariant() switch
    {
        "png" => "image/png",
        "jpg" or "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "bmp" => "image/bmp",
        _ => "image/png"
    };

    private void UpdateAttachState()
    {
        IsAttaching = _uploadTokens.Count > 0;
        OnPropertyChanged(nameof(AttachHint));
    }

    /// <summary>拖拽文件到会话区：蒙层显示/隐藏（由视图拖拽事件驱动）。</summary>
    [ObservableProperty]
    private bool _isDragOver;

    partial void OnIsDragOverChanged(bool value) => OnPropertyChanged(nameof(ShowDropOverlay));

    public bool ShowDropOverlay => IsDragOver;

    public void BeginDragOver() => IsDragOver = true;

    public void EndDragOver() => IsDragOver = false;

    /// <summary>拖入释放：逐个按「图片→image、其它→file」排队发送。</summary>
    public Task DropFilesAsync(IReadOnlyList<string> paths)
    {
        EndDragOver();
        return AttachFilesAsync(paths, asImage: false);
    }

    /// <summary>粘贴剪贴板图片：先落到临时文件，再走与选择文件完全相同的占位/上传/发送路径。</summary>
    public async Task PasteImageAsync(byte[] pngBytes)
    {
        if (pngBytes is not { Length: > 0 }) return;
        if (SelectedThread is null) return;

        var path = System.IO.Path.Combine(
            System.IO.Path.GetTempPath(),
            $"bond-paste-{DateTime.Now:yyyyMMdd-HHmmss-fff}.png");
        try
        {
            await System.IO.File.WriteAllBytesAsync(path, pngBytes).ConfigureAwait(true);
        }
        catch (Exception ex)
        {
            HasSendError = true;
            SendError = LoadRetry.Humanize(ex.Message, "粘贴图片失败，请稍后重试");
            return;
        }

        await AttachFilesAsync([path], asImage: true).ConfigureAwait(true);
    }

    /// <summary>拉取会话已读回执（对端 max(read) 之后决定自己哪条消息显示「已读」）。</summary>
    public async Task RefreshReadStateAsync()
    {
        var thread = SelectedThread;
        if (thread is null) return;

        await _chat.LoadReadStateAsync(thread.Id).ConfigureAwait(true);
        UpdateMessageStates();
    }

    // ── 发起会话（好友选择器） ────────────────────────────────

    [RelayCommand]
    private async Task OpenPeerPickerAsync()
    {
        IsPeerPickerOpen = true;
        await LoadPeersAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private void ClosePeerPicker() => IsPeerPickerOpen = false;

    private async Task LoadPeersAsync()
    {
        if (!_chat.IsChatAvailable)
        {
            HasPeerPickerError = true;
            PeerPickerError = "登录后才能发起会话";
            return;
        }

        IsPeerPickerLoading = true;
        HasPeerPickerError = false;
        PeerPickerError = string.Empty;
        try
        {
            var friends = await _chat.GetFriendsAsync().ConfigureAwait(true);
            // task-135：好友选择器列表同样逐项同步（Reset 会让候选好友翻倍）。
            CollectionSync.Apply(Peers, friends);

            if (Peers.Count == 0)
            {
                HasPeerPickerError = true;
                PeerPickerError = "还没有可以私聊的好友。先在「好友」里添加好友后再回来。";
            }
        }
        catch (Exception ex)
        {
            HasPeerPickerError = true;
            PeerPickerError = $"好友列表加载失败：{ex.Message}";
        }
        finally
        {
            IsPeerPickerLoading = false;
        }
    }

    [RelayCommand]
    private async Task StartChatAsync(ChatPeer? peer)
    {
        if (peer is null) return;

        IsPeerPickerLoading = true;
        HasPeerPickerError = false;
        PeerPickerError = string.Empty;
        try
        {
            var thread = await _chat.EnsureDirectConversationAsync(peer.UserId).ConfigureAwait(true);
            if (thread is null)
            {
                HasPeerPickerError = true;
                PeerPickerError = LoadRetry.Humanize(_chat.ChatError, "无法创建会话，请稍后重试");
                return;
            }

            IsPeerPickerOpen = false;
            RebuildVisibleThreads();
            var target = VisibleThreads.FirstOrDefault(item => item.Id == thread.Id)
                         ?? _chat.Threads.FirstOrDefault(item => item.Id == thread.Id)
                         ?? thread;
            await SelectThreadAsync(target).ConfigureAwait(true);
        }
        catch (Exception ex)
        {
            HasPeerPickerError = true;
            PeerPickerError = $"创建会话失败：{ex.Message}";
        }
        finally
        {
            IsPeerPickerLoading = false;
        }
    }
}

public partial class SettingsPageViewModel : ObservableObject
{
    private readonly ISessionService _session;
    private readonly INotificationCenter _notify;
    private readonly Bond.Services.Api.BondApiClient _api;
    private readonly Bond.Services.Lan.LanDiscoveryService _lan;
    private readonly LocalPrefs _prefs;

    public SettingsPageViewModel(
        ISessionService session,
        INotificationCenter notify,
        Bond.Services.Api.BondApiClient api,
        Bond.Services.Lan.LanDiscoveryService lan)
    {
        _session = session;
        _notify = notify;
        _api = api;
        _lan = lan;
        _prefs = LocalPrefs.Current;

        _deviceName = session.DeviceName;
        _serverUrl = api.BaseUrl;
        _downloadDirectory = _prefs.DownloadDirectory;
        _notifyOnTransfer = _prefs.NotifyOnTransfer;
        _startWithWindows = _prefs.StartWithWindows;
        _allowLanDiscovery = _prefs.AllowLanDiscovery;

        // 首屏时后端数据可能还没回来：登录信息刷新后同步账户区
        session.Changed += (_, _) =>
        {
            OnPropertyChanged(nameof(DeviceCode));
            OnPropertyChanged(nameof(AccountName));
            OnPropertyChanged(nameof(AccountInitial));
        };

        // 设置页常驻内存：登录态就绪后再拉「挂载网络硬盘」信息（未登录时等 session.Changed，避免 401/误判未开启）
        if (_session.IsSignedIn) _ = LoadDavSectionAsync();
        else _session.Changed += (_, _) => _ = LoadDavSectionAsync();
    }

    [ObservableProperty]
    private string _deviceName;

    [ObservableProperty]
    private bool _notifyOnTransfer;

    [ObservableProperty]
    private bool _startWithWindows;

    [ObservableProperty]
    private bool _allowLanDiscovery;

    /// <summary>暗色模式：切换即生效并写入本地。</summary>
    [ObservableProperty]
    private bool _isDarkTheme = App.IsDarkTheme;

    /// <summary>服务端地址：可查看、修改并重连。</summary>
    [ObservableProperty]
    private string _serverUrl;

    [ObservableProperty]
    private string _downloadDirectory;

    partial void OnIsDarkThemeChanged(bool value) => App.ApplyTheme(value);

    public string DeviceCode => _session.DeviceCode;

    public string AccountName => _session.AccountName;

    public string AccountInitial => string.IsNullOrEmpty(_session.AccountName)
        ? "?"
        : _session.AccountName[..1].ToUpperInvariant();

    public string ServerHint => $"当前连接：{_api.BaseUrl}";

    public string AboutText =>
        "Bond 2.0（重构分支）\n"
        + "客户端：Avalonia 12 / .NET 10 / SukiUI 7\n"
        + "服务端：BondServer（Spring Boot 微服务）\n"
        + "文件 / 工作区 / 传输 / 设备 / 聊天数据来自真实接口。\n"
        + "聊天走 REST + WebSocket 实时通道，断线会自动重连并补拉消息。";

    [RelayCommand]
    private async Task SaveAsync()
    {
        _session.DeviceName = string.IsNullOrWhiteSpace(DeviceName)
            ? Environment.MachineName
            : DeviceName.Trim();

        _prefs.DownloadDirectory = string.IsNullOrWhiteSpace(DownloadDirectory)
            ? LocalPrefs.DefaultDownloadDirectory
            : DownloadDirectory.Trim();
        _prefs.NotifyOnTransfer = NotifyOnTransfer;
        _prefs.StartWithWindows = StartWithWindows;
        _prefs.AllowLanDiscovery = AllowLanDiscovery;
        _prefs.Save();

        // task-160 A：局域网发现开关立即生效（开→启动组播监听，关→停止并从所有列表消失）。
        if (!_prefs.AllowLanDiscovery)
        {
            _lan.Stop();
        }
        else if (_api.IsLoggedIn && !_lan.IsRunning)
        {
            _lan.Start();
        }

        if (OperatingSystem.IsWindows()) ApplyStartWithWindows(StartWithWindows);

        await Task.Delay(60).ConfigureAwait(true);
        _notify.Notify("设置已保存", NoticeKind.Success);
    }

    [RelayCommand]
    private async Task ReconnectAsync()
    {
        var url = (ServerUrl ?? string.Empty).Trim();
        if (!_api.ReconfigureBaseUrl(url))
        {
            _notify.Notify(
                string.Equals(url.TrimEnd('/'), _api.BaseUrl, StringComparison.OrdinalIgnoreCase)
                    ? "服务器地址未变化"
                    : "地址无效：需要以 http:// 或 https:// 开头的完整地址",
                NoticeKind.Warning);
            return;
        }

        _prefs.ServerUrl = _api.BaseUrl;
        _prefs.Save();

        // 旧 token 属于旧服务器，切换后必须重新登录。
        _api.ClearTokens();
        ServerUrl = _api.BaseUrl;
        OnPropertyChanged(nameof(ServerHint));
        _notify.Notify($"已切换到 {_api.BaseUrl}，请重新登录", NoticeKind.Success);
        (App.Current as App)?.ReturnToLogin();
    }

    [RelayCommand]
    private async Task BrowseDownloadFolderAsync()
    {
        var top = App.ShellTopLevel;
        if (top is null) return;

        var folders = await top.StorageProvider.OpenFolderPickerAsync(new FolderPickerOpenOptions
        {
            Title = "选择下载目录",
            AllowMultiple = false
        }).ConfigureAwait(true);

        var path = folders.FirstOrDefault()?.TryGetLocalPath();
        if (string.IsNullOrEmpty(path)) return;

        DownloadDirectory = path;
        _prefs.DownloadDirectory = path;
        _prefs.Save();
        _notify.Notify("下载目录已更新", NoticeKind.Success);
    }

    [RelayCommand]
    private void OpenDownloadFolder()
    {
        try
        {
            System.IO.Directory.CreateDirectory(DownloadDirectory);
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = DownloadDirectory,
                UseShellExecute = true
            });
        }
        catch
        {
            _notify.Notify("无法打开下载目录", NoticeKind.Warning);
        }
    }

    /// <summary>退出登录：清 token 并回到登录页（界面按钮带二次确认）。</summary>
    [RelayCommand]
    private void SignOut()
    {
        // 账号隔离（task-67）：退出前落库并清空本账号上传队列，避免切换账号后串号。
        try
        {
            if (App.Services.GetService(typeof(HttpBondStore)) is HttpBondStore store)
            {
                store.FlushUploadsOnSignOut();
            }
        }
        catch
        {
            // 队列清理失败不应阻塞退出
        }

        _api.ClearTokens();
        _notify.Notify("已退出登录");
        (App.Current as App)?.ReturnToLogin();
    }

    [RelayCommand]
    private void OpenDataFolder()
    {
        var dir = System.IO.Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Bond");
        System.IO.Directory.CreateDirectory(dir);

        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = dir,
                UseShellExecute = true
            });
        }
        catch
        {
            _notify.Notify("无法打开目录", NoticeKind.Warning);
        }
    }

    /// <summary>开机自启：真正落到 HKCU\...\Run，而不只是界面开关。</summary>
    [System.Runtime.Versioning.SupportedOSPlatform("windows")]
    private void ApplyStartWithWindows(bool enabled)
    {
        try
        {
            using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Run", writable: true);
            if (key is null) return;

            const string name = "Bond";
            if (enabled)
            {
                var exe = Environment.ProcessPath;
                if (!string.IsNullOrEmpty(exe)) key.SetValue(name, $"\"{exe}\"");
            }
            else if (key.GetValue(name) is not null)
            {
                key.DeleteValue(name, throwOnMissingValue: false);
            }
        }
        catch
        {
            // 注册表不可写时不影响其余设置
        }
    }

    // ── 邮箱（双认证修改） ──────────────────────────────────────────────

    /// <summary>当前邮箱：进入设置页时从 /api/auth/me 读取。</summary>
    [ObservableProperty]
    private string _emailDisplay = "—";

    [ObservableProperty]
    private bool _isEmailChangeOpen;

    [ObservableProperty]
    private bool _isEmailConfirmStep;

    [ObservableProperty]
    private string _emailChangeHint = "";

    [ObservableProperty]
    private string _currentPassword = "";

    [ObservableProperty]
    private string _newEmail = "";

    [ObservableProperty]
    private string _emailChangeCode = "";

    private string _pendingNewEmail = "";

    public async Task RefreshEmailAsync()
    {
        var me = await _api.GetAsync<Bond.Services.Api.UserDto>("/api/auth/me");
        if (me?.IsOk == true && me.Data is not null)
        {
            EmailDisplay = string.IsNullOrWhiteSpace(me.Data.Email) ? "未设置" : me.Data.Email!;
        }
    }

    [RelayCommand]
    private void OpenEmailChange()
    {
        IsEmailChangeOpen = true;
        IsEmailConfirmStep = false;
        EmailChangeHint = "第 1 步：输入当前密码与新邮箱，验证码会发到新邮箱。";
        CurrentPassword = "";
        NewEmail = "";
        EmailChangeCode = "";
    }

    [RelayCommand]
    private void CancelEmailChange()
    {
        IsEmailChangeOpen = false;
        IsEmailConfirmStep = false;
        EmailChangeHint = "";
        CurrentPassword = "";
        NewEmail = "";
        EmailChangeCode = "";
    }

    [RelayCommand]
    private async Task RequestEmailChangeAsync()
    {
        var password = CurrentPassword;
        var newEmail = (NewEmail ?? "").Trim();

        if (string.IsNullOrWhiteSpace(password))
        {
            EmailChangeHint = "请输入当前密码";
            return;
        }

        if (!EmailLooksValid(newEmail))
        {
            EmailChangeHint = "请输入正确的新邮箱地址";
            return;
        }

        var res = await _api.PostAsync<Dictionary<string, object>>(
            "/api/auth/email/change/request", new { password, newEmail });

        if (res is null)
        {
            EmailChangeHint = "无法连接服务器，请检查网络后重试";
            return;
        }

        if (!res.IsOk)
        {
            EmailChangeHint = DescribeEmailChangeError(res);
            return;
        }

        _pendingNewEmail = newEmail;
        IsEmailConfirmStep = true;
        EmailChangeHint = "验证码已发送到新邮箱：" + newEmail;
    }

    [RelayCommand]
    private async Task ConfirmEmailChangeAsync()
    {
        var code = (EmailChangeCode ?? "").Trim();
        if (code.Length != 6 || code.Any(c => !char.IsDigit(c)))
        {
            EmailChangeHint = "请输入 6 位数字验证码";
            return;
        }

        var res = await _api.PostAsync<Bond.Services.Api.UserDto>(
            "/api/auth/email/change/confirm", new { code });

        if (res is null)
        {
            EmailChangeHint = "无法连接服务器，请检查网络后重试";
            return;
        }

        if (!res.IsOk)
        {
            EmailChangeHint = DescribeEmailChangeError(res);
            return;
        }

        EmailDisplay = _pendingNewEmail;
        IsEmailChangeOpen = false;
        IsEmailConfirmStep = false;
        EmailChangeHint = "";
        CurrentPassword = "";
        NewEmail = "";
        EmailChangeCode = "";
        _notify.Notify("邮箱已更新", NoticeKind.Success);
    }

    private static string DescribeEmailChangeError(Bond.Services.Api.ApiResult res)
        => res.Code switch
        {
            404 => "服务端尚未开放修改邮箱接口（后端邮箱体系未上线）。",
            409 => "该邮箱已被其他账号使用，请更换。",
            400 => "当前密码不正确或新邮箱无效。",
            429 => "操作太频繁，请稍后再试。",
            _ => string.IsNullOrWhiteSpace(res.Message) ? $"请求失败（HTTP {res.Code}）" : res.Message
        };

    private static bool EmailLooksValid(string? value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Length > 254) return false;
        try
        {
            var address = new System.Net.Mail.MailAddress(value.Trim());
            return string.Equals(address.Address, value.Trim(), StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }
}

/// <summary>
/// 本地偏好：下载目录、行为开关、服务器地址。
/// 落盘在 %LocalAppData%\Bond\prefs.json，重启后保持。
/// </summary>
public sealed class LocalPrefs
{
    private static readonly string Dir = System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Bond");

    private static readonly string FilePath = System.IO.Path.Combine(Dir, "prefs.json");

    private static readonly Lazy<LocalPrefs> Instance = new(Load);

    public static LocalPrefs Current => Instance.Value;

    public static string DefaultDownloadDirectory => System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");

    public string DownloadDirectory { get; set; } = DefaultDownloadDirectory;

    public bool NotifyOnTransfer { get; set; } = true;

    public bool StartWithWindows { get; set; }

    public bool AllowLanDiscovery { get; set; } = true;

    /// <summary>task-160：局域网发现的 UDP 端口（多实例调试时可在 prefs.json 里改）。</summary>
    public int LanDiscoveryPort { get; set; } = Bond.Services.Lan.LanDiscoveryService.DefaultDiscoveryPort;

    /// <summary>task-160：局域网直连传输端口（A 阶段随发现报文发布，B 阶段真正用于传文件）。</summary>
    public int LanTransferPort { get; set; } = Bond.Services.Lan.LanDiscoveryService.DefaultTransferPort;

    public string ServerUrl { get; set; } = Bond.Services.Api.BondApiClient.DefaultBaseUrl;

    /// <summary>文件页排序键（FileSort 枚举名）。</summary>
    public string FileSortBy { get; set; } = "NameAsc";

    /// <summary>文件页视图：list / tile。</summary>
    public string FilesViewMode { get; set; } = "list";

    /// <summary>侧边栏是否展开（折叠后只显示图标）。</summary>
    public bool SidebarExpanded { get; set; } = true;

    /// <summary>task-153：主窗口界面缩放，存 "auto|1|1.1|1.25|1.5"（默认跟随系统）。</summary>
    public string UiScale { get; set; } = "auto";

    /// <summary>
    /// 本机稳定设备指纹（首次运行生成的 GUID，写入后永不重建）。
    /// 服务端按 (user_id, device_uid) 建唯一行，所以同一台机器重启只会更新同一行。
    /// </summary>
    public string DeviceId { get; set; } = "";

    /// <summary>本机在服务端的设备行 id（首次注册后写入，用于心跳与「当前设备」判定）。</summary>
    public string ServerDeviceId { get; set; } = "";

    /// <summary>ServerDeviceId 属于哪个账号：换账号登录时需要为当前账号重新登记。</summary>
    public long ServerDeviceUserId { get; set; }

    /// <summary>
    /// task-141：检查更新的频率。never / launch / daily / weekly。
    /// 默认 never——是否检查由用户在设置里显式选择，应用不做静默检查。
    /// </summary>
    public string UpdateFrequency { get; set; } = "never";

    /// <summary>task-141：查到新版本后是否自动下载安装包。默认关闭。</summary>
    public bool UpdateAutoDownload { get; set; }

    /// <summary>task-141：更新通道。当前只实现 stable，beta 仅预留。</summary>
    public string UpdateChannel { get; set; } = "stable";

    /// <summary>task-141：上次检查更新时间（ISO 8601，本地时间）。用于 daily / weekly 的到期判断。</summary>
    public string UpdateLastCheckedAt { get; set; } = "";

    /// <summary>task-141：用户选择「忽略此版本」的版本号；再次检查到同一版本时不再提示。</summary>
    public string IgnoredUpdateVersion { get; set; } = "";

    private static LocalPrefs Load()
    {
        LocalPrefs prefs = new();
        try
        {
            if (System.IO.File.Exists(FilePath))
            {
                var loaded = System.Text.Json.JsonSerializer
                    .Deserialize<LocalPrefs>(System.IO.File.ReadAllText(FilePath));
                if (loaded is not null) prefs = loaded;
            }
        }
        catch
        {
            // 文件损坏时退回默认值
        }

        // 稳定设备身份：只在首次运行时生成一次，之后加不重建（重建会让服务端多出一行设备）。
        if (string.IsNullOrWhiteSpace(prefs.DeviceId))
        {
            prefs.DeviceId = Guid.NewGuid().ToString("N");
            prefs.Save();
        }

        return prefs;
    }

    public void Save()
    {
        try
        {
            System.IO.Directory.CreateDirectory(Dir);
            System.IO.File.WriteAllText(FilePath, System.Text.Json.JsonSerializer.Serialize(
                this, new System.Text.Json.JsonSerializerOptions { WriteIndented = true }));
        }
        catch
        {
            // 落盘失败不打断交互
        }
    }
}

/// <summary>分享有效期选项。<see cref="Days"/> 为 null 表示永久。</summary>
public sealed record ShareExpiryOption(string Label, int? Days);
