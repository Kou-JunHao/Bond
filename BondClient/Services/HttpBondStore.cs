using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Bond.Core;
using Bond.Models;
using Bond.Services.Api;
using Bond.ViewModels;

namespace Bond.Services;

/// <summary>
/// 真实后端实现：把旧客户端验证过的接口映射到新 UI 的领域模型。
///
/// 覆盖：会话 / 设备 / 工作区 / 工作区文件（文件页）/ 云端传输任务 / 收件箱 / 聊天（REST + WebSocket，见 HttpBondStore.Chat.cs）。
/// </summary>
public sealed partial class HttpBondStore :
    IFileService,
    ITransferService,
    IDeviceService,
    IWorkspaceService,
    IChatService,
    IInboxService,
    ISessionService
{
    private readonly BondApiClient _api;

    private readonly ObservableCollection<NodeItem> _nodes = [];
    private readonly ObservableCollection<NodeItem> _recent = [];
    private readonly ObservableCollection<TransferItem> _transfers = [];
    private readonly ObservableCollection<DevicePeer> _peers = [];
    private readonly ObservableCollection<WorkspaceItem> _workspaces = [];
    // 会话列表整体替换引用（见 HttpBondStore.Chat.cs 的 ApplyConversations）：
    // 绝不对绑定集合逐项增删/移动，避免 Avalonia 弱事件列表在派发途中被改写而崩溃。
    private List<ChatThread> _threads = [];
    private readonly ObservableCollection<InboxItem> _inbox = [];

    private long _currentUserId;
    private Task<bool>? _bootstrapTask;
    private StorageQuota _quota = new() { UsedBytes = 0, TotalBytes = 5L * 1024 * 1024 * 1024 };
    private bool _isLoadingFiles;
    private bool _isLoadingTransfers;
    private string? _lastFilesError;

    // task-43：空间 / 设备 / 传输三个列表也单独记错误，避免把「请求失败」画成「什么都没有」。
    private bool _isLoadingSpaces;
    private string? _lastSpacesError;
    private string? _lastDevicesError;
    private string? _lastTransfersError;
    private int _spacesRetryRunning;
    private int _devicesRetryRunning;

    /// <summary>自动重试令牌：每次成功加载或重新排期都会递增，旧的等待循环看到令牌变化即退出。
    /// 用于「断网 → 出错 → 网络恢复后自动清掉错误提示」。
    /// </summary>
    private int _autoRetryToken;

    /// <summary>是否已有一个自动重试循环在跑，避免重复排期。</summary>
    private int _autoRetryRunning;

    public HttpBondStore(BondApiClient api)
    {
        _api = api;
        _api.AuthExpired += (_, _) =>
        {
            // 登出/掉登录态：聊天通道必须一起停，避免拿旧 token 无限重连。
            StopChat();
            AuthExpired?.Invoke(this, EventArgs.Empty);
        };

        // task-43：个人空间本地就存在，不依赖 /api/workspaces 成功；
        // 否则首次请求失败时文件页会误显示「还没有可用的空间」。
        _spaces.Add(new FileSpace
        {
            Id = PersonalSpaceId,
            Name = "个人空间",
            Kind = FileSpaceKind.Personal
        });
        _currentSpace = _spaces[0];
    }

    public event EventHandler? AuthExpired;

    /// <summary>登录成功后调用：拉取当前用户、设备、工作区与任务。</summary>
    public Task<bool> BootstrapAsync() => _bootstrapTask ??= BootstrapCoreAsync();

    private async Task<bool> BootstrapCoreAsync()
    {
        if (!_api.IsLoggedIn) return false;

        var me = await _api.GetAsync<UserDto>("/api/auth/me").ConfigureAwait(false);
        if (me?.Data is null) return false;

        _currentUserId = me.Data.Id;
        // 账号隔离（task-67）：拿到用户 id 后立刻过滤 uploads.json，别人的/旧格式的任务不出现在本账号。
        RefreshUploadOwnership();
        _accountName = string.IsNullOrEmpty(me.Data.Nickname) ? me.Data.Username : me.Data.Nickname;
        IsSignedIn = true;
        AvatarUrl = me.Data.AvatarUrl;
        await LoadAvatarAsync().ConfigureAwait(false);

        // task-35：先把本机登记成一台设备（已有登记则跳过），设备列表里才会有「当前设备」行
        await EnsureDevicePresenceAsync().ConfigureAwait(false);

        await RefreshWorkspacesAsync().ConfigureAwait(false);
        await RefreshDevicesAsync().ConfigureAwait(false);
        await RefreshTransfersAsync().ConfigureAwait(false);
        // task-14：有活跃任务时每 5s 静默刷新一次进度（失败不写错误条）
        StartTransferPolling();
        await RefreshInboxAsync().ConfigureAwait(false);
        await StartChatAsync().ConfigureAwait(false);

        Changed?.Invoke(this, EventArgs.Empty);
        return true;
    }

    // ── ISessionService ────────────────────────────────────────
    public string DeviceName { get; set; } = Environment.MachineName;

    private string? _accountName;

    /// <summary>
    /// 优先用 token 里的 username —— 换账号登录后界面立刻正确，
    /// 不再依赖启动时那一次 /api/auth/me 的结果。
    /// </summary>
    public string AccountName =>
        !string.IsNullOrEmpty(_accountName) ? _accountName! : (_api.Username ?? "未登录");

    public bool IsSignedIn { get; private set; }

    public string DeviceCode => _currentUserId > 0 ? $"BOND-{_currentUserId}" : "—";

    public string PresenceText => IsSignedIn ? "已连接云端" : "未登录";

    public PresenceState Presence => IsSignedIn ? PresenceState.Online : PresenceState.Offline;

    public BondApiClient Api => _api;

    public string? AvatarUrl { get; private set; }

    public object? AvatarSource { get; private set; }

    /// <summary>下载头像；失败时保持为空，由界面回退到首字母。</summary>
    private async Task LoadAvatarAsync()
    {
        if (string.IsNullOrWhiteSpace(AvatarUrl)) return;

        // task-140：记录代际号，账号切换（ResetSessionState）后晚到的旧头像直接丢弃，
        // 避免 B 登录后闪现 A 的头像。
        var generation = Volatile.Read(ref _sessionGeneration);

        var bytes = await _api.GetBytesAsync(AvatarUrl!).ConfigureAwait(false);
        if (bytes is null || bytes.Length == 0) return;
        if (generation != Volatile.Read(ref _sessionGeneration)) return;

        try
        {
            using var ms = new System.IO.MemoryStream(bytes);
            AvatarSource = new Avalonia.Media.Imaging.Bitmap(ms);
        }
        catch
        {
            AvatarSource = null;
        }
    }

    /// <summary>会话代际号：每次退出登录 +1，用于丢弃旧账号晚到的异步结果（头像等）。</summary>
    private int _sessionGeneration;

    /// <summary>
    /// task-140：退出登录时清空所有与账号绑定的会话缓存和列表。
    /// 只清 token 不清这里，下次登录复用同一个 store 单例时就会串号：
    /// 右上角头像/昵称还是上一个用户，列表也还是旧账号的数据（BootstrapAsync
    /// 的缓存任务会让 /api/auth/me 根本不重跑）。
    /// 必须在 UI 线程调用（ReturnToLogin 已保证）。
    /// </summary>
    public void ResetSessionState()
    {
        // 多次 AuthExpired 会重复触发退出流程；重入直接忽略，避免事件风暴。
        if (Interlocked.Exchange(ref _resetInProgress, 1) == 1) return;

        try
        {
            // 先停聊天通道：否则拿到新 token 后还会继续用旧账号的会话上下文。
            try { StopChat(); } catch { /* 停止失败不应阻塞退出登录 */ }

            _bootstrapTask = null;
            // 代际号 +1：上一个账号晚到的头像/请求结果一律作废。
            Interlocked.Increment(ref _sessionGeneration);
            _currentUserId = 0;
            _accountName = null;
            IsSignedIn = false;
            AvatarUrl = null;
            AvatarSource = null;
            _quota = new StorageQuota { UsedBytes = 0, TotalBytes = 5L * 1024 * 1024 * 1024 };
            _lastFilesError = null;
            _lastSpacesError = null;
            _lastDevicesError = null;
            _lastTransfersError = null;
            _isLoadingFiles = false;
            _isLoadingSpaces = false;

            Ui(() =>
            {
                CollectionSync.Apply(_nodes, Array.Empty<NodeItem>());
                CollectionSync.Apply(_recent, Array.Empty<NodeItem>());
                CollectionSync.Apply(_transfers, Array.Empty<TransferItem>());
                CollectionSync.Apply(_peers, Array.Empty<DevicePeer>());
                CollectionSync.Apply(_workspaces, Array.Empty<WorkspaceItem>());
                CollectionSync.Apply(_inbox, Array.Empty<InboxItem>());
                CollectionSync.Apply(_sessions, Array.Empty<TransferSession>());
                CollectionSync.Apply(_uploads, Array.Empty<UploadTaskItem>());

                // 空间列表回到构造时的初始状态：只留个人空间。
                var personal = new FileSpace
                {
                    Id = PersonalSpaceId,
                    Name = "个人空间",
                    Kind = FileSpaceKind.Personal
                };
                CollectionSync.Apply(_spaces, new[] { personal });
                _currentSpace = _spaces.Count > 0 ? _spaces[0] : null;
            });

            // 聊天：会话列表整体替换引用，各会话的消息列表逐项清空（不用 Clear，避免绑定集合 Reset）。
            ApplyConversations([]);
            foreach (var list in _chatMessages.Values)
            {
                CollectionSync.Apply(list, Array.Empty<ChatMessage>());
            }
            _chatMessages.Clear();

            // task-140：这里故意不触发 Changed 系列事件。
            // 设置页 / 工作区页的 session.Changed 订阅会立刻发起网络请求，
            // 而此刻 token 已被清空 → 401 → AuthExpired → 再次回到登录页，
            // 形成「退出登录」死循环。清空后的界面由下一次登录的 bootstrap 统一刷新。
        }
        finally
        {
            Interlocked.Exchange(ref _resetInProgress, 0);
        }
    }

    private int _resetInProgress;

    // ── IDeviceService ────────────────────────────────────────
    public ObservableCollection<DevicePeer> Peers => _peers;

    public bool IsScanning { get; private set; }

    public event EventHandler? Changed;

    public async Task RefreshAsync()
    {
        IsScanning = true;
        Changed?.Invoke(this, EventArgs.Empty);

        await RefreshDevicesAsync().ConfigureAwait(false);
        _isLoadingTransfers = true;
        try { await RefreshTransfersAsync().ConfigureAwait(false); }
        finally { _isLoadingTransfers = false; }

        IsScanning = false;
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private async Task RefreshDevicesAsync()
    {
        try
        {
            var result = await _api.GetAsync<List<DeviceDto>>("/api/devices").ConfigureAwait(false);
            if (result?.Data is null)
            {
                _lastDevicesError = LoadRetry.Humanize(_api.LastError, "无法加载设备列表，请检查网络后重试");
                ScheduleDevicesAutoRetry();
                Changed?.Invoke(this, EventArgs.Empty);
                return;
            }

            _lastDevicesError = null;

            // task-35：「当前设备」= 服务端行 id 与本机登记 id 一致；在线状态只认服务端 isOnline
            var serverDeviceId = LocalPrefs.Current.ServerDeviceId;

            var fresh = new List<DevicePeer>(result.Data.Count);
            foreach (var d in result.Data)
            {
                var id = d.Id.ToString();
                fresh.Add(new DevicePeer
                {
                    Id = id,
                    Name = string.IsNullOrEmpty(d.DeviceName) ? $"设备 {d.Id}" : d.DeviceName,
                    Ip = d.DeviceType switch
                    {
                        "Desktop" or "Windows" => "Windows",
                        _ => d.DeviceType
                    },
                    IsOnline = d.IsOnline,
                    Platform = string.IsNullOrEmpty(d.DeviceType) ? "Desktop" : d.DeviceType,
                    IsCurrentDevice = !string.IsNullOrWhiteSpace(serverDeviceId)
                                      && string.Equals(id, serverDeviceId, StringComparison.Ordinal),
                    LastSeenAt = d.LastSeenAt
                });
            }

            // 就地同步，不用 Clear+Add（Reset 会让 Avalonia 12 的 ItemsControl 留下旧容器）
            await SyncPeersAsync(fresh).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _lastDevicesError = LoadRetry.Humanize(ex.Message, "无法加载设备列表，请检查网络后重试");
            ScheduleDevicesAutoRetry();
            Changed?.Invoke(this, EventArgs.Empty);
        }
    }

    /// <summary>ITransferService.LastError / IDeviceService.LastError 分开暴露，避免互相污染。</summary>
    string? IDeviceService.LastError => _lastDevicesError;

    string? ITransferService.LastError => _lastTransfersError;

    /// <summary>设备列表下拉失败后的首次加载自动重试（task-43 口径：2s/4s/8s，最多 3 次）。</summary>
    private void ScheduleDevicesAutoRetry()
    {
        if (Interlocked.CompareExchange(ref _devicesRetryRunning, 1, 0) != 0) return;

        _ = Task.Run(async () =>
        {
            try
            {
                var error = await LoadRetry.FirstLoadAsync(async () =>
                {
                    var result = await _api.GetAsync<List<DeviceDto>>("/api/devices").ConfigureAwait(false);
                    return result?.Data is null
                        ? LoadRetry.Humanize(_api.LastError, "无法加载设备列表")
                        : null;
                }).ConfigureAwait(false);

                if (error is null) await RefreshDevicesAsync().ConfigureAwait(false);
            }
            finally
            {
                Interlocked.Exchange(ref _devicesRetryRunning, 0);
            }
        });
    }

    // ── IWorkspaceService ────────────────────────────────────
    public ObservableCollection<WorkspaceItem> Workspaces => _workspaces;

    public async Task CreateAsync(string name)
    {
        var created = await _api.PostAsync<WorkspaceDto>("/api/workspaces", new { name }).ConfigureAwait(false);
        if (created?.IsOk != true)
            throw new InvalidOperationException(created?.Message ?? _api.LastError ?? "创建工作区失败");

        await RefreshWorkspacesAsync().ConfigureAwait(false);
    }

    private int _workspacesRefreshInFlight;

    /// <summary>
    /// 已应用到 UI 的工作区签名。启动阶段页面构造、导航事件、自动重试会并发触发多次刷新；
    /// 若数据没变仍对 ObservableCollection 做 Clear/Add（Reset 事件），Avalonia 12 的
    /// WeakEvents 在监听器重新挂载时会偶发 "Stack empty" 崩溃（0xc0000409 / coreclr AV）。
    /// 因此同内容刷新直接跳过 UI 重建。
    /// </summary>
    private string? _appliedWorkspacesSignature;

    private async Task RefreshWorkspacesAsync()
    {
        // 页面可能在登录 Bootstrap 完成前就触发刷新；此时 _currentUserId 还是 0，
        // 会把 owner 误判成普通成员（Role=2，管理按钮变灰）。等 Bootstrap 完成后再刷，
        // 由 Bootstrap 自己的 RefreshWorkspacesAsync 负责填充列表。
        if (_currentUserId == 0 && _bootstrapTask is { } boot)
        {
            await boot.ConfigureAwait(false);
            return;
        }

        // 页面构造 / 导航激活 / 保存后刷新可能同时触发；并发 Clear+Add 会让同一工作区
        // 在列表里出现两次，所以这里串行化，并把集合变更统一抛回 UI 线程执行。
        if (System.Threading.Interlocked.CompareExchange(ref _workspacesRefreshInFlight, 1, 0) != 0)
            return;

        _isLoadingSpaces = true;
        Changed?.Invoke(this, EventArgs.Empty);

        try
        {
            var result = await _api.GetAsync<List<WorkspaceDto>>("/api/workspaces").ConfigureAwait(false);
            if (result?.Data is null)
            {
                _lastSpacesError = LoadRetry.Humanize(_api.LastError, "无法加载工作区列表，请检查网络后重试");
                ScheduleSpacesAutoRetry();
                return;
            }

            _lastSpacesError = null;

            var palette = new[] { "#E57153", "#4C7A5E", "#C08A3E", "#7A6BC4", "#4E79A7" };
            var items = new List<WorkspaceItem>();

            for (var i = 0; i < result.Data.Count; i++)
            {
                var w = result.Data[i];
                var isOwner = w.OwnerId == _currentUserId;
                items.Add(new WorkspaceItem
                {
                    Id = w.Id.ToString(),
                    Name = w.Name,
                    Description = w.Description ?? "",
                    OwnerId = w.OwnerId,
                    OwnerName = isOwner ? AccountName : $"用户 {w.OwnerId}",
                    IsOwner = isOwner,
                    Role = isOwner ? 0 : 2,
                    MemberCount = w.MemberCount > 0 ? w.MemberCount : (isOwner ? 1 : 0),
                    FileCount = w.FileCount,
                    UsedBytes = w.UsedSize,
                    QuotaBytes = w.MaxSize > 0 ? w.MaxSize : 5L * 1024 * 1024 * 1024,
                    AccentHex = palette[i % palette.Length]
                });
            }

            void Apply()
            {
                var sig = string.Join("|", items.Select(w =>
                    $"{w.Id},{w.Name},{w.IsOwner},{w.MemberCount},{w.FileCount},{w.UsedBytes},{w.QuotaBytes}"));

                var currentId = _currentSpace?.Id;
                if (sig != _appliedWorkspacesSignature)
                {
                    _appliedWorkspacesSignature = sig;
                    // task-135：不再 Clear+Add —— Clear 的 Reset 会让 Avalonia 12 的 ItemsControl 残留旧容器。
                    CollectionSync.Apply(_workspaces, items);
                    RebuildSpaces();

                    // 保持用户当前选择；失效则回到个人空间。
                    Changed?.Invoke(this, EventArgs.Empty);
                }

                _currentSpace = _spaces.FirstOrDefault(s => s.Id == currentId) ?? _spaces[0];
            }

            if (Avalonia.Threading.Dispatcher.UIThread.CheckAccess())
                Apply();
            else
                Avalonia.Threading.Dispatcher.UIThread.Post(Apply);

            await RefreshFilesAsync().ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _lastSpacesError = LoadRetry.Humanize(ex.Message, "无法加载工作区列表，请检查网络后重试");
            ScheduleSpacesAutoRetry();
        }
        finally
        {
            System.Threading.Interlocked.Exchange(ref _workspacesRefreshInFlight, 0);
            _isLoadingSpaces = false;
            Changed?.Invoke(this, EventArgs.Empty);
        }
    }

    /// <summary>IFileService：空间列表加载态 / 错误态（与文件列表错误分开，避免把失败画成「没有空间」）。</summary>
    public bool IsSpacesLoading => _isLoadingSpaces;

    public string? LastSpacesError => _lastSpacesError;

    public Task RefreshSpacesAsync() => RefreshWorkspacesAsync();

    // 工作区列表的加载态 / 错误态 / 刷新走显式实现，避免与文件页的 IsLoading/LastError 混淆。
    bool IWorkspaceService.IsLoading => _isLoadingSpaces;

    string? IWorkspaceService.LastError => _lastSpacesError;

    Task IWorkspaceService.RefreshAsync() => RefreshWorkspacesAsync();

    /// <summary>空间（工作区）列表失败后的首次加载自动重试（task-43 口径：2s/4s/8s，最多 3 次）。</summary>
    private void ScheduleSpacesAutoRetry()
    {
        if (Interlocked.CompareExchange(ref _spacesRetryRunning, 1, 0) != 0) return;

        _ = Task.Run(async () =>
        {
            try
            {
                var error = await LoadRetry.FirstLoadAsync(async () =>
                {
                    var result = await _api.GetAsync<List<WorkspaceDto>>("/api/workspaces").ConfigureAwait(false);
                    return result?.Data is null
                        ? LoadRetry.Humanize(_api.LastError, "无法加载工作区列表")
                        : null;
                }).ConfigureAwait(false);

                if (error is null) await RefreshWorkspacesAsync().ConfigureAwait(false);
            }
            finally
            {
                Interlocked.Exchange(ref _spacesRetryRunning, 0);
            }
        });
    }

    /// <summary>空间 = 个人空间（固定第一个）+ 我加入的团队工作区。</summary>
    private void RebuildSpaces()
    {
        // task-135：构建「新的一批」再交给 CollectionSync，避免 Clear() 的 Reset 残留容器
        // （空间切换下拉框、转存对话框都绑着这个集合）。
        var fresh = new List<FileSpace>
        {
            new()
            {
                Id = PersonalSpaceId,
                Name = "个人空间",
                Kind = FileSpaceKind.Personal
            }
        };

        foreach (var w in _workspaces)
        {
            fresh.Add(new FileSpace
            {
                Id = w.Id,
                Name = w.Name,
                Kind = FileSpaceKind.Workspace,
                WorkspaceId = w.Id,
                UsedBytes = w.UsedBytes,
                QuotaBytes = w.QuotaBytes
            });
        }

        CollectionSync.Apply(_spaces, fresh);
    }

    // ── IFileService（默认个人空间，可切团队工作区）──────────
    private const string PersonalSpaceId = "personal";

    /// <summary>
    /// task-127：服务端给「个人空间」也分配了 workspaceId，但空间列表里的个人空间 id 是固定的
    /// <see cref="PersonalSpaceId"/>，两者对不上。浏览个人空间时把真实 workspaceId 记下来，
    /// 搜索结果副行才能把「个人空间」和团队工作区区分开（不猜、不编）。
    /// </summary>
    private long _personalWorkspaceId;

    private readonly ObservableCollection<FileSpace> _spaces = [];
    private FileSpace? _currentSpace;

    public ObservableCollection<NodeItem> Nodes => _nodes;

    public ObservableCollection<NodeItem> Recent => _recent;

    public ObservableCollection<FileSpace> Spaces => _spaces;

    public FileSpace? CurrentSpace => _currentSpace;

    public bool IsPersonalSpace => _currentSpace?.IsPersonal ?? true;

    public StorageQuota Quota => _quota;

    public string CurrentPath { get; private set; } = "/";

    public bool IsLoading => _isLoadingFiles || _isLoadingTransfers;

    public string? LastError => _lastFilesError;

    public async Task SelectSpaceAsync(FileSpace space)
    {
        if (space is null || space.Id == _currentSpace?.Id) return;

        _currentSpace = _spaces.FirstOrDefault(s => s.Id == space.Id) ?? space;
        CurrentPath = "/";
        await RefreshFilesAsync().ConfigureAwait(false);
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void EnterFolder(NodeItem folder)
    {
        if (folder.Kind != NodeKind.Folder) return;
        CurrentPath = CurrentPath == "/" ? $"/{folder.Name}" : $"{CurrentPath}/{folder.Name}";
        _ = RefreshFilesAsync();
    }

    public void GoUp()
    {
        if (CurrentPath == "/") return;
        var idx = CurrentPath.LastIndexOf('/');
        CurrentPath = idx <= 0 ? "/" : CurrentPath[..idx];
        _ = RefreshFilesAsync();
    }

    public void NavigateTo(string path)
    {
        CurrentPath = string.IsNullOrWhiteSpace(path) || path == "/" ? "/" : path.TrimEnd('/');
        _ = RefreshFilesAsync();
    }

    /// <summary>个人空间走 /api/files，团队工作区仍走 /api/workspaces/{id}/files。</summary>
    private string FilesBase
    {
        get
        {
            if (IsPersonalSpace) return "/api/files";
            var workspaceId = _currentSpace?.WorkspaceId;
            return string.IsNullOrEmpty(workspaceId)
                ? "/api/files"
                : $"/api/workspaces/{workspaceId}/files";
        }
    }

    /// <summary>
    /// 文件列表查询 URL（task-158）：个人空间只认 path，团队工作区只认 parentPath。
    /// 传错参数时后端会静默忽略、返回根目录 —— 表现就是「进了子文件夹，列表还是根目录」。
    /// </summary>
    private static string FilesListUrl(string basePath, string path)
    {
        var queryName = basePath.StartsWith("/api/workspaces/", StringComparison.Ordinal) ? "parentPath" : "path";
        return $"{basePath}?{queryName}={Uri.EscapeDataString(path)}";
    }

    /// <summary>IFileService.ReloadListAsync：重新拉取当前目录（回收站恢复/删除后回刷）。</summary>
    public Task ReloadListAsync() => RefreshFilesAsync();

    /// <summary>转存目标选择器：列出指定空间（null=个人空间）某路径下的文件夹。</summary>
    public async Task<(IReadOnlyList<NodeItem>? Folders, string? Error)> ListFoldersAsync(FileSpace? space,
        string path, CancellationToken ct = default)
    {
        var basePath = space?.WorkspaceId is { Length: > 0 } workspaceId
            ? $"/api/workspaces/{workspaceId}/files"
            : "/api/files";
        var normalized = string.IsNullOrWhiteSpace(path) || path == "/" ? "/" : path.TrimEnd('/');

        var result = await _api
            .GetAsync<List<WorkspaceFileDto>>(FilesListUrl(basePath, normalized))
            .ConfigureAwait(false);
        if (result?.Data is null)
            return (null, result?.Message ?? _api.LastError ?? "无法加载目标目录，请重试");

        var folders = result.Data
            .Select(f => MapFile(f, space?.Id))
            .Where(n => n.IsFolder)
            .ToList();
        return (folders, null);
    }

    /// <summary>
    /// 转存（服务端复制）：source 在个人空间走 /api/files/{id}/copy，在工作区走
    /// /api/workspaces/{ws}/files/{id}/copy；失败返回后端原文，界面按 413/400/404/403 分派文案。
    /// </summary>
    public async Task<CopyResult> CopyAsync(NodeItem source, string? targetWorkspaceId, string targetParentPath,
        string? newName, CancellationToken ct = default)
    {
        if (!source.IsFile)
            return CopyResult.Failed(400, "仅支持单个文件转存（文件夹不能转存）");

        var sourceBase = string.IsNullOrEmpty(source.SpaceId) || source.SpaceId == "personal"
            ? "/api/files"
            : $"/api/workspaces/{source.SpaceId}/files";
        var parent = string.IsNullOrWhiteSpace(targetParentPath) || targetParentPath == "/"
            ? "/"
            : targetParentPath.TrimEnd('/');

        try
        {
            var result = await _api
                .CopyFileAsync(sourceBase, source.Id, targetWorkspaceId, parent, newName)
                .ConfigureAwait(false);
            if (result is null)
                return CopyResult.Failed(0, "无法连接服务器，请检查网络后重试");

            if (result.Code is 200 or 201)
                return CopyResult.Success(source.Size, result.Message ?? string.Empty);

            return CopyResult.Failed(result.Code, string.IsNullOrWhiteSpace(result.Message)
                ? $"转存失败（HTTP {result.Code}）"
                : result.Message);
        }
        catch (Exception ex)
        {
            return CopyResult.Failed(0, ex.Message);
        }
    }

    /// <summary>
    /// 移动（task-166）：与转存同一套源/目标解析，只是走 <c>/move</c>；同空间移动服务端只改
    /// parentPath，不复制对象、不消耗流量。失败原文回传（409 同名 / 404 目标目录不存在 / 400 非法路径）。
    /// </summary>
    public async Task<CopyResult> MoveAsync(NodeItem source, string? targetWorkspaceId, string targetParentPath,
        string? newName, CancellationToken ct = default)
    {
        var sourceBase = string.IsNullOrEmpty(source.SpaceId) || source.SpaceId == "personal"
            ? "/api/files"
            : $"/api/workspaces/{source.SpaceId}/files";
        var parent = string.IsNullOrWhiteSpace(targetParentPath) || targetParentPath == "/"
            ? "/"
            : targetParentPath.TrimEnd('/');

        try
        {
            var result = await _api
                .MoveFileAsync(sourceBase, source.Id, targetWorkspaceId, parent, newName)
                .ConfigureAwait(false);
            if (result is null)
                return CopyResult.Failed(0, "无法连接服务器，请检查网络后重试");

            if (result.Code is 200 or 201)
                return CopyResult.Success(source.Size, result.Message ?? string.Empty);

            return CopyResult.Failed(result.Code, string.IsNullOrWhiteSpace(result.Message)
                ? $"移动失败（HTTP {result.Code}）"
                : result.Message);
        }
        catch (Exception ex)
        {
            return CopyResult.Failed(0, ex.Message);
        }
    }

    private async Task RefreshFilesAsync()    {
        _isLoadingFiles = true;
        _lastFilesError = null;
        Changed?.Invoke(this, EventArgs.Empty);

        try
        {
            var result = await _api
                .GetAsync<List<WorkspaceFileDto>>(
                    FilesListUrl(FilesBase, CurrentPath))
                .ConfigureAwait(false);

            if (result?.Data is null)
            {
                // 业务拒绝（例如 ACL restricted 成员进入未授权目录返回 404「目录不存在」）时
                // ApiClient.LastError 为空，必须优先透传后端 message，否则界面只剩通用网络文案。
                _lastFilesError = result?.Message ?? _api.LastError ?? "无法加载文件列表，请检查网络后重试";
                // 断网时提示会一直挂在这儿：排一个后台重试，网络恢复后自动刷新并清掉提示。
                ScheduleAutoRetry();
                return;
            }

            // 加载成功：作废任何在途的自动重试，避免继续打扰
            Interlocked.Increment(ref _autoRetryToken);

            // task-127：个人空间的真实 workspaceId 从这里缓存下来（供搜索结果副行显示来源）
            if (IsPersonalSpace)
            {
                var personalWs = result.Data.Select(f => f.WorkspaceId).FirstOrDefault(id => id > 0);
                if (personalWs > 0) _personalWorkspaceId = personalWs;
            }

            // task-97：上面用了 ConfigureAwait(false)，续体可能在后台线程；
            // _nodes/_recent 绑定在文件页的 ItemsControl 上，必须在 UI 线程成批替换。
            var files = result.Data.Select(f => MapFile(f, _currentSpace?.Id)).ToList();
            var recent = result.Data.Take(6).Select(f => MapFile(f, _currentSpace?.Id)).ToList();
            Ui(() =>
            {
                // task-135：逐项同步，避免 Clear() 的 Reset 让文件列表/最近使用容器残留。
                CollectionSync.Apply(_nodes, files);
                CollectionSync.Apply(_recent, recent);
            });

            // 配额：个人空间走 /api/files/quota（真实 已用/配额）；团队沿用工作区配额
            if (IsPersonalSpace)
            {
                var quotaResult = await _api.GetAsync<StorageQuotaDto>("/api/files/quota").ConfigureAwait(false);
                var q = quotaResult?.Data;
                _quota = q is { MaxBytes: > 0 }
                    ? new StorageQuota { UsedBytes = q.UsedBytes, TotalBytes = q.MaxBytes }
                    : new StorageQuota { UsedBytes = files.Sum(n => n.Size), TotalBytes = 0 };
            }
            else
            {
                _quota = new StorageQuota
                {
                    UsedBytes = _currentSpace?.UsedBytes ?? 0,
                    TotalBytes = _currentSpace?.QuotaBytes ?? 0
                };
            }
        }
        finally
        {
            _isLoadingFiles = false;
            Changed?.Invoke(this, EventArgs.Empty);
        }
    }

    /// <summary>
    /// 文件列表加载失败后的后台自动重试（断网恢复自愈）。
    /// <para>退避 3s→5s→8s→10s→15s→20s→30s，最多 10 次；任意一次成功即停止并清除错误提示。
    /// 用户手动刷新或切空间会触发新的加载，成功后会通过令牌作废旧循环。</para>
    /// </summary>
    private void ScheduleAutoRetry()
    {
        // 已有循环在跑就不再叠加
        if (Interlocked.CompareExchange(ref _autoRetryRunning, 1, 0) != 0) return;

        var token = Volatile.Read(ref _autoRetryToken);
        _ = Task.Run(async () =>
        {
            var delaysMs = new[] { 3_000, 5_000, 8_000, 10_000, 15_000, 20_000, 30_000, 30_000, 30_000, 30_000 };
            try
            {
                foreach (var delay in delaysMs)
                {
                    await Task.Delay(delay).ConfigureAwait(false);

                    // 已被新的加载作废（或错误已被清除）就不再重试
                    if (token != Volatile.Read(ref _autoRetryToken) || _lastFilesError is null) return;

                    // 前后台正在拉取时不叠加请求
                    if (_isLoadingFiles) continue;

                    try
                    {
                        await RefreshFilesAsync().ConfigureAwait(false);
                    }
                    catch (Exception ex)
                    {
                        Console.Error.WriteLine($"[Bond] 自动重试失败: {ex.Message}");
                        continue;
                    }

                    if (_lastFilesError is null) return; // 成功，提示已清除
                }
            }
            finally
            {
                Interlocked.Exchange(ref _autoRetryRunning, 0);
            }
        });
    }

    private static NodeItem MapFile(WorkspaceFileDto f, string? spaceId) => new()
    {
        Id = f.Id.ToString(),
        Name = f.FileName,
        Kind = f.IsDirectory ? NodeKind.Folder : NodeKind.File,
        Size = f.FileSize,
        ContentHash = f.FileHash,
        ContentType = f.ContentType,
        ParentPath = f.ParentPath,
        SpaceId = string.IsNullOrEmpty(spaceId) ? "personal" : spaceId,
        ModifiedAt = f.CreatedAt is { } ms
            ? DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime
            : DateTime.Now
    };

    public async Task UploadAsync(IReadOnlyList<string> localPaths, CancellationToken ct = default)
    {
        foreach (var path in localPaths)
        {
            try
            {
                await using var stream = System.IO.File.OpenRead(path);
                using var content = new MultipartFormDataContent();
                content.Add(new StreamContent(stream), "file", System.IO.Path.GetFileName(path));
                content.Add(new StringContent(CurrentPath), "parentPath");

                await _api
                    .PostMultipartAsync<WorkspaceFileDto>($"{FilesBase}/upload", content)
                    .ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"[Bond] 上传失败 {path}: {ex.Message}");
            }
        }

        await RefreshFilesAsync().ConfigureAwait(false);
    }

    /// <summary>单文件上传（悬浮球拖拽）：带真实进度回调；上传到个人空间根目录。</summary>
    public async Task<bool> UploadQuickAsync(string localPath, IProgress<double>? progress = null,
        CancellationToken ct = default)
    {
        if (!System.IO.File.Exists(localPath)) return false;

        try
        {
            await using var stream = System.IO.File.OpenRead(localPath);
            using var content = new MultipartFormDataContent();
            content.Add(new ProgressStreamContent(stream, stream.Length, progress, ct),
                "file", System.IO.Path.GetFileName(localPath));
            content.Add(new StringContent("/"), "parentPath");

            var result = await _api
                .PostMultipartAsync<WorkspaceFileDto>("/api/files/upload", content)
                .ConfigureAwait(false);

            if (result?.IsOk != true)
            {
                Console.Error.WriteLine($"[Bond] 快速上传失败 {localPath}: {result?.Message ?? _api.LastError}");
                return false;
            }

            await RefreshFilesAsync().ConfigureAwait(false);
            return true;
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[Bond] 快速上传失败 {localPath}: {ex.Message}");
            return false;
        }
    }

    /// <summary>计费权益；失败返回 null，界面隐藏额度行（不显示假数字）。</summary>
    public async Task<EntitlementsInfo?> GetEntitlementsAsync(CancellationToken ct = default)
    {
        var result = await _api.GetAsync<EntitlementsDto>("/api/entitlements/me").ConfigureAwait(false);
        if (result?.IsOk != true || result.Data is null) return null;

        var dto = result.Data;
        return new EntitlementsInfo
        {
            PlanName = string.IsNullOrWhiteSpace(dto.PlanName) ? null : dto.PlanName.Trim(),
            RelayBytesRemaining = dto.Usage?.RelayBytesRemaining ?? 0,
            StorageBytes = dto.Usage?.StorageBytes ?? Quota.UsedBytes,
            StorageLimitBytes = dto.Limits?.StorageBytes ?? Quota.TotalBytes,
            IsThrottled = dto.IsThrottled,
            TrashRetentionDays = dto.Limits?.TrashRetentionDays
        };
    }

    // ── 分享链接 ──

    public async Task<(ShareLink? Share, string? Error)> CreateShareAsync(NodeItem file, bool requiresCode,
        string? code, int? expiresDays, int maxDownloads = 0, CancellationToken ct = default)
    {
        try
        {
            // 服务端 fileId 是 Long：客户端 id 是字符串，能转数字就按数字发（Jackson 对字符串→Long 有版本差异）。
            object fileId = long.TryParse(file.Id, out var numericId) ? numericId : file.Id;
            var result = await _api
                .CreateShareAsync(fileId, requiresCode, code, expiresDays, maxDownloads)
                .ConfigureAwait(false);

            if (result?.IsOk != true || result.Data is null)
                return (null, result?.Message ?? _api.LastError ?? "创建分享链接失败");

            return (MapShare(result.Data, file.IsFolder), null);
        }
        catch (Exception ex)
        {
            return (null, ex.Message);
        }
    }

    public async Task<(IReadOnlyList<ShareLink>? Shares, string? Error)> GetSharesAsync(
        CancellationToken ct = default)
    {
        try
        {
            var result = await _api.GetSharesAsync().ConfigureAwait(false);
            if (result?.IsOk != true || result.Data is null)
                return (null, result?.Message ?? _api.LastError ?? "加载分享记录失败");

            return (result.Data.Select(dto => MapShare(dto)).ToList(), null);
        }
        catch (Exception ex)
        {
            return (null, ex.Message);
        }
    }

    public Task<string?> RevokeShareAsync(string shareId, CancellationToken ct = default)
        => _api.RevokeShareAsync(shareId);

    /// <summary>
    /// 编辑分享（PATCH /api/shares/{id}，task-163 契约）。
    /// 三态语义：<paramref name="changeExtractCode"/>=false 表示不传（保持现状）；
    /// 为 true 且 <paramref name="extractCode"/> 为 null/空 时显式发 null（取消提取码），否则替换。
    /// 有效期同理：<paramref name="changeExpiresAt"/> 为 false 不传，否则按绝对毫秒发送（null=永久）。
    /// </summary>
    public async Task<(ShareLink? Share, string? Error)> UpdateShareAsync(
        string shareId,
        bool changeExtractCode,
        string? extractCode,
        bool changeExpiresAt,
        long? expiresAtMillis,
        bool changeMaxDownloads,
        int? maxDownloads,
        CancellationToken ct = default)
    {
        var body = new Dictionary<string, object?>(StringComparer.Ordinal);
        if (changeExtractCode)
            body["extractCode"] = string.IsNullOrWhiteSpace(extractCode) ? null : extractCode.Trim();
        if (changeExpiresAt)
            body["expiresAtMillis"] = expiresAtMillis;
        if (changeMaxDownloads)
            body["maxDownloads"] = maxDownloads;

        if (body.Count == 0) return (null, "没有需要保存的改动");

        try
        {
            var result = await _api.PatchShareAsync(shareId, body).ConfigureAwait(false);
            if (result?.IsOk == true && result.Data is not null)
                return (MapShare(result.Data), null);

            return (null, DescribeShareUpdateError(_api.LastHttpStatus, result?.Message ?? _api.LastError));
        }
        catch (Exception ex)
        {
            return (null, ex.Message);
        }
    }

    /// <summary>把后端 message 原样带上，并按 403/404/410 补一句可读前缀（不替换后端文案）。</summary>
    private static string DescribeShareUpdateError(int status, string? message)
    {
        var detail = string.IsNullOrWhiteSpace(message) ? null : message.Trim();
        var prefix = status switch
        {
            403 => "无权编辑这条分享",
            404 => "分享不存在或已被删除",
            410 => "文件已删除或已移入回收站",
            _ => string.Empty
        };

        if (prefix.Length == 0)
            return detail ?? $"保存失败（HTTP {status}）";

        return detail is null || detail.Contains(prefix, StringComparison.Ordinal)
            ? prefix
            : $"{prefix}：{detail}";
    }

    private static ShareLink MapShare(ShareLinkDto dto, bool fallbackIsDirectory = false) => new()
    {
        // 服务端 id 是 Long，客户端模型用字符串保存。
        Id = dto.Id.ToString(),
        Token = dto.Token,
        Url = dto.Url,
        FileName = dto.FileName,
        FileSize = dto.FileSize,
        // task-183：优先取服务端 isDirectory；创建响应若未带该字段则用客户端已知的节点类型兜底。
        IsDirectory = dto.IsDirectory ?? fallbackIsDirectory,
        RequiresCode = dto.RequiresCode,
        Code = dto.Code,
        // task-163：时间以 *Millis 为准，ISO 字段仅兼容回退。
        ExpiresAt = dto.ExpiresAtMillis is { } expMs
            ? DateTimeOffset.FromUnixTimeMilliseconds(expMs)
            : dto.ExpiresAt,
        MaxDownloads = dto.MaxDownloads,
        DownloadCount = dto.DownloadCount,
        // state(active|expired|revoked) 优先；没有时退回数字 status（0 正常 / 1 已撤销 / 2 已过期）。
        Status = !string.IsNullOrWhiteSpace(dto.State)
            ? dto.State!
            : dto.Status switch { 1 => "revoked", 2 => "expired", _ => "active" },
        CreatedAt = dto.CreatedAtMillis is { } createdMs
            ? DateTimeOffset.FromUnixTimeMilliseconds(createdMs)
            : dto.CreatedAt,
        LastAccessAt = dto.LastAccessAtMillis is { } lastMs
            ? DateTimeOffset.FromUnixTimeMilliseconds(lastMs)
            : null
    };

    public async Task DeleteAsync(NodeItem node)
    {
        var result = await _api
            .DeleteAsync($"{FilesBase}/{node.Id}")
            .ConfigureAwait(false);

        if (result is null || result.Code != 200)
            throw new InvalidOperationException(result?.Message ?? _api.LastError ?? "删除失败");

        _nodes.Remove(node);
        _recent.Remove(node);
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public async Task RenameAsync(NodeItem node, string newName)
    {
        // 个人空间：POST /api/files/rename { fileId, newName }
        // 团队工作区：PUT /api/workspaces/{id}/files/{fileId} { newName }
        var result = IsPersonalSpace
            ? await _api
                .PostAsync<WorkspaceFileDto>("/api/files/rename", new { fileId = node.Id, newName })
                .ConfigureAwait(false)
            : await _api
                .PutAsync<WorkspaceFileDto>($"{FilesBase}/{node.Id}", new { newName })
                .ConfigureAwait(false);

        if (result?.IsOk != true)
            throw new InvalidOperationException(result?.Message ?? _api.LastError ?? "重命名失败");

        await RefreshFilesAsync().ConfigureAwait(false);
    }

    /// <summary>
    /// 新建文件夹（task-120）：个人空间 POST /api/files/folders，工作区
    /// POST /api/workspaces/{id}/files/folders；body { parentPath, name }。
    /// 错误（409 重名 / 400 非法名 / 404 父目录缺失 / 403 非成员）返回后端 message 原文。
    /// </summary>
    public async Task<(bool Ok, string? Error)> CreateFolderAsync(
        string parentPath, string name, CancellationToken ct = default)
    {
        _ = ct;
        var path = IsPersonalSpace ? "/api/files/folders" : $"{FilesBase}/folders";
        var result = await _api
            .PostAsync<WorkspaceFileDto>(path, new { parentPath, name })
            .ConfigureAwait(false);

        if (result?.IsOk != true)
            return (false, result?.Message ?? _api.LastError ?? "新建文件夹失败");

        await RefreshFilesAsync().ConfigureAwait(false);
        return (true, null);
    }

    /// <summary>
    /// 把云端文件下载到本地目录（下载按钮 / 行右键菜单）。
    /// task-14：下载过程登记为一条下载会话（传输中心可见），进度/速度来自真实读流字节。
    /// </summary>
    public async Task<string?> DownloadAsync(NodeItem node, string targetDirectory)
    {
        if (node.Kind == NodeKind.Folder) return null;

        string? target = null;
        TransferSession? session = null;
        CancellationTokenSource? cts = null;
        try
        {
            System.IO.Directory.CreateDirectory(targetDirectory);

            // task-3：聊天文件不属于任何浏览空间（node.SpaceId="chat:<messageId>"），
            // 旧路径 /api/files/{fileId}/download 对接收方必然 404；非聊天节点也改为按
            // 节点自己的 SpaceId 拼 URL（不再用当前浏览空间的 FilesBase）。
            // 解析细节见 HttpBondStore.Chat.cs 的 ResolveDownloadUrlAsync。
            var downloadUrl = await ResolveDownloadUrlAsync(node).ConfigureAwait(false);
            if (downloadUrl is null) return null;

            using var response = await _api
                .GetStreamAsync(downloadUrl)
                .ConfigureAwait(false);

            if (response is null) return null;

            var safeName = string.Join("_", node.Name.Split(
                System.IO.Path.GetInvalidFileNameChars(), StringSplitOptions.RemoveEmptyEntries));
            if (string.IsNullOrWhiteSpace(safeName)) safeName = "download";

            target = System.IO.Path.Combine(targetDirectory, safeName);
            var stem = System.IO.Path.GetFileNameWithoutExtension(safeName);
            var ext = System.IO.Path.GetExtension(safeName);
            for (var i = 1; System.IO.File.Exists(target); i++)
                target = System.IO.Path.Combine(targetDirectory, $"{stem} ({i}){ext}");

            var totalBytes = response.Content.Headers.ContentLength ?? node.Size;
            cts = new CancellationTokenSource();
            session = BeginDownloadSession(node.Name, DescribeDownloadTarget(targetDirectory), totalBytes, cts);

            await using var source = await response.Content.ReadAsStreamAsync(cts.Token).ConfigureAwait(false);
            await using var file = System.IO.File.Create(target);

            var buffer = new byte[256 * 1024];
            long received = 0;
            var reportAt = System.Diagnostics.Stopwatch.StartNew();
            int read;
            while ((read = await source.ReadAsync(buffer.AsMemory(), cts.Token).ConfigureAwait(false)) > 0)
            {
                await file.WriteAsync(buffer.AsMemory(0, read), cts.Token).ConfigureAwait(false);
                received += read;

                // 约 150ms 上报一次，既让进度/速度跟手，又不制造 PropertyChanged 风暴
                if (reportAt.ElapsedMilliseconds >= 150)
                {
                    ReportDownloadProgress(session, received);
                    reportAt.Restart();
                }
            }

            ReportDownloadProgress(session, received);
            FinishDownloadSession(session, target, null);
            return target;
        }
        catch (OperationCanceledException) when (cts?.IsCancellationRequested == true)
        {
            if (session is not null) FinishDownloadSession(session, null, null, canceled: true);
            TryDeletePartial(target);
            return null;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[Bond] 下载失败 {node.Name}: {ex.Message}");
            if (session is not null) FinishDownloadSession(session, null, ex.Message);
            TryDeletePartial(target);
            return null;
        }
        finally
        {
            cts?.Dispose();
        }
    }

    private static void TryDeletePartial(string? path)
    {
        if (string.IsNullOrEmpty(path)) return;
        try
        {
            if (System.IO.File.Exists(path)) System.IO.File.Delete(path);
        }
        catch
        {
            // 清理失败不影响调用方
        }
    }

    private static string DescribeDownloadTarget(string directory)
    {
        try
        {
            var name = System.IO.Path.GetFileName(directory.TrimEnd('\\', '/'));
            return string.IsNullOrWhiteSpace(name) ? "本机文件夹" : $"本机 · {name}";
        }
        catch
        {
            return "本机文件夹";
        }
    }

    // ── ITransferService ─────────────────────────────────────
    public ObservableCollection<TransferItem> Items => _transfers;

    public bool HasActive => _transfers.Any(t => t.IsActive);

    public double OverallProgress
    {
        get
        {
            var active = _transfers.Where(t => t.IsActive).ToList();
            return active.Count == 0 ? 0 : active.Average(t => t.Progress);
        }
    }

    /// <summary>重试任务：优先服务端 retry，必要时用本机源文件重建（见 TransferCenter 分部类）。</summary>

    // ── 悬浮球收件箱（真实传输任务）──────────────────────────

    public async Task<IReadOnlyList<IncomingTaskInfo>> GetIncomingAsync(CancellationToken ct = default)
    {
        var result = await _api.GetAsync<List<TransferTaskDto>>("/api/transfer/tasks").ConfigureAwait(false);
        if (result?.Data is null) return [];

        // 只保留"我是接收方且未完成"的任务：0=PENDING / 1=UPLOADING
        return result.Data
            .Where(t => t.ReceiverId == _currentUserId && t.Status is 0 or 1)
            .Select(t => new IncomingTaskInfo(t.Id.ToString(), t.FileName, t.FileSize,
                t.SenderId == _currentUserId ? "我" : $"设备 {t.SenderId}", t.Status))
            .ToList();
    }

    public async Task<bool> AcceptIncomingAsync(string id, CancellationToken ct = default)
    {
        var result = await _api
            .PostAsync<object>($"/api/transfer/tasks/{id}/complete")
            .ConfigureAwait(false);
        return result?.IsOk == true;
    }

    public async Task<bool> RejectIncomingAsync(string id, CancellationToken ct = default)
    {
        var result = await _api
            .DeleteAsync($"/api/transfer/tasks/{id}")
            .ConfigureAwait(false);
        return result is not null && result.Code == 200;
    }

    public async Task SendToAsync(DevicePeer peer, IReadOnlyList<string> localPaths)
    {
        if (!long.TryParse(peer.Id, out var receiverDeviceId)) return;

        foreach (var path in localPaths)
        {
            // 防御：路径不存在时跳过（FileInfo.Length 对缺失文件会抛异常）
            if (!System.IO.File.Exists(path)) continue;

            try
            {
                var info = new System.IO.FileInfo(path);
                var created = await _api.PostAsync<TransferTaskDto>("/api/transfer/tasks", new
                {
                    receiverDeviceId,
                    fileName = info.Name,
                    fileSize = info.Length,
                    chunkSize = 5 * 1024 * 1024
                }).ConfigureAwait(false);

                if (created?.IsOk != true) continue;

                _transfers.Insert(0, new TransferItem
                {
                    Id = created.Data?.Id.ToString() ?? Guid.NewGuid().ToString("N")[..8],
                    FileName = info.Name,
                    PeerName = peer.Name,
                    Size = info.Length,
                    Direction = TransferDirection.Upload,
                    State = TransferState.Pending,
                    LocalPath = path,
                    ReceiverDeviceId = peer.Id
                });
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"[Bond] 创建传输任务失败 {path}: {ex.Message}");
            }
        }

        Changed?.Invoke(this, EventArgs.Empty);
        // task-14：新任务进入等待/传输中后，交给 5s 轮询推进真实进度
        StartTransferPolling();
    }

    // 取消任务：POST /api/transfer/tasks/{id}/cancel（见 TransferCenter 分部类）

    /// <param name="silent">5s 轮询调用：失败时不写错误条、不打扰界面（数据保持上一份快照）。</param>
    private async Task RefreshTransfersAsync(bool silent = false)
    {
        try
        {
            // task-54：传 page 走分页信封，列表稳定在最近 100 条。
            var tasks = await _api.GetTransferTasksPagedAsync(1, 100).ConfigureAwait(false);
            if (tasks is null)
            {
                if (silent) return;
                _lastTransfersError = LoadRetry.Humanize(_api.LastError, "无法加载传输任务，请检查网络后重试");
                Changed?.Invoke(this, EventArgs.Empty);
                return;
            }

            _lastTransfersError = null;

            // task-96：任务行只显示设备名，先取一份「设备 id → 设备名」快照（拿不到就回退「接收方设备」）
            var deviceNames = await SnapshotDeviceNamesAsync().ConfigureAwait(false);
            var fresh = tasks.Select(t => MapTransferItem(t, deviceNames)).ToList();
            await ApplyTransferSnapshotAsync(fresh).ConfigureAwait(false);
            Changed?.Invoke(this, EventArgs.Empty);
            StartTransferPolling();
        }
        catch (Exception ex)
        {
            if (silent) return;
            _lastTransfersError = LoadRetry.Humanize(ex.Message, "无法加载传输任务，请检查网络后重试");
            Changed?.Invoke(this, EventArgs.Empty);
        }
    }

    // ── IInboxService ────────────────────────────────────────
    // ITransferService.Items 与 IInboxService.Items 同名，这里显式实现后者
    ObservableCollection<InboxItem> IInboxService.Items => _inbox;

    public int PendingCount => _inbox.Count;

    public void Dismiss(InboxItem item)
    {
        if (_inbox.Remove(item)) InboxChanged?.Invoke(this, EventArgs.Empty);
    }

    public event EventHandler? InboxChanged;

    event EventHandler? IInboxService.Changed
    {
        add => InboxChanged += value;
        remove => InboxChanged -= value;
    }

    /// <summary>Unix 毫秒 → 本地时间；WebDAV、聊天等分部类共用这一份，别再各写一份。</summary>
    internal static DateTimeOffset? FromMillis(long? millis) =>
        millis is { } value && value > 0
            ? DateTimeOffset.FromUnixTimeMilliseconds(value)
            : null;

    /// <summary>同 <see cref="FromMillis"/>，但缺值时回退到当前时间（聊天时间戳只需要本地 DateTime）。</summary>
    internal static DateTime FromMillisOrNow(long? millis) => FromMillis(millis)?.LocalDateTime ?? DateTime.Now;

    private async Task RefreshInboxAsync()
    {
        var requests = await _api.GetAsync<List<FriendRequestDto>>("/api/friends/requests").ConfigureAwait(false);
        var invites = await _api.GetAsync<List<InvitationDto>>("/api/workspaces/invitations").ConfigureAwait(false);

        // task-97：两个请求都用 ConfigureAwait(false)，续体可能在后台线程；
        // _inbox 绑定在界面（待处理列表），必须在 UI 线程合并成一次改写。
        Ui(() =>
        {
            // task-135：先攒新的一批，再逐项同步（不 Clear，避免 Reset 残留容器）。
            var fresh = new List<InboxItem>();

            foreach (var r in requests?.Data ?? [])
            {
                fresh.Add(new InboxItem
                {
                    Id = $"fr-{r.Id}",
                    Title = $"{(string.IsNullOrEmpty(r.FromNickname) ? r.FromUsername : r.FromNickname)} 申请添加好友",
                    Detail = string.IsNullOrEmpty(r.Message) ? "好友申请" : r.Message,
                    Kind = InboxKind.WorkspaceInvite,
                    At = r.CreatedAt is { } ms ? DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime : DateTime.Now
                });
            }

            foreach (var inv in invites?.Data ?? [])
            {
                fresh.Add(new InboxItem
                {
                    Id = $"wi-{inv.Id}",
                    Title = $"{inv.InviterName} 邀请你加入「{inv.WorkspaceName}」",
                    Detail = "工作区邀请",
                    Kind = InboxKind.WorkspaceInvite,
                    At = inv.CreatedAt is { } ms ? DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime : DateTime.Now
                });
            }

            CollectionSync.Apply(_inbox, fresh);
            InboxChanged?.Invoke(this, EventArgs.Empty);
        });
    }
}
