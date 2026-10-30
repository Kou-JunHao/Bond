using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Threading;
using Bond.Models;

namespace Bond.Services;

/// <summary>
/// 演示数据存储：一次性实现全部领域接口，供重构骨架直接跑起来。
/// <para>
/// 真实后端接入时，把这个类按接口拆成 6 个实现（FileService / TransferService / …）
/// 并替换 <see cref="AppServices"/> 里的注册即可，ViewModel 一行都不用改。
/// </para>
/// </summary>
public sealed partial class DemoBondStore :
    IFileService,
    ITransferService,
    IDeviceService,
    IWorkspaceService,
    IInboxService,
    ISessionService
{
    private readonly DispatcherTimer _tick;
    private readonly ObservableCollection<NodeItem> _nodes = [];
    private readonly ObservableCollection<NodeItem> _recent = [];
    private readonly ObservableCollection<TransferItem> _transfers = [];
    private readonly ObservableCollection<DevicePeer> _peers = [];
    private readonly ObservableCollection<WorkspaceItem> _workspaces = [];
    private readonly ObservableCollection<InboxItem> _inbox = [];

    public DemoBondStore()
    {
        SeedFiles();
        SeedPeers();
        SeedWorkspaces();
        SeedInbox();
        SeedTransfers();

        // 让演示界面"活"起来：每 400ms 推进一次进行中的任务。
        _tick = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(400) };
        _tick.Tick += (_, _) => AdvanceTransfers();
        _tick.Start();
    }

    // ── IFileService ────────────────────────────────────────────
    public ObservableCollection<NodeItem> Nodes => _nodes;

    public ObservableCollection<NodeItem> Recent => _recent;

    public StorageQuota Quota { get; private set; } = new()
    {
        UsedBytes = 38L * 1024 * 1024 * 1024 / 10,
        TotalBytes = 50L * 1024 * 1024 * 1024
    };

    public string CurrentPath { get; private set; } = "/";

    public void EnterFolder(NodeItem folder)
    {
        if (folder.Kind != NodeKind.Folder) return;
        CurrentPath = CurrentPath == "/" ? $"/{folder.Name}" : $"{CurrentPath}/{folder.Name}";
    }

    public void GoUp()
    {
        if (CurrentPath == "/") return;
        var idx = CurrentPath.LastIndexOf('/');
        CurrentPath = idx <= 0 ? "/" : CurrentPath[..idx];
    }

    public Task UploadAsync(IReadOnlyList<string> localPaths, CancellationToken ct = default)
    {
        foreach (var path in localPaths)
        {
            var name = System.IO.Path.GetFileName(path);
            var size = SafeFileSize(path);
            var node = new NodeItem
            {
                Id = NewId("n"),
                Name = name,
                Kind = NodeKind.File,
                Size = size,
                ParentPath = CurrentPath,
                ModifiedAt = DateTime.Now
            };
            _nodes.Insert(0, node);
            _recent.Insert(0, node);
            if (_recent.Count > 8) _recent.RemoveAt(_recent.Count - 1);
        }

        Quota = new StorageQuota
        {
            UsedBytes = Quota.UsedBytes + 6_400_000,
            TotalBytes = Quota.TotalBytes
        };
        return Task.CompletedTask;
    }

    // 演示实现：单文件上传同样进内存列表；进度直接 100%。
    public async Task<bool> UploadQuickAsync(string localPath, IProgress<double>? progress = null,
        CancellationToken ct = default)
    {
        await UploadAsync([localPath], ct).ConfigureAwait(true);
        progress?.Report(1);
        return true;
    }

    // 演示实现没有后端：新建文件夹如实失败，不伪造成功。
    public Task<(bool Ok, string? Error)> CreateFolderAsync(string parentPath, string name,
        CancellationToken ct = default)
        => Task.FromResult<(bool, string?)>((false, "演示模式没有连接到服务器，无法新建文件夹"));

    // 演示数据没有真实计费权益：返回 null，界面据此隐藏额度行。
    public Task<EntitlementsInfo?> GetEntitlementsAsync(CancellationToken ct = default)
        => Task.FromResult<EntitlementsInfo?>(null);

    // 演示实现：分享链接只在内存里造一条，供 UI 联调；真实数据走 HttpBondStore。
    public Task<(ShareLink? Share, string? Error)> CreateShareAsync(NodeItem file, bool requiresCode,
        string? code, int? expiresDays, int maxDownloads = 0, CancellationToken ct = default)
    {
        var share = new ShareLink
        {
            Id = Guid.NewGuid().ToString("N"),
            Token = Guid.NewGuid().ToString("N"),
            Url = "https://bond-api.sg.skkk.uno/s/demo-token",
            FileName = file.Name,
            FileSize = file.Size,
            // task-183：演示模式同样标记文件夹分享（UI 徽标/文案按真实节点类型走）。
            IsDirectory = file.IsFolder,
            RequiresCode = requiresCode,
            Code = requiresCode ? code : null,
            ExpiresAt = expiresDays is { } d ? DateTimeOffset.UtcNow.AddDays(d) : null,
            MaxDownloads = maxDownloads,
            Status = "active",
            CreatedAt = DateTimeOffset.UtcNow
        };
        return Task.FromResult<(ShareLink?, string?)>((share, null));
    }

    // 演示模式不连服务器：目标空间为空、转存直接失败并说明原因（不伪造成功）。
    public Task<(IReadOnlyList<NodeItem>? Folders, string? Error)> ListFoldersAsync(FileSpace? space, string path,
        CancellationToken ct = default)
        => Task.FromResult<(IReadOnlyList<NodeItem>?, string?)>(([], null));

    public Task<CopyResult> CopyAsync(NodeItem source, string? targetWorkspaceId, string targetParentPath,
        string? newName, CancellationToken ct = default)
        => Task.FromResult(CopyResult.Failed(0, "演示模式（离线）不连接服务器，无法转存；请登录后使用。"));

    public Task<CopyResult> MoveAsync(NodeItem source, string? targetWorkspaceId, string targetParentPath,
        string? newName, CancellationToken ct = default)
        => Task.FromResult(CopyResult.Failed(0, "演示模式（离线）不连接服务器，无法移动；请登录后使用。"));

    public Task<(IReadOnlyList<ShareLink>? Shares, string? Error)> GetSharesAsync(CancellationToken ct = default)
        => Task.FromResult<(IReadOnlyList<ShareLink>?, string?)>(([], null));

    public Task<string?> RevokeShareAsync(string shareId, CancellationToken ct = default)
        => Task.FromResult<string?>(null);

    // 演示模式（离线）没有服务器，编辑分享一律报不可用，不伪造本地成功。
    public Task<(ShareLink? Share, string? Error)> UpdateShareAsync(
        string shareId,
        bool changeExtractCode,
        string? extractCode,
        bool changeExpiresAt,
        long? expiresAtMillis,
        bool changeMaxDownloads,
        int? maxDownloads,
        CancellationToken ct = default)
        => Task.FromResult<(ShareLink?, string?)>(
            (null, "演示模式（离线）不连接服务器，无法编辑分享；请登录后使用。"));

    public Task DeleteAsync(NodeItem node)
    {
        _nodes.Remove(node);
        _recent.Remove(node);
        return Task.CompletedTask;
    }

    // 演示实现：个人空间 + 工作区都从内存集合来。
    public ObservableCollection<FileSpace> Spaces { get; } =
    [
        new FileSpace { Id = "personal", Name = "个人空间", Kind = FileSpaceKind.Personal }
    ];

    public FileSpace? CurrentSpace => Spaces.FirstOrDefault();

    public bool IsPersonalSpace => true;

    /// <summary>IFileService / ITransferService 同名成员：演示实现没有异步加载。</summary>
    public bool IsLoading => false;

    public string? LastError => null;

    // task-43：空间列表的加载态 / 错误态；演示实现没有异步加载。
    public bool IsSpacesLoading => false;

    public string? LastSpacesError => null;

    public Task RefreshSpacesAsync() => Task.CompletedTask;

    public void NavigateTo(string path)
    {
        CurrentPath = string.IsNullOrWhiteSpace(path) || path == "/" ? "/" : path.TrimEnd('/');
    }

    public Task SelectSpaceAsync(FileSpace space) => Task.CompletedTask;

    public Task ReloadListAsync() => Task.CompletedTask;

    /// <summary>
    /// task-123：演示模式没有服务端搜索，按名字在内存节点里过滤（仍是真实数据，不编造条目）。
    /// </summary>
    public Task<FileSearchOutcome> SearchFilesAsync(string query, string? workspaceId, int limit, string? cursor)
    {
        var q = (query ?? string.Empty).Trim();
        if (q.Length == 0) return Task.FromResult(new FileSearchOutcome(null, "搜索关键词不能为空"));
        if (q.Length > 100) return Task.FromResult(new FileSearchOutcome(null, "搜索关键词过长（最多 100 字符）"));

        var items = _nodes
            .Where(n => n.Name.Contains(q, StringComparison.OrdinalIgnoreCase))
            .Select(n => new NodeItem
            {
                Id = n.Id,
                Name = n.Name,
                Kind = n.Kind,
                Size = n.Size,
                ContentType = n.ContentType,
                ParentPath = n.ParentPath,
                SpaceId = n.SpaceId,
                PathHint = (n.ParentPath.TrimEnd('/') + "/" + n.Name).Replace("//", "/"),
                FullPath = (n.ParentPath.TrimEnd('/') + "/" + n.Name).Replace("//", "/"),
                ModifiedAt = n.ModifiedAt
            })
            .Take(Math.Clamp(limit, 1, 100))
            .ToList();

        return Task.FromResult(new FileSearchOutcome(
            new FileSearchPage(items, null, false, items.Count), null));
    }

    public Task<string?> DownloadAsync(NodeItem node, string targetDirectory) => Task.FromResult<string?>(null);

    public Task RenameAsync(NodeItem node, string newName) => Task.CompletedTask;

    public Task<bool> RetryAsync(TransferItem item) => Task.FromResult(false);

    // 收件箱：演示数据里没有真实任务，返回空。
    public Task<IReadOnlyList<IncomingTaskInfo>> GetIncomingAsync(CancellationToken ct = default)
        => Task.FromResult<IReadOnlyList<IncomingTaskInfo>>([]);

    public Task<bool> AcceptIncomingAsync(string id, CancellationToken ct = default)
        => Task.FromResult(true);

    public Task<bool> RejectIncomingAsync(string id, CancellationToken ct = default)
        => Task.FromResult(true);

    // task-94：演示数据里没有「别人发给我」的任务，接受/拒绝按本地状态推进。
    public Task<bool> AcceptAsync(TransferItem item, CancellationToken ct = default)
    {
        if (item is { State: TransferState.Pending })
        {
            item.State = TransferState.Running;
            Changed?.Invoke(this, EventArgs.Empty);
        }

        return Task.FromResult(true);
    }

    public Task<bool> RejectAsync(TransferItem item, CancellationToken ct = default)
    {
        if (item is { State: TransferState.Pending })
        {
            item.State = TransferState.Rejected;
            Changed?.Invoke(this, EventArgs.Empty);
        }

        return Task.FromResult(true);
    }

    // ── ITransferService ────────────────────────────────────────
    //  显式实现：ITransferService.Items 与 IInboxService.Items 同名。
    ObservableCollection<TransferItem> ITransferService.Items => _transfers;

    public bool HasActive => _transfers.Any(t => t.IsActive);

    public double OverallProgress
    {
        get
        {
            var active = _transfers.Where(t => t.IsActive).ToList();
            return active.Count == 0 ? 0 : active.Average(t => t.Progress);
        }
    }

    public event EventHandler? Changed;

    public async Task SendToAsync(DevicePeer peer, IReadOnlyList<string> localPaths)
    {
        foreach (var path in localPaths)
        {
            _transfers.Insert(0, new TransferItem
            {
                Id = NewId("t"),
                FileName = System.IO.Path.GetFileName(path),
                PeerName = peer.Name,
                Size = SafeFileSize(path),
                Direction = TransferDirection.Upload,
                State = TransferState.Running,
                Progress = 0
            });
        }

        Changed?.Invoke(this, EventArgs.Empty);
        await Task.Delay(120).ConfigureAwait(true);
    }

    public Task<bool> CancelAsync(TransferItem item)
    {
        item.State = TransferState.Canceled;
        Changed?.Invoke(this, EventArgs.Empty);
        return Task.FromResult(true);
    }

    // task-14：演示数据没有真实上传/下载会话，保持空集合让传输中心走空态。
    private readonly ObservableCollection<TransferSession> _sessions = [];

    public ObservableCollection<TransferSession> Sessions => _sessions;

    public event EventHandler? SessionsChanged;

    public void CancelSession(TransferSession session)
    {
        session.SetState(TransferSessionState.Canceled);
        SessionsChanged?.Invoke(this, EventArgs.Empty);
    }

    private void AdvanceTransfers()
    {
        var moved = false;
        foreach (var t in _transfers.Where(t => t.State == TransferState.Running))
        {
            t.Progress = Math.Min(1.0, t.Progress + 0.035);
            if (t.Progress >= 1.0) t.State = TransferState.Done;
            moved = true;
        }

        if (moved) Changed?.Invoke(this, EventArgs.Empty);
    }

    // ── IDeviceService ──────────────────────────────────────────
    public ObservableCollection<DevicePeer> Peers => _peers;

    public bool IsScanning { get; private set; }

    public event EventHandler? DeviceChanged;

    event EventHandler? IDeviceService.Changed
    {
        add => DeviceChanged += value;
        remove => DeviceChanged -= value;
    }

    public async Task RefreshAsync()
    {
        IsScanning = true;
        await Task.Delay(320).ConfigureAwait(true);
        IsScanning = false;
        DeviceChanged?.Invoke(this, EventArgs.Empty);
    }

    // 演示数据没有服务端设备行（task-35 的真实注册/心跳走 HttpBondStore）
    public string? ServerDeviceId => null;

    public Task<string?> RemoveAsync(DevicePeer peer)
    {
        if (peer is null) return Task.FromResult<string?>("设备不存在");

        var existing = _peers.FirstOrDefault(p => p.Id == peer.Id);
        if (existing is null) return Task.FromResult<string?>("设备已经不在列表里");

        _peers.Remove(existing);
        DeviceChanged?.Invoke(this, EventArgs.Empty);
        return Task.FromResult<string?>(null);
    }

    // ── IWorkspaceService ───────────────────────────────────────
    public ObservableCollection<WorkspaceItem> Workspaces => _workspaces;

    Task IWorkspaceService.RefreshAsync() => Task.CompletedTask;

    public Task CreateAsync(string name, string? description = null)
    {
        var palette = new[] { "#4F7CFF", "#22A06B", "#E8A33D", "#B45BFF", "#E5534B" };
        _workspaces.Add(new WorkspaceItem
        {
            Id = NewId("w"),
            Name = name,
            Description = description ?? "",
            OwnerName = AccountName,
            IsOwner = true,
            Role = 0,
            MemberCount = 1,
            FileCount = 0,
            UsedBytes = 0,
            AccentHex = palette[_workspaces.Count % palette.Length]
        });
        Changed?.Invoke(this, EventArgs.Empty);
        return Task.CompletedTask;
    }

    public Task UpdateAsync(WorkspaceItem workspace, string name, string? description)
    {
        var index = _workspaces.IndexOf(workspace);
        if (index >= 0)
        {
            _workspaces[index] = new WorkspaceItem
            {
                Id = workspace.Id,
                Name = name,
                Description = description ?? "",
                OwnerName = workspace.OwnerName,
                IsOwner = workspace.IsOwner,
                Role = workspace.Role,
                MemberCount = workspace.MemberCount,
                FileCount = workspace.FileCount,
                UsedBytes = workspace.UsedBytes,
                QuotaBytes = workspace.QuotaBytes,
                AccentHex = workspace.AccentHex
            };
        }
        Changed?.Invoke(this, EventArgs.Empty);
        return Task.CompletedTask;
    }

    public Task DeleteAsync(WorkspaceItem workspace)
    {
        Workspaces.Remove(workspace);
        Changed?.Invoke(this, EventArgs.Empty);
        return Task.CompletedTask;
    }

    public Task<IReadOnlyList<WorkspaceMemberItem>> GetMembersAsync(WorkspaceItem workspace)
    {
        IReadOnlyList<WorkspaceMemberItem> members =
        [
            new WorkspaceMemberItem
            {
                UserId = "1",
                Nickname = AccountName,
                Role = 0,
                CanRemove = false
            }
        ];
        return Task.FromResult(members);
    }

    public Task RemoveMemberAsync(WorkspaceItem workspace, WorkspaceMemberItem member) => Task.CompletedTask;

    public Task SetMemberRoleAsync(WorkspaceItem workspace, WorkspaceMemberItem member, int role)
    {
        member.Role = role;
        Changed?.Invoke(this, EventArgs.Empty);
        return Task.CompletedTask;
    }

    public Task<string?> GenerateInviteLinkAsync(WorkspaceItem workspace) =>
        Task.FromResult<string?>($"https://bond-api.sg.skkk.uno/ws/join/demo-{workspace.Id}");

    public Task InviteFriendAsync(WorkspaceItem workspace, string userIdInput) => Task.CompletedTask;

    public Task<IReadOnlyList<WorkspaceInvitation>> GetInvitationsAsync() =>
        Task.FromResult<IReadOnlyList<WorkspaceInvitation>>(Array.Empty<WorkspaceInvitation>());

    public Task AcceptInvitationAsync(WorkspaceInvitation invitation) => Task.CompletedTask;

    public Task RejectInvitationAsync(WorkspaceInvitation invitation) => Task.CompletedTask;

    // 演示模式：没有真实邀请链接，返回固定预览（不联网）。
    public Task<WorkspaceInvitePreview> VerifyInviteLinkAsync(string token) =>
        Task.FromResult(new WorkspaceInvitePreview
        {
            WorkspaceId = "demo",
            WorkspaceName = "演示工作区",
            InviterName = "Demo",
            ExpiresAtMillis = DateTimeOffset.UtcNow.AddDays(7).ToUnixTimeMilliseconds()
        });

    public Task AcceptInviteLinkAsync(string token) => Task.CompletedTask;

    // ── task-8：成员文件级 ACL（演示实现：内存保存，不联网） ─────────
    private readonly Dictionary<string, WorkspaceMemberAcl> _demoAcls = [];

    private static string AclKey(WorkspaceItem workspace, string userId) => $"{workspace.Id}:{userId}";

    public Task<WorkspaceMemberAcl> GetMemberAclAsync(WorkspaceItem workspace,
        WorkspaceMemberItem member, CancellationToken ct = default)
        => Task.FromResult(_demoAcls.TryGetValue(AclKey(workspace, member.UserId), out var acl)
            ? acl
            : WorkspaceMemberAcl.All(workspace.Id, member.UserId));

    public Task<WorkspaceMemberAcl> SetMemberAclAsync(WorkspaceItem workspace,
        WorkspaceMemberItem member, string accessMode, IReadOnlyList<WorkspaceAclGrant> grants,
        CancellationToken ct = default)
    {
        var restricted = string.Equals(accessMode, "restricted", StringComparison.OrdinalIgnoreCase);
        var acl = new WorkspaceMemberAcl
        {
            WorkspaceId = workspace.Id,
            UserId = member.UserId,
            AccessMode = restricted ? "restricted" : "all",
            Grants = restricted ? grants : []
        };
        _demoAcls[AclKey(workspace, member.UserId)] = acl;
        return Task.FromResult(acl);
    }

    public Task<WorkspaceMemberAcl> GetMyAclAsync(WorkspaceItem workspace, CancellationToken ct = default)
        => Task.FromResult(WorkspaceMemberAcl.All(workspace.Id, "1"));

    public Task<(IReadOnlyList<NodeItem>? Entries, string? Error)> ListAclEntriesAsync(
        WorkspaceItem workspace, string path, CancellationToken ct = default)
        => Task.FromResult<(IReadOnlyList<NodeItem>?, string?)>(([], null));

    // ── IInboxService ───────────────────────────────────────────
    public ObservableCollection<InboxItem> Items => _inbox;

    public int PendingCount => _inbox.Count;

    public event EventHandler? InboxChanged;

    event EventHandler? IInboxService.Changed
    {
        add => InboxChanged += value;
        remove => InboxChanged -= value;
    }

    public void Dismiss(InboxItem item)
    {
        if (_inbox.Remove(item)) InboxChanged?.Invoke(this, EventArgs.Empty);
    }

    // ── ISessionService ─────────────────────────────────────────
    private string _deviceName = Environment.MachineName;

    public string DeviceName
    {
        get => _deviceName;
        set => _deviceName = value;
    }

    public string AccountName => "demo";

    public bool IsSignedIn => true;

    public string DeviceCode => "BOND-7K2Q-9F3M";

    public string PresenceText => Presence switch
    {
        PresenceState.Online => "已连接云端",
        PresenceState.Relay => "中继可用",
        PresenceState.LanOnly => "仅局域网",
        _ => "离线"
    };

    public PresenceState Presence => PresenceState.Online;

    public string? AvatarUrl => null;

    public object? AvatarSource => null;

    // ── 种子数据 ────────────────────────────────────────────────
    private void SeedFiles()
    {
        void Add(string name, NodeKind kind, long size, string parent = "/", bool shared = false)
            => _nodes.Add(new NodeItem
            {
                Id = NewId("n"),
                Name = name,
                Kind = kind,
                Size = size,
                ParentPath = parent,
                IsShared = shared,
                ModifiedAt = DateTime.Now.AddMinutes(-_nodes.Count * 37)
            });

        // 根目录
        Add("设计稿", NodeKind.Folder, 0);
        Add("项目文档", NodeKind.Folder, 0, shared: true);
        Add("安装包", NodeKind.Folder, 0);
        Add("Bond 架构评审.md", NodeKind.Document, 24_800);
        Add("产品需求草案.md", NodeKind.Document, 51_200, shared: true);
        Add("局域网协议时序图.png", NodeKind.File, 1_240_000);
        Add("BondClient-1.0.0.zip", NodeKind.File, 55_800_000);
        Add("会议纪要-1018.md", NodeKind.Document, 8_900);

        // 设计稿
        Add("首页-v3.fig", NodeKind.File, 18_400_000, "/设计稿", shared: true);
        Add("悬浮球动效.mp4", NodeKind.File, 42_000_000, "/设计稿");
        Add("图标规范.md", NodeKind.Document, 12_600, "/设计稿");

        // 项目文档
        Add("架构评审记录.md", NodeKind.Document, 31_400, "/项目文档", shared: true);
        Add("接口约定-v2.md", NodeKind.Document, 22_100, "/项目文档", shared: true);

        // 安装包
        Add("BondSetup-1.0.0.exe", NodeKind.File, 55_774_821, "/安装包");
        Add("BondSetup-0.9.3.exe", NodeKind.File, 37_462_340, "/安装包");

        foreach (var node in _nodes.Where(n => n.ParentPath == "/").Take(6)) _recent.Add(node);
    }

    private void SeedPeers()
    {
        _peers.Add(new DevicePeer { Id = "d1", Name = "MacBook Pro", Ip = "192.168.1.12", Platform = "macOS" });
        _peers.Add(new DevicePeer { Id = "d2", Name = "Pixel 9", Ip = "192.168.1.31", RequiresPassword = true, Platform = "Android" });
        _peers.Add(new DevicePeer { Id = "d3", Name = "办公室 NAS", Ip = "192.168.1.5", Platform = "Linux" });
        _peers.Add(new DevicePeer { Id = "d4", Name = "ThinkPad", Ip = "—", IsOnline = false });
    }

    private void SeedWorkspaces()
    {
        _workspaces.Add(new WorkspaceItem
        {
            Id = "w1", Name = "产品设计", OwnerName = "演示用户", MemberCount = 5, FileCount = 42,
            UsedBytes = 620L * 1024 * 1024, AccentHex = "#4F7CFF"
        });
        _workspaces.Add(new WorkspaceItem
        {
            Id = "w2", Name = "客户交付-明远", OwnerName = "李工", MemberCount = 3, FileCount = 17,
            UsedBytes = 1_800L * 1024 * 1024, AccentHex = "#22A06B"
        });
        _workspaces.Add(new WorkspaceItem
        {
            Id = "w3", Name = "个人知识库", OwnerName = "演示用户", MemberCount = 1, FileCount = 6,
            UsedBytes = 48L * 1024 * 1024, AccentHex = "#B45BFF"
        });
    }

    private void SeedInbox()
    {
        _inbox.Add(new InboxItem
        {
            Id = "i1", Title = "MacBook Pro 请求发送 3 个文件",
            Detail = "共 128.4 MB · 局域网直连", Kind = InboxKind.TransferRequest,
            At = DateTime.Now.AddMinutes(-2)
        });
        _inbox.Add(new InboxItem
        {
            Id = "i2", Title = "李工 邀请你加入「客户交付-明远」",
            Detail = "角色：内容管理员", Kind = InboxKind.WorkspaceInvite,
            At = DateTime.Now.AddMinutes(-25)
        });
    }

    private void SeedTransfers()
    {
        _transfers.Add(new TransferItem
        {
            Id = "t1", FileName = "Bond-设计稿-v3.sketch", PeerName = "李工", Size = 48_200_000,
            Direction = TransferDirection.Upload, State = TransferState.Running, Progress = 0.42
        });
        _transfers.Add(new TransferItem
        {
            Id = "t2", FileName = "会议录屏.mp4", PeerName = "MacBook Pro", Size = 312_000_000,
            Direction = TransferDirection.Download, State = TransferState.Done, Progress = 1
        });
    }

    private static long SafeFileSize(string path)
    {
        try { return new System.IO.FileInfo(path).Length; }
        catch { return 1024 * 1024; }
    }

    private static string NewId(string prefix) => $"{prefix}{Guid.NewGuid():N}"[..10];
}
