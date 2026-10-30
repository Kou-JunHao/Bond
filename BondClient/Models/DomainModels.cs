using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;

namespace Bond.Models;

// ═══════════════════════════════════════════════════════════════
//  领域模型（新版单表设计的精简投影：node / container / acl）
//  真实后端落地时，这里换成 DTO 即可，ViewModel 不需要改。
// ═══════════════════════════════════════════════════════════════

/// <summary>统一节点类型：网盘文件、文件夹、文档、会话附件都是同一种东西。</summary>
public enum NodeKind
{
    Folder = 0,
    File = 1,
    Document = 2
}

/// <summary>一个存储节点。对应目标架构里的 <c>nodes</c> 表。</summary>
public sealed class NodeItem : CommunityToolkit.Mvvm.ComponentModel.ObservableObject
{
    public required string Id { get; init; }
    public required string Name { get; init; }
    public NodeKind Kind { get; init; } = NodeKind.File;

    /// <summary>字节数；文件夹为 0。</summary>
    public long Size { get; init; }

    /// <summary>内容哈希（秒传用）。文件夹与未上传的节点为 null。</summary>
    public string? ContentHash { get; init; }

    public string ParentPath { get; init; } = "/";
    public DateTime ModifiedAt { get; init; } = DateTime.Now;
    public bool IsShared { get; init; }

    /// <summary>节点所属空间："personal" 或团队 workspace id（缩略图 / 预览接口用）。</summary>
    public string SpaceId { get; init; } = "personal";

    /// <summary>服务端 contentType（可能为空）。</summary>
    public string? ContentType { get; init; }

    // ── 行内重命名状态（仅展示层使用，不参与数据层）────────────
    private bool _isEditing;
    private string _editName = string.Empty;
    private string? _editError;
    private bool _isSelected;

    /// <summary>平铺视图的选中态（列表视图仍由 ListBox 管）。</summary>
    public bool IsSelected
    {
        get => _isSelected;
        set => SetProperty(ref _isSelected, value);
    }

    // ── task-120：新建后短暂高亮（1.5 秒自动消退，仅展示层）──
    private bool _isNewlyCreated;

    public bool IsNewlyCreated
    {
        get => _isNewlyCreated;
        set => SetProperty(ref _isNewlyCreated, value);
    }

    // ── 上传进度覆盖层（task-42：只由本机真实字节驱动）─────────
    private bool _isUploading;
    private double _uploadProgress;

    /// <summary>该节点是否正在本机的上传队列里（行/卡片显示 2px 进度条）。</summary>
    public bool IsUploading
    {
        get => _isUploading;
        set => SetProperty(ref _isUploading, value);
    }

    /// <summary>上传进度 0–100（真实已传字节 / 总字节）。</summary>
    public double UploadProgress
    {
        get => _uploadProgress;
        set => SetProperty(ref _uploadProgress, value);
    }

    /// <summary>该行是否处于重命名编辑态。</summary>
    public bool IsEditing
    {
        get => _isEditing;
        set
        {
            if (SetProperty(ref _isEditing, value)) OnPropertyChanged(nameof(IsNotEditing));
        }
    }

    public bool IsNotEditing => !IsEditing;

    public string EditName
    {
        get => _editName;
        set => SetProperty(ref _editName, value);
    }

    public string? EditError
    {
        get => _editError;
        set
        {
            if (SetProperty(ref _editError, value)) OnPropertyChanged(nameof(HasEditError));
        }
    }

    public bool HasEditError => !string.IsNullOrEmpty(EditError);

    /// <summary>文件名主体长度（不含扩展名）。重命名进入编辑时预选这一段。</summary>
    public int NameBodyLength
    {
        get
        {
            if (Kind == NodeKind.Folder) return Name.Length;
            var ext = System.IO.Path.GetExtension(Name);
            return string.IsNullOrEmpty(ext) ? Name.Length : Math.Max(0, Name.Length - ext.Length);
        }
    }

    /// <summary>排序用：扩展名（小写，不带点）。</summary>
    public string Extension
    {
        get
        {
            if (Kind == NodeKind.Folder) return string.Empty;
            var ext = System.IO.Path.GetExtension(Name);
            return string.IsNullOrEmpty(ext) ? string.Empty : ext.TrimStart('.').ToLowerInvariant();
        }
    }

    // ── 预览类型（客户端按扩展名判断，与后端 ThumbnailService.detectKind 对齐）──
    public bool IsImage => Kind != NodeKind.Folder &&
        Extension is "jpg" or "jpeg" or "png" or "gif" or "bmp" or "webp";

    public bool IsPdf => Kind != NodeKind.Folder && Extension == "pdf";

    public bool IsVideo => Kind != NodeKind.Folder &&
        Extension is "mp4" or "mov" or "mkv" or "webm" or "m4v" or "avi";

    public bool IsText => Kind != NodeKind.Folder &&
        Extension is "txt" or "md" or "markdown" or "json" or "xml" or "yaml" or "yml" or "csv"
            or "log" or "ini" or "toml" or "cs" or "js" or "ts" or "java" or "py" or "go"
            or "rs" or "c" or "h" or "cpp" or "hpp" or "css" or "html" or "htm" or "sh"
            or "ps1" or "sql" or "conf" or "properties" or "gitignore";

    /// <summary>办公文档：服务端 Gotenberg 转 PDF 后交给 PDF 阅读器预览（task-60）。</summary>
    public bool IsOffice => Kind != NodeKind.Folder &&
        Extension is "doc" or "docx" or "xls" or "xlsx" or "ppt" or "pptx"
            or "odt" or "ods" or "odp" or "rtf";

    /// <summary>能否从服务端拿到缩略图（图片/PDF/视频）。</summary>
    public bool HasThumbnail => IsImage || IsPdf || IsVideo;

    /// <summary>用于界面的友好体积文本。</summary>
    public string SizeText => Kind == NodeKind.Folder ? "—" : FormatSize(Size);

    /// <summary>平铺卡片的 meta 左栏：文件夹显示"文件夹"，文件显示大小。</summary>
    public string TileMetaText => Kind == NodeKind.Folder ? "文件夹" : SizeText;

    /// <summary>平铺卡片副行（单行）：只显示体积，避免日期被截断成半截。</summary>
    public string TileSubline => TileMetaText;

    /// <summary>平铺格子的 ToolTip：完整名称 + 体积 + 修改时间（日期从副行移到悬停）。</summary>
    public string TileTooltip => string.IsNullOrEmpty(PathHint)
        ? $"{Name}\n{TileMetaText} · {ModifiedText}"
        : $"{Name}\n{PathHint}";

    /// <summary>
    /// task-123：服务端搜索结果里该节点的完整路径（含文件名），只用于展示与定位；
    /// 普通目录列表里为空，副行继续显示修改时间。
    /// </summary>
    public string? PathHint { get; init; }

    /// <summary>
    /// task-123：搜索结果里「进入」用的绝对路径（目录=目录自身路径，文件=文件自身路径）。
    /// 普通列表为空，导航仍按 <see cref="ParentPath"/> 处理。
    /// </summary>
    public string? FullPath { get; init; }

    /// <summary>
    /// task-127：搜索结果的来源名（「个人空间」或工作区名）。
    /// 取不到工作区名时为 null —— 副行只显示路径，不编造来源。
    /// </summary>
    public string? SourceName { get; init; }

    /// <summary>列表行副行：搜索结果显示「来源 · 父目录」，其余情况显示修改时间（task-127）。</summary>
    public string SubtitleText
    {
        get
        {
            if (string.IsNullOrEmpty(PathHint)) return ModifiedText;

            // 父目录来自 ParentPath（已是去掉文件名的目录）；根目录显示「根目录」，不再复述文件名。
            var isRoot = string.IsNullOrEmpty(ParentPath) || ParentPath == "/";
            var dir = isRoot ? "根目录" : ParentPath;
            if (!string.IsNullOrEmpty(SourceName)) return $"{SourceName} · {dir}";

            // 没有来源名就只给路径，不做任何猜测。
            return isRoot ? "/" : dir;
        }
    }

    /// <summary>平铺卡片副行：搜索结果显示完整路径，其余情况体积（task-123）。</summary>
    public string TilePathSubline => string.IsNullOrEmpty(PathHint) ? TileSubline : PathHint;

    /// <summary>文件图标（按扩展名区分形状）。</summary>
    public Material.Icons.MaterialIconKind Icon => FileVisuals.Icon(FileVisuals.Classify(Name, Kind));

    /// <summary>图标前景色。</summary>
    public Avalonia.Media.IBrush IconBrush =>
        FileVisuals.Brush(FileVisuals.Classify(Name, Kind));

    /// <summary>图标底座背景（同色低透明度）。</summary>
    public Avalonia.Media.IBrush IconBackground =>
        FileVisuals.Background(FileVisuals.Classify(Name, Kind));

    public string ModifiedText => ModifiedAt.ToString(ModifiedAt.Date == DateTime.Today
        ? "HH:mm"
        : "MM-dd HH:mm");

    public string KindText => Kind switch
    {
        NodeKind.Folder => "文件夹",
        NodeKind.Document => "文档",
        _ => "文件"
    };

    /// <summary>供菜单/按钮按需显示（文件夹可进入，文件可下载）。</summary>
    public bool IsFolder => Kind == NodeKind.Folder;

    public bool IsFile => Kind != NodeKind.Folder;

    public static string FormatSize(long bytes)
    {
        if (bytes <= 0) return "0 B";
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        double v = bytes;
        var i = 0;
        while (v >= 1024 && i < units.Length - 1) { v /= 1024; i++; }
        return $"{(i == 0 ? v.ToString("0") : v.ToString("0.#"))} {units[i]}";
    }
}

/// <summary>传输任务。</summary>
public sealed class TransferItem : CommunityToolkit.Mvvm.ComponentModel.ObservableObject
{
    private double _progress;
    private TransferState _state;

    public required string Id { get; init; }
    public required string FileName { get; init; }
    public required string PeerName { get; init; }
    public long Size { get; init; }
    public TransferDirection Direction { get; init; }

    /// <summary>task-94：这条任务是不是「别人发给我的」（接收方视角）——决定是否显示接受/拒绝。</summary>
    public bool IsIncoming { get; init; }

    public string SizeText => NodeItem.FormatSize(Size);

    public string DirectionText => Direction == TransferDirection.Upload ? "上传" : "下载";

    /// <summary>列表行图标（按文件类型区分）。</summary>
    public Material.Icons.MaterialIconKind Icon => FileVisuals.Icon(FileVisuals.Classify(FileName, NodeKind.File));

    public Avalonia.Media.IBrush IconBrush => FileVisuals.Brush(FileVisuals.Classify(FileName, NodeKind.File));

    public Avalonia.Media.IBrush IconBackground =>
        FileVisuals.Background(FileVisuals.Classify(FileName, NodeKind.File));

    public double Progress
    {
        get => _progress;
        set
        {
            if (SetProperty(ref _progress, value)) OnPropertyChanged(nameof(ProgressText));
        }
    }

    public string ProgressText => $"{Progress * 100:0}%";

    public TransferState State
    {
        get => _state;
        set
        {
            if (SetProperty(ref _state, value)) OnPropertyChanged(nameof(StateText));
        }
    }

    public string StateText => State switch
    {
        TransferState.Pending => "等待中",
        TransferState.Running => "传输中",
        TransferState.Done => "已完成",
        TransferState.Paused => "已暂停",
        TransferState.Failed => "失败",
        TransferState.Canceled => "已取消",
        TransferState.Expired => "已过期",
        TransferState.Rejected => "已拒绝",
        _ => ""
    };

    public bool IsActive => State is TransferState.Running or TransferState.Pending;

    public bool CanRetry => State is TransferState.Failed or TransferState.Canceled;

    /// <summary>
    /// 本机源文件路径：仅本次会话内发起、且源文件仍存在时可重试。
    /// 服务端任务记录里没有这个信息，所以重试按钮会据此判断可用性。
    /// </summary>
    public string? LocalPath { get; set; }

    /// <summary>接收方设备 id（重试时重建任务用）。</summary>
    public string? ReceiverDeviceId { get; set; }

    /// <summary>把千分比进度喂给 ProgressBar（0..100）。</summary>
    public double ProgressPercent => Progress * 100.0;
}

public enum TransferDirection { Upload, Download }

public enum TransferState { Pending, Running, Paused, Done, Failed, Canceled, Expired, Rejected }

/// <summary>局域网 / 云端对端设备。</summary>
public sealed class DevicePeer
{
    public required string Id { get; init; }
    public required string Name { get; init; }
    public string Ip { get; init; } = "—";
    public bool IsOnline { get; init; } = true;
    public bool RequiresPassword { get; init; }
    public string Platform { get; init; } = "Windows";

    /// <summary>这一行就是本机（服务端设备行 id == 本机登记 id）。</summary>
    public bool IsCurrentDevice { get; init; }

    /// <summary>服务端最后一次心跳时间（epoch 毫秒）；null 表示服务端没有记录。</summary>
    public long? LastSeenAt { get; init; }

    public string StatusText => IsOnline ? "在线" : "离线";

    public string Initial => string.IsNullOrEmpty(Name) ? "?" : Name[..1].ToUpperInvariant();

    /// <summary>
    /// 离线行补充「最后在线」时间。在线行留空：状态点已经表达得更清楚，
    /// 只有服务端真实给的 lastSeenAt 才显示（不用本地时间推算）。
    /// </summary>
    public string LastSeenText => IsOnline || LastSeenAt is not { } ms
        ? string.Empty
        : $"最后在线 {DateTimeOffset.FromUnixTimeMilliseconds(ms).ToLocalTime():MM-dd HH:mm}";

    /// <summary>状态色（在线绿 / 离线灰）。</summary>
    public Avalonia.Media.IBrush StatusBrush =>
        Avalonia.Media.Brush.Parse(IsOnline ? "#5D7358" : "#9A948B");
}

/// <summary>工作区（共享容器）。</summary>
public sealed class WorkspaceItem
{
    public required string Id { get; init; }
    public required string Name { get; init; }
    public string OwnerName { get; init; } = "";
    public int MemberCount { get; init; }
    public int FileCount { get; init; }
    public long UsedBytes { get; init; }
    public long QuotaBytes { get; init; } = 5 * 1024L * 1024 * 1024;

    /// <summary>后端 ownerId（成员/角色判断用）。</summary>
    public long OwnerId { get; init; }

    /// <summary>工作区描述（可空，单行省略展示）。</summary>
    public string Description { get; init; } = "";

    /// <summary>当前登录用户是否 owner（决定管理面板写权限）。</summary>
    public bool IsOwner { get; init; }

    /// <summary>按后端 WorkspaceMember.role：0=owner 1=admin 2=member。</summary>
    public int Role { get; init; } = 2;

    /// <summary>角色徽标文案（owner / 管理员 / 成员）。</summary>
    public string RoleText => Role switch
    {
        0 => "owner",
        1 => "管理员",
        _ => "成员"
    };

    /// <summary>卡片元信息行：成员 N · 文件 M。</summary>
    public string MetaLine => $"成员 {MemberCount} · 文件 {FileCount}";

    /// <summary>描述行（空描述给占位）。</summary>
    public string DescriptionLine => Description;

    /// <summary>描述为空时卡片不显示副行（避免标题与副行重复同一段文字）。</summary>
    public bool HasDescription => !string.IsNullOrWhiteSpace(Description);

    public string UsedText => NodeItem.FormatSize(UsedBytes);
    public string QuotaText => NodeItem.FormatSize(QuotaBytes);
    public double UsageRatio => QuotaBytes <= 0 ? 0 : Math.Clamp((double)UsedBytes / QuotaBytes, 0, 1);
    public string MemberText => $"{MemberCount} 位成员 · {FileCount} 个文件";

    public string Initial => string.IsNullOrEmpty(Name) ? "?" : Name[..1].ToUpperInvariant();

    public string OwnerLine => string.IsNullOrEmpty(OwnerName) ? "—" : OwnerName;

    public string MemberCountText => $"{MemberCount} 位成员";

    public string FileCountText => $"{FileCount} 个文件";

    // task-4：工作区卡片同样按「已用 <1%」显示，避免 0.x% 被四舍五入成 0%。
    public string RatioText => QuotaBytes > 0
        ? QuotaFormat.Percent(UsedBytes, QuotaBytes)
        : $"{UsageRatio * 100:0}%";

    /// <summary>成员头像首字母（最多展示 3 个）。</summary>
    public IReadOnlyList<string> MemberInitials
    {
        get
        {
            var count = Math.Clamp(MemberCount, 0, 3);
            var list = new List<string>(count);
            var seed = string.IsNullOrEmpty(Name) ? "B" : Name;
            for (var i = 0; i < count; i++)
                list.Add(seed[i % seed.Length].ToString().ToUpperInvariant());
            return list;
        }
    }

    public string AccentHex { get; init; } = "#4F7CFF";

    /// <summary>卡片主色（由 AccentHex 解析，避免 XAML 里做字符串→画刷转换）。</summary>
    public Avalonia.Media.IBrush Accent => Avalonia.Media.Brush.Parse(AccentHex);
}

/// <summary>工作区成员（管理面板列表项）。</summary>
public sealed class WorkspaceMemberItem : CommunityToolkit.Mvvm.ComponentModel.ObservableObject
{
    public required string UserId { get; init; }

    public string Nickname { get; init; } = "";

    private int _role;

    /// <summary>0=owner 1=admin 2=member。</summary>
    public int Role
    {
        get => _role;
        set
        {
            if (!SetProperty(ref _role, value)) return;
            OnPropertyChanged(nameof(RoleText));
            OnPropertyChanged(nameof(IsOwner));
            OnPropertyChanged(nameof(IsAdmin));
            OnPropertyChanged(nameof(RoleIndex));
        }
    }

    public string RoleText => Role switch
    {
        0 => "owner",
        1 => "管理员",
        _ => "成员"
    };

    public bool IsOwner => Role == 0;

    public bool IsAdmin => Role == 1;

    /// <summary>角色下拉的选中项：0=管理员、1=普通成员（仅 admin/member 行有下拉）。</summary>
    public int RoleIndex => IsAdmin ? 0 : 1;

    public bool CanRemove { get; init; }

    /// <summary>owner 视角下、非 owner 且非本人，才允许调整角色。</summary>
    public bool CanChangeRole { get; init; }

    /// <summary>task-8：当前用户能否为该成员设置文件权限（owner/admin 视角 + 目标不是 owner/admin 本人）。</summary>
    public bool CanManageAcl { get; init; }

    private string _aclSummary = string.Empty;
    private bool _aclRestricted;
    private bool _aclLoaded;

    /// <summary>
    /// task-8：该成员当前访问范围摘要：「全部文件」/「受限 N 项」。空串表示尚未加载。
    /// 由 WorkspacePageViewModel 在成员列表加载后按需拉取 GET …/members/{userId}/acl 填充。
    /// </summary>
    public string AclSummary
    {
        get => _aclSummary;
        set
        {
            if (!SetProperty(ref _aclSummary, value)) return;
            OnPropertyChanged(nameof(HasAclSummary));
            OnPropertyChanged(nameof(AclButtonText));
        }
    }

    public bool HasAclSummary => !string.IsNullOrEmpty(AclSummary);

    /// <summary>该成员是否处于 restricted（仅授权目录/文件可见）。</summary>
    public bool AclRestricted
    {
        get => _aclRestricted;
        set => SetProperty(ref _aclRestricted, value);
    }

    /// <summary>范围摘要是否已从服务端拿到（没拿到时按钮显示「权限」而不是「全部文件」，避免谎报）。</summary>
    public bool AclLoaded
    {
        get => _aclLoaded;
        set
        {
            if (SetProperty(ref _aclLoaded, value)) OnPropertyChanged(nameof(AclButtonText));
        }
    }

    /// <summary>成员行「权限」入口文案：已加载显示摘要，未加载/加载失败显示「权限」。</summary>
    public string AclButtonText => AclLoaded && HasAclSummary ? AclSummary : "权限";

    public string Initial => string.IsNullOrWhiteSpace(Nickname) ? "?" : Nickname[..1].ToUpperInvariant();
}

/// <summary>待处理的工作区邀请。</summary>
public sealed class WorkspaceInvitation
{
    public required string Id { get; init; }

    public string WorkspaceName { get; init; } = "";

    public string InviterName { get; init; } = "";

    public int Role { get; init; }

    public long? CreatedAt { get; init; }

    public string RoleText => Role switch
    {
        0 => "owner",
        1 => "管理员",
        _ => "成员"
    };

    public string FromText => string.IsNullOrWhiteSpace(InviterName)
        ? "收到工作区邀请"
        : $"{InviterName} 邀请你加入";
}

/// <summary>
/// 邀请链接/邀请码校验结果（GET /api/invite/{token}，匿名可读）。
/// 用于客户端「加入工作区」弹窗：先预览工作区名/邀请人/有效期，再确认加入。
/// </summary>
public sealed class WorkspaceInvitePreview
{
    public string WorkspaceId { get; init; } = "";

    public string WorkspaceName { get; init; } = "";

    public string? InviterName { get; init; }

    /// <summary>过期时间（epoch millis）；null = 永久或服务端未返回。</summary>
    public long? ExpiresAtMillis { get; init; }

    public string SummaryText
    {
        get
        {
            var inviter = string.IsNullOrWhiteSpace(InviterName) ? "某位成员" : InviterName;
            var name = string.IsNullOrWhiteSpace(WorkspaceName) ? "未知工作区" : WorkspaceName;
            if (ExpiresAtMillis is not long millis) return $"工作区「{name}」 · {inviter} 邀请你加入";

            var expires = DateTimeOffset.FromUnixTimeMilliseconds(millis).ToLocalTime();
            return $"工作区「{name}」 · {inviter} 邀请你加入 · 有效期至 {expires:yyyy-MM-dd HH:mm}";
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  task-8：工作区成员文件级 ACL（后端 V34 / /api/workspaces/{id}/…/acl）
//  语义：成员 accessMode=all 时不受限（默认）；restricted 时只能访问被授权的
//  目录前缀（含子目录）或指定文件；owner/admin 永远全权、不可被设置。
// ═══════════════════════════════════════════════════════════════

/// <summary>
/// 一条 ACL 授权（GET/PUT <c>/api/workspaces/{id}/members/{userId}/acl</c> 的 grants[i]）。
/// <para>folder：<see cref="ScopePath"/> 是规范化逻辑路径（<c>/</c> 开头、无尾斜杠，<c>/</c> = 整个工作区），
/// 覆盖该目录及其全部子路径；file：只覆盖 <see cref="FileId"/> 指向的单个文件。</para>
/// </summary>
public sealed class WorkspaceAclGrant : CommunityToolkit.Mvvm.ComponentModel.ObservableObject
{
    /// <summary>folder / file（服务端小写）。</summary>
    public string ScopeType { get; init; } = "folder";

    /// <summary>规范化逻辑路径；file 授权时服务端以文件真实路径回填。</summary>
    public string ScopePath { get; init; } = "/";

    /// <summary>file 授权必填；folder 授权为 null。</summary>
    public string? FileId { get; init; }

    private bool _canRead = true;
    private bool _canWrite;

    public bool CanRead
    {
        get => _canRead;
        set
        {
            if (!SetProperty(ref _canRead, value)) return;
            // 服务端口径：can_write ⇒ can_read。这里同步到界面，避免保存时才被服务端纠正。
            if (!value && _canWrite) CanWrite = false;
            OnPropertyChanged(nameof(PermissionText));
        }
    }

    public bool CanWrite
    {
        get => _canWrite;
        set
        {
            if (!SetProperty(ref _canWrite, value)) return;
            if (value && !_canRead) CanRead = true;
            OnPropertyChanged(nameof(PermissionText));
        }
    }

    public bool IsFolder => !string.Equals(ScopeType, "file", StringComparison.OrdinalIgnoreCase);

    public bool IsFile => !IsFolder;

    public string KindText => IsFolder ? "目录" : "文件";

    public string PathText => ScopePath;

    public string PermissionText => (CanRead, CanWrite) switch
    {
        (true, true) => "可读可写",
        (true, false) => "只读",
        _ => "可写"
    };

    /// <summary>给「授权项」去重用：folder 按路径、file 按 fileId。</summary>
    public string Key => IsFolder ? $"folder:{ScopePath}" : $"file:{FileId}";
}

/// <summary>
/// 某成员在一个工作区的有效访问范围（GET/PUT …/acl 与 GET …/acl/me 的响应体）。
/// </summary>
public sealed class WorkspaceMemberAcl
{
    public string WorkspaceId { get; init; } = "";

    public string UserId { get; init; } = "";

    /// <summary>all | restricted（服务端小写）。</summary>
    public string AccessMode { get; init; } = "all";

    public IReadOnlyList<WorkspaceAclGrant> Grants { get; init; } = [];

    public bool IsRestricted => string.Equals(AccessMode, "restricted", StringComparison.OrdinalIgnoreCase);

    /// <summary>成员行摘要文案：全部文件 / 受限 N 项。</summary>
    public string SummaryText => IsRestricted ? $"受限 {Grants.Count} 项" : "全部文件";

    public static WorkspaceMemberAcl All(string workspaceId, string userId) => new()
    {
        WorkspaceId = workspaceId,
        UserId = userId,
        AccessMode = "all",
        Grants = []
    };
}

/// <summary>文件页可切换的空间：个人空间（默认）或团队工作区。</summary>
public sealed class FileSpace
{
    public required string Id { get; init; }

    public required string Name { get; init; }

    public FileSpaceKind Kind { get; init; } = FileSpaceKind.Personal;

    /// <summary>团队空间对应的 workspace id；个人空间为 null。</summary>
    public string? WorkspaceId { get; init; }

    public long UsedBytes { get; init; }

    public long QuotaBytes { get; init; }

    public bool IsPersonal => Kind == FileSpaceKind.Personal;

    public bool IsTeam => Kind == FileSpaceKind.Workspace;

    /// <summary>切换器 / 标题徽标图标。</summary>
    public Material.Icons.MaterialIconKind Icon => IsPersonal
        ? Material.Icons.MaterialIconKind.AccountOutline
        : Material.Icons.MaterialIconKind.AccountGroup;

    public string UsedText => NodeItem.FormatSize(UsedBytes);

    public string QuotaText => QuotaBytes > 0 ? NodeItem.FormatSize(QuotaBytes) : "—";

    public double UsageRatio => QuotaBytes <= 0 ? 0 : Math.Clamp((double)UsedBytes / QuotaBytes, 0, 1);

    // task-4：已用不足 1% 时显示「<1%」，不再四舍五入成误导性的 0%（真 0 才显示 0%）。
    public string RatioText => QuotaBytes > 0 ? QuotaFormat.Percent(UsedBytes, QuotaBytes) : "";

    public string Subtitle => IsPersonal
        ? "个人空间 · 文件仅自己可见"
        : $"{Name} · 团队共享空间";
}

public enum FileSpaceKind
{
    Personal,
    Workspace
}

/// <summary>聊天连接状态：页面顶部细条用（绿=已连接、橙=重连中/轮询降级、灰=未登录）。</summary>
public enum ChatConnectionState
{
    /// <summary>未登录 / 聊天服务未启动。</summary>
    Offline,
    /// <summary>正在建立 WebSocket 连接。</summary>
    Connecting,
    /// <summary>WebSocket 在线，消息实时到达。</summary>
    Connected,
    /// <summary>WebSocket 不可用，已退化为 5 秒轮询同步。</summary>
    Polling
}

/// <summary>可发起私聊的好友（GET /api/friends）。</summary>
public sealed class ChatPeer
{
    public required string UserId { get; init; }
    public required string DisplayName { get; init; }
    public string? AvatarUrl { get; init; }

    public string Initial => string.IsNullOrEmpty(DisplayName) ? "?" : DisplayName[..1].ToUpperInvariant();
    public string Subtitle => "好友 · 点击发起私聊";
}

/// <summary>会话（GET /api/chat/conversations 的一行；字段随推送实时更新）。</summary>
public sealed partial class ChatThread : CommunityToolkit.Mvvm.ComponentModel.ObservableObject
{
    /// <summary>会话 id（雪花 bigint，全链路按字符串处理）。</summary>
    public required string Id { get; init; }

    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private string _title = "";

    /// <summary>direct（好友私聊） / workspace（工作区频道）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private string _type = "direct";

    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private string? _peerUserId;

    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private string? _workspaceId;

    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private string? _avatarUrl;

    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private string _lastMessage = "";

    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private DateTime _lastAt = DateTime.Now;

    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private int _unread;

    /// <summary>是否是当前打开的会话（会话列表高亮用）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _isSelected;

    /// <summary>置顶（会话成员维度；置顶会话排在列表最前，每人上限 20 个）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _pinned;

    /// <summary>免打扰（会话成员维度；有未读时只显示灰点，不显示红色数字）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _muted;

    public string TimeText => LastAt.Date == DateTime.Today ? LastAt.ToString("HH:mm") : LastAt.ToString("MM-dd");

    public string Initial => string.IsNullOrEmpty(Title) ? "?" : Title[..1].ToUpperInvariant();

    public bool HasUnread => Unread > 0;

    public string UnreadText => Unread > 99 ? "99+" : Unread.ToString();

    public bool IsChannel => string.Equals(Type, "workspace", StringComparison.OrdinalIgnoreCase);

    public string KindText => IsChannel ? "工作区频道" : "私聊";

    public string Subtitle => IsChannel ? "工作区频道" : "私聊";

    /// <summary>置顶徽标是否可见。</summary>
    public bool ShowPinned => Pinned;

    /// <summary>免打扰图标是否可见。</summary>
    public bool ShowMuted => Muted;

    /// <summary>未读数字徽标（免打扰会话不显示数字）。</summary>
    public bool ShowUnreadNumber => HasUnread && !Muted;

    /// <summary>免打扰会话的灰点（只在有未读时显示）。</summary>
    public bool ShowUnreadDot => HasUnread && Muted;

    partial void OnUnreadChanged(int value)
    {
        OnPropertyChanged(nameof(HasUnread));
        OnPropertyChanged(nameof(UnreadText));
        OnPropertyChanged(nameof(ShowUnreadNumber));
        OnPropertyChanged(nameof(ShowUnreadDot));
    }

    partial void OnPinnedChanged(bool value) => OnPropertyChanged(nameof(ShowPinned));

    partial void OnMutedChanged(bool value)
    {
        OnPropertyChanged(nameof(ShowMuted));
        OnPropertyChanged(nameof(ShowUnreadNumber));
        OnPropertyChanged(nameof(ShowUnreadDot));
    }

    partial void OnTypeChanged(string value)
    {
        OnPropertyChanged(nameof(IsChannel));
        OnPropertyChanged(nameof(KindText));
        OnPropertyChanged(nameof(Subtitle));
    }

    partial void OnTitleChanged(string value) => OnPropertyChanged(nameof(Initial));

    partial void OnLastAtChanged(DateTime value) => OnPropertyChanged(nameof(TimeText));
}

/// <summary>一条消息的本地发送状态（乐观发送用）。</summary>
public enum ChatSendState
{
    /// <summary>已在服务端确认。</summary>
    Sent,
    /// <summary>正在发送（乐观插入）。</summary>
    Sending,
    /// <summary>发送失败，气泡保留可重试。</summary>
    Failed
}

/// <summary>
/// 一条聊天消息。服务端返回 id 为雪花 bigint 字符串，绝不解析成数字。
/// 文本、文件、系统提示（含已删除占位）统一用这一个模型。
/// </summary>
public sealed partial class ChatMessage : CommunityToolkit.Mvvm.ComponentModel.ObservableObject
{
    public required string ConversationId { get; init; }

    /// <summary>text / file / system。</summary>
    public string Kind { get; init; } = "text";

    public string SenderId { get; init; } = "";

    public string SenderName { get; init; } = "";

    public bool IsMine { get; init; }

    /// <summary>客户端生成的幂等键（重试复用同一个，服务端去重）。</summary>
    public string? ClientMsgId { get; init; }

    private string? _fileId;

    private string _fileName = "";

    private long _fileSize;

    private string? _fileContentType;

    /// <summary>
    /// 附件消息的 fileId。task-165：附件是「先本地占位、上传成功后再补 fileId」，
    /// 因此这里必须是可通知属性，否则占位气泡在上传完成后不会切换到可预览/已发送状态。
    /// </summary>
    public string? FileId
    {
        get => _fileId;
        set
        {
            if (_fileId == value) return;
            _fileId = value;
            _fileNode = null;
            OnPropertyChanged(nameof(FileId));
            OnPropertyChanged(nameof(HasFile));
            OnPropertyChanged(nameof(FileNode));
            OnPropertyChanged(nameof(CanPreviewFile));
            OnPropertyChanged(nameof(CanDownload));
        }
    }

    public string FileName
    {
        get => _fileName;
        set
        {
            if (_fileName == value) return;
            _fileName = value;
            _fileNode = null;
            OnPropertyChanged(nameof(FileName));
            OnPropertyChanged(nameof(FileNode));
        }
    }

    public long FileSize
    {
        get => _fileSize;
        set
        {
            if (_fileSize == value) return;
            _fileSize = value;
            _fileNode = null;
            OnPropertyChanged(nameof(FileSize));
            OnPropertyChanged(nameof(FileSizeText));
            OnPropertyChanged(nameof(FileNode));
        }
    }

    public string? FileContentType
    {
        get => _fileContentType;
        set
        {
            if (_fileContentType == value) return;
            _fileContentType = value;
            _fileNode = null;
            OnPropertyChanged(nameof(FileContentType));
            OnPropertyChanged(nameof(FileNode));
        }
    }

    /// <summary>文件所在空间：私聊为 personal，工作区频道为该工作区 id（预览要拼对应 URL）。</summary>
    public string SpaceId { get; init; } = "personal";

    private DateTime _sentAt = DateTime.Now;

    /// <summary>发送时间。乐观插入时先用本地时间，服务端返回后用真实时间覆盖。</summary>
    public DateTime SentAt
    {
        get => _sentAt;
        set
        {
            if (_sentAt == value) return;
            _sentAt = value;
            OnPropertyChanged(nameof(SentAt));
            OnPropertyChanged(nameof(TimeText));
        }
    }

    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private string _id = "";

    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private string _text = "";

    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private ChatSendState _sendState = ChatSendState.Sent;

    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _isDeleted;

    /// <summary>该消息是否是某一天的第一条（渲染日期分隔）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _showDaySeparator;

    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private string _dayText = "";

    /// <summary>是否已撤回（撤回后正文/文件 id 均为空，界面显示一行撤回提示）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _recalled;

    /// <summary>自己这条消息是否已被对端读掉（已读回执）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _readByPeer;

    /// <summary>已读回执文案（只在自己最后一条消息上显示：「已读」）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private string _readReceipt = "";

    /// <summary>搜索命中后临时高亮。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _highlighted;

    /// <summary>同一发送者连续消息（5 分钟内、同一天）的第一条：组间距 12，组内 2。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _isGroupStart = true;

    /// <summary>同一发送者连续消息的最后一条：只有它显示头像与 hover 时间。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _isGroupEnd = true;

    /// <summary>未读分割线：进入会话时第一条对方发来的未读消息。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _showUnreadDivider;

    partial void OnIsGroupEndChanged(bool value)
    {
        OnPropertyChanged(nameof(ShowAvatar));
        OnPropertyChanged(nameof(ShowPeerAvatar));
        OnPropertyChanged(nameof(ShowMineAvatar));
    }

    // ── task-165：附件「即时占位 → 真实进度 → 成功替换/失败重试」 ──

    /// <summary>本地文件绝对路径：上传完成前用于本地渲染缩略图/图片，完成后清空。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private string _localPath = "";

    /// <summary>是否正在上传（占位气泡显示真实进度条，允许继续输入/发送其它消息）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _isUploading;

    /// <summary>上传进度 0..1（来自分片/流式写入的真实回调，不是假动画）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private double _uploadProgress;

    /// <summary>是否还是「仅本地」的占位消息（fileId 尚未拿到）。</summary>
    public bool HasLocalFile => !string.IsNullOrEmpty(LocalPath) && string.IsNullOrEmpty(FileId);

    /// <summary>上传进度百分比（0..100，供进度条与文字显示）。</summary>
    public int UploadProgressPercent =>
        (int)Math.Round(Math.Clamp(UploadProgress, 0, 1) * 100);

    public string UploadProgressText => UploadProgressPercent >= 100
        ? "上传完成"
        : $"上传中 {UploadProgressPercent}%";

    /// <summary>是否显示上传进度（仅自己的上传中消息）。</summary>
    public bool ShowUploadProgress => IsMine && IsUploading;

    /// <summary>是否可取消上传（上传中的本地占位消息）。</summary>
    public bool CanCancelUpload => IsMine && IsUploading;

    partial void OnLocalPathChanged(string value)
    {
        _fileNode = null;
        OnPropertyChanged(nameof(LocalPath));
        OnPropertyChanged(nameof(HasLocalFile));
        OnPropertyChanged(nameof(FileNode));
    }

    partial void OnIsUploadingChanged(bool value)
    {
        OnPropertyChanged(nameof(ShowUploadProgress));
        OnPropertyChanged(nameof(CanCancelUpload));
        OnPropertyChanged(nameof(CanPreviewFile));
        OnPropertyChanged(nameof(CanDownload));
    }

    partial void OnUploadProgressChanged(double value)
    {
        OnPropertyChanged(nameof(UploadProgressPercent));
        OnPropertyChanged(nameof(UploadProgressText));
    }

    // ── task-3：聊天附件下载「真实字节进度 → 成功保存路径 / 失败重试」 ──
    // 点击文件消息 = 下载到本地下载目录（QQ/微信的默认行为）；图片消息点开预览，预览面板里可另存。

    /// <summary>是否正在下载到本地（气泡下方显示真实字节进度，不阻塞继续聊天）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _isDownloading;

    /// <summary>下载进度 0..1（来自本地真实读流字节）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private double _downloadProgress;

    /// <summary>下载失败原因（失败态显示在气泡下方，点击重新下载）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private string _downloadError = "";

    /// <summary>下载成功后的本地保存路径（气泡下方展示，完整路径放 ToolTip）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private string _downloadSavedPath = "";

    public bool ShowDownloadProgress => IsDownloading;

    public int DownloadProgressPercent => (int)Math.Round(Math.Clamp(DownloadProgress, 0, 1) * 100);

    public string DownloadProgressText => $"下载中 {DownloadProgressPercent}%";

    public bool HasDownloadError => !IsDownloading && DownloadError.Length > 0;

    /// <summary>下载成功提示：完整本地路径（用户可据此去资源管理器找到文件）。</summary>
    public string DownloadStatusText => DownloadSavedPath.Length > 0 ? $"已保存到 {DownloadSavedPath}" : "";

    public bool HasDownloadStatus => !IsDownloading && DownloadStatusText.Length > 0;

    /// <summary>可点击下载：已拿到服务端 fileId、不在上传/下载中、未撤回未删除。</summary>
    public bool CanDownload => (IsFile || IsImage) && !IsUploading && !IsDownloading;

    partial void OnIsDownloadingChanged(bool value)
    {
        OnPropertyChanged(nameof(ShowDownloadProgress));
        OnPropertyChanged(nameof(CanDownload));
        OnPropertyChanged(nameof(HasDownloadError));
        OnPropertyChanged(nameof(HasDownloadStatus));
    }

    partial void OnDownloadProgressChanged(double value)
    {
        OnPropertyChanged(nameof(DownloadProgressPercent));
        OnPropertyChanged(nameof(DownloadProgressText));
    }

    partial void OnDownloadErrorChanged(string value) => OnPropertyChanged(nameof(HasDownloadError));

    partial void OnDownloadSavedPathChanged(string value)
    {
        OnPropertyChanged(nameof(DownloadStatusText));
        OnPropertyChanged(nameof(HasDownloadStatus));
    }

    private NodeItem? _fileNode;

    public bool IsFile => Kind == "file" && !IsDeleted && !Recalled;

    /// <summary>图片消息（kind=image，渲染为图片卡片而不是文本气泡）。</summary>
    public bool IsImage => Kind == "image" && !IsDeleted && !Recalled;

    public bool HasFile => (IsFile || IsImage) && !string.IsNullOrEmpty(FileId);

    public bool IsSystem => Kind == "system" || IsDeleted;

    public bool IsText => !IsFile && !IsImage && !IsSystem && !Recalled;

    /// <summary>是否显示为聊天气泡（撤回/系统提示显示为居中细行）。</summary>
    public bool BubbleVisible => !Recalled && !IsSystem;

    public bool IsRecalled => Recalled;

    /// <summary>相邻同发送者的撤回提示合并后的聚合文案（由分组阶段写入；null=单条）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private string? _recallAggregateText;

    /// <summary>相邻撤回提示只保留一行，其余合并行不再渲染（避免「撤回了一条消息」刷屏占行）。</summary>
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _recallRowVisible = true;

    public bool RecallVisible => IsRecalled && RecallRowVisible;

    /// <summary>撤回提示文案。</summary>
    public string RecallText => !string.IsNullOrEmpty(RecallAggregateText)
        ? RecallAggregateText!
        : IsMine ? "你撤回了一条消息" : $"{SenderLabel} 撤回了一条消息";

    partial void OnRecallAggregateTextChanged(string? value) => OnPropertyChanged(nameof(RecallText));

    partial void OnRecallRowVisibleChanged(bool value) => OnPropertyChanged(nameof(RecallVisible));

    public bool IsSending => SendState == ChatSendState.Sending;

    public bool IsFailed => SendState == ChatSendState.Failed;

    /// <summary>是否显示头像：只在连续消息的末条显示（QQ/微信式合并）。</summary>
    public bool ShowAvatar => IsGroupEnd && !IsSystem && !Recalled;

    /// <summary>对方头像（左栏）是否显示。</summary>
    public bool ShowPeerAvatar => ShowAvatar && !IsMine;

    /// <summary>自己头像（右栏）是否显示。</summary>
    public bool ShowMineAvatar => ShowAvatar && IsMine;

    /// <summary>是否可以在 2 分钟窗口内撤回（本人、已发送、非系统消息）。</summary>
    public bool CanRecall => IsMine && !IsDeleted && !Recalled && !IsSystem
        && SendState == ChatSendState.Sent && !string.IsNullOrEmpty(Id)
        && (DateTime.Now - SentAt).TotalSeconds <= 120;

    public string TimeText => SentAt.ToString("HH:mm");

    public string FileSizeText => NodeItem.FormatSize(FileSize);

    public string DeletedText => "消息已删除";

    public bool HasReadReceipt => IsMine && !Recalled && ReadReceipt.Length > 0;

    /// <summary>图片消息的可选文字说明。</summary>
    public string CaptionText => Text;

    public bool HasCaption => IsImage && !string.IsNullOrWhiteSpace(Text);

    /// <summary>系统提示 / 已删除占位显示的文字。</summary>
    public string SystemText => IsDeleted ? DeletedText : Text;

    public string SenderLabel => IsMine ? "我" : SenderName;

    /// <summary>文件/图片消息可打开预览：上传完成（有 fileId）且未撤回。</summary>
    public bool CanPreviewFile => HasFile && !IsUploading;

    /// <summary>
    /// 文件消息复用的预览节点。自己的消息直接用个人空间路径（所有者鉴权，图片消息也走这里）；
    /// 对端消息用 "chat:&lt;messageId&gt;" 约定，预览服务据此走 /api/chat/files/{messageId}/tokens。
    /// task-165：上传中的本地占位消息用 "local:&lt;绝对路径&gt;"，预览服务直接读本地字节。
    /// task-3：后端 ChatFileAccessService 已同时支持 kind=file 与 kind=image（KIND_IMAGE），
    /// 图片消息对端的缩略图/预览/下载都走同一套聊天令牌链路，不会再 404。
    /// </summary>
    /// <summary>自己发送的文件实际所在空间：工作区频道为 workspace id，私聊/未知回退 personal。</summary>
    private string OwnFileSpace => string.IsNullOrEmpty(SpaceId) || SpaceId.StartsWith("chat:", StringComparison.Ordinal)
        ? "personal"
        : SpaceId!;

    public NodeItem? FileNode
    {
        get
        {
            if (!string.IsNullOrEmpty(FileId))
            {
                return _fileNode ??= new NodeItem
                {
                    Id = FileId!,
                    Name = string.IsNullOrEmpty(FileName) ? "文件" : FileName,
                    Size = FileSize,
                    ContentType = FileContentType,
                    // 自己发的文件所在空间随会话走：工作区频道在 workspace 空间，
                    // 私聊在 personal；用错空间会 404（缩略图空白/预览失败）。
                    SpaceId = IsMine ? OwnFileSpace : ChatFileSpace.ForMessage(Id)
                };
            }

            // 本地占位：上传完成前直接渲染本地文件（图片走本地字节，文件走类型图标）。
            if (!string.IsNullOrEmpty(LocalPath))
            {
                return _fileNode ??= new NodeItem
                {
                    Id = LocalPath,
                    Name = string.IsNullOrEmpty(FileName)
                        ? System.IO.Path.GetFileName(LocalPath)
                        : FileName,
                    Size = FileSize,
                    ContentType = FileContentType,
                    SpaceId = LocalFileSpace.ForPath(LocalPath)
                };
            }

            return null;
        }
    }

    partial void OnIdChanged(string value)
    {
        _fileNode = null;
        OnPropertyChanged(nameof(FileNode));
        OnPropertyChanged(nameof(CanRecall));
    }

    partial void OnIsDeletedChanged(bool value)
    {
        OnPropertyChanged(nameof(IsFile));
        OnPropertyChanged(nameof(IsImage));
        OnPropertyChanged(nameof(HasFile));
        OnPropertyChanged(nameof(IsSystem));
        OnPropertyChanged(nameof(IsText));
        OnPropertyChanged(nameof(BubbleVisible));
        OnPropertyChanged(nameof(CanRecall));
        OnPropertyChanged(nameof(FileNode));
        OnPropertyChanged(nameof(SystemText));
        OnPropertyChanged(nameof(CanDownload));
    }

    partial void OnRecalledChanged(bool value)
    {
        OnPropertyChanged(nameof(IsRecalled));
        OnPropertyChanged(nameof(RecallText));
        OnPropertyChanged(nameof(RecallVisible));
        OnPropertyChanged(nameof(IsFile));
        OnPropertyChanged(nameof(IsImage));
        OnPropertyChanged(nameof(HasFile));
        OnPropertyChanged(nameof(IsText));
        OnPropertyChanged(nameof(BubbleVisible));
        OnPropertyChanged(nameof(CanRecall));
        OnPropertyChanged(nameof(CanPreviewFile));
        OnPropertyChanged(nameof(FileNode));
        OnPropertyChanged(nameof(HasReadReceipt));
        OnPropertyChanged(nameof(CanDownload));
        _fileNode = null;
    }

    partial void OnReadReceiptChanged(string value)
    {
        OnPropertyChanged(nameof(HasReadReceipt));
        OnPropertyChanged(nameof(ReadReceiptText));
    }

    /// <summary>已读回执文案（绑定用别名）。</summary>
    public string ReadReceiptText => ReadReceipt;

    partial void OnSendStateChanged(ChatSendState value)
    {
        OnPropertyChanged(nameof(IsSending));
        OnPropertyChanged(nameof(IsFailed));
        OnPropertyChanged(nameof(CanRecall));
    }
}

/// <summary>
/// 聊天文件预览节点约定：<c>SpaceId = "chat:&lt;messageId&gt;"</c>。
/// 预览服务据此调用 <c>POST /api/chat/files/{messageId}/tokens</c> 取短时流转 URL，
/// 发送方与接收方走同一路径（不再依赖文件 owner 的短签接口）。
/// </summary>
public static class ChatFileSpace
{
    public const string Prefix = "chat:";

    public static string ForMessage(string messageId) => Prefix + messageId;

    public static bool IsChatFile(NodeItem node) => node.SpaceId.StartsWith(Prefix, StringComparison.Ordinal);

    public static string MessageId(NodeItem node) => node.SpaceId[Prefix.Length..];
}

/// <summary>
/// task-165 本地附件占位节点约定：<c>SpaceId = "local:&lt;本地绝对路径&gt;"</c>。
/// 附件在上传完成前就用它渲染缩略图/图片预览，避免「发送中」气泡空白或先显示假图。
/// </summary>
public static class LocalFileSpace
{
    public const string Prefix = "local:";

    public static string ForPath(string localPath) => Prefix + localPath;

    public static bool IsLocal(NodeItem node) => node.SpaceId.StartsWith(Prefix, StringComparison.Ordinal);

    public static string Path(NodeItem node) => node.SpaceId[Prefix.Length..];
}

/// <summary>一页消息（升序）。</summary>
public sealed class ChatMessagePage
{
    public IReadOnlyList<ChatMessage> Messages { get; init; } = [];

    public bool HasMore { get; init; }

    /// <summary>非空表示这一页加载失败（界面必须显示可重试的错误，而不是空态）。</summary>
    public string? Error { get; init; }

    public static ChatMessagePage Failure(string error) => new() { Error = error };
}

/// <summary>发送一条消息的结果。</summary>
public sealed class ChatSendResult
{
    public bool Success { get; init; }

    public string? Error { get; init; }

    public ChatMessage? Message { get; init; }

    public static ChatSendResult Failure(string error) => new() { Success = false, Error = error };
}

/// <summary>会话成员的已读位置（GET /api/chat/conversations/{id}/read-state）。</summary>
public sealed class ChatReadStateMember
{
    public required string UserId { get; init; }
    public string Nickname { get; init; } = "";
    public string? LastReadMessageId { get; init; }
    public DateTime? LastReadAt { get; init; }
}

/// <summary>会话的已读回执集合。</summary>
public sealed class ChatReadState
{
    public required string ConversationId { get; init; }
    public IReadOnlyList<ChatReadStateMember> Members { get; init; } = [];
}

/// <summary>会话内搜索结果（GET /api/chat/conversations/{id}/messages/search）。</summary>
public sealed class ChatSearchResult
{
    public required string Id { get; init; }
    public string Body { get; init; } = "";
    public string SenderName { get; init; } = "";
    public DateTime SentAt { get; init; } = DateTime.Now;
    public bool IsMine { get; init; }

    public string TimeText => SentAt.Date == DateTime.Today ? SentAt.ToString("HH:mm") : SentAt.ToString("MM-dd HH:mm");
    public string SenderLabel => IsMine ? "我" : (string.IsNullOrEmpty(SenderName) ? "对方" : SenderName);
}

/// <summary>待处理事项（收件箱 / 邀请 / 审批）。</summary>
public sealed class InboxItem
{
    public required string Id { get; init; }
    public required string Title { get; init; }
    public required string Detail { get; init; }
    public InboxKind Kind { get; init; }
    public DateTime At { get; init; } = DateTime.Now;

    public Material.Icons.MaterialIconKind Icon => Kind switch
    {
        InboxKind.TransferRequest => Material.Icons.MaterialIconKind.Download,
        InboxKind.WorkspaceInvite => Material.Icons.MaterialIconKind.AccountGroup,
        InboxKind.SystemNotice => Material.Icons.MaterialIconKind.AlertCircleOutline,
        _ => Material.Icons.MaterialIconKind.FileDocument
    };

    public string TimeText => At.Date == DateTime.Today ? At.ToString("HH:mm") : At.ToString("MM-dd");
}

public enum InboxKind { TransferRequest, WorkspaceInvite, SystemNotice }

/// <summary>本地存储配额概览。</summary>
/// <summary>
/// 回收站条目（GET /api/trash 的列表项）。后端只返回顶层条目，
/// 目录用 <see cref="ItemCount"/> 表示子树文件数；时间字段为 epoch 毫秒。
/// </summary>
public sealed record TrashItem(
    string Id,
    string FileName,
    long FileSize,
    bool IsDirectory,
    string OriginalPath,
    DateTimeOffset DeletedAt,
    DateTimeOffset? ExpiresAt,
    long ItemCount);

/// <summary>
/// 回收站列表行（可选中）。展示文本在这里拼好，XAML 直接绑定，避免 View 里写逻辑。
/// </summary>
public sealed partial class TrashRow : CommunityToolkit.Mvvm.ComponentModel.ObservableObject
{
    public TrashRow(TrashItem item) => Item = item;

    public TrashItem Item { get; }

    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _isSelected;

    public string Id => Item.Id;

    public string FileName => Item.FileName;

    public bool IsDirectory => Item.IsDirectory;

    public string OriginalPathText => string.IsNullOrEmpty(Item.OriginalPath) ? "（原路径未知）" : Item.OriginalPath;

    public Material.Icons.MaterialIconKind Icon =>
        FileVisuals.Icon(FileVisuals.Classify(Item.FileName, Item.IsDirectory ? NodeKind.Folder : NodeKind.File));

    public Avalonia.Media.IBrush IconBrush =>
        FileVisuals.Brush(FileVisuals.Classify(Item.FileName, Item.IsDirectory ? NodeKind.Folder : NodeKind.File));

    public Avalonia.Media.IBrush IconBackground =>
        FileVisuals.Background(FileVisuals.Classify(Item.FileName, Item.IsDirectory ? NodeKind.Folder : NodeKind.File));

    public string SizeText => Item.IsDirectory ? $"{Item.ItemCount} 项" : NodeItem.FormatSize(Item.FileSize);

    /// <summary>删除时间：绝对时间（2026-10-24 12:25），与「保留期」列风格一致（task-113）。</summary>
    public string DeletedText => Bond.Services.TimeText.Local(Item.DeletedAt);

    /// <summary>
    /// 保留期文案（task-113）：
    /// <c>剩余 3 天（2026-05-12 09:30 到期）</c> / <c>剩余 11 小时（… 到期）</c> /
    /// 已过期 <c>即将清理</c>；<see cref="TrashItem.ExpiresAt"/> 缺失或为 0 → <c>不限期保留</c>。
    /// </summary>
    public string RemainingText
    {
        get
        {
            if (Item.ExpiresAt is not { } expires) return "不限期保留";
            var left = expires - DateTimeOffset.Now;
            if (left <= TimeSpan.Zero) return "即将清理";
            var deadline = Bond.Services.TimeText.Local(expires);
            return left.TotalHours < 48
                ? $"剩余 {Math.Ceiling(left.TotalHours):0} 小时（{deadline} 到期）"
                : $"剩余 {Math.Ceiling(left.TotalDays):0} 天（{deadline} 到期）";
        }
    }

    /// <summary>已过期：界面用红色强调「即将清理」。</summary>
    public bool IsExpired => Item.ExpiresAt is { } expires && expires <= DateTimeOffset.Now;

    /// <summary>悬停补充：明确到期时刻 / 不限期语义。</summary>
    public string RemainingTip => Item.ExpiresAt is { } expires
        ? $"到期后自动清理；到期时间 {Bond.Services.TimeText.Local(expires)}（本机时区）"
        : "该文件不会被自动清理";
}

public sealed class StorageQuota
{
    public long UsedBytes { get; init; }
    public long TotalBytes { get; init; }

    public double Ratio => TotalBytes <= 0 ? 0 : Math.Clamp((double)UsedBytes / TotalBytes, 0, 1);
    public string UsedText => NodeItem.FormatSize(UsedBytes);
    public string TotalText => NodeItem.FormatSize(TotalBytes);
    public string RatioText => QuotaFormat.Percent(UsedBytes, TotalBytes);
}

/// <summary>
/// 一条分享链接（服务端 <c>/api/shares</c>）。时间字段后端返回带 <c>Z</c> 的 UTC，这里统一用
/// <see cref="DateTimeOffset"/>，界面一律按本地时间显示。
/// </summary>
public sealed class ShareLink
{
    public string Id { get; init; } = string.Empty;

    /// <summary>匿名落地页路径用的 43 字符 token。</summary>
    public string Token { get; init; } = string.Empty;

    /// <summary>完整分享 URL（后端直接给出绝对地址）。</summary>
    public string Url { get; init; } = string.Empty;

    public string FileName { get; init; } = string.Empty;

    public long FileSize { get; init; }

    /// <summary>
    /// task-183：该分享是否指向文件夹。为 true 时匿名 <c>/s/{token}/download</c> 返回流式 ZIP，
    /// 落地页展示首层子项；文件分享恒为 false（历史链接该字段可能缺失，按 false 处理）。
    /// </summary>
    public bool IsDirectory { get; init; }

    public bool RequiresCode { get; init; }

    /// <summary>提取码（创建成功后仅在本次响应里回显；列表里通常为空）。</summary>
    public string? Code { get; init; }

    /// <summary>过期时间；null 表示永久。</summary>
    public DateTimeOffset? ExpiresAt { get; init; }

    /// <summary>最大下载次数；0 表示不限。</summary>
    public int MaxDownloads { get; init; }

    public int DownloadCount { get; init; }

    /// <summary>服务端状态：active / revoked / expired 等。</summary>
    public string Status { get; init; } = "active";

    public DateTimeOffset CreatedAt { get; init; }

    /// <summary>最近一次匿名下载时间；从未下载过为 null（task-163 契约 lastAccessAtMillis）。</summary>
    public DateTimeOffset? LastAccessAt { get; init; }

    // ── 展示用派生属性（界面只绑定这些，不改数据） ──

    /// <summary>条目类型文案：文件夹分享下载为 ZIP，「我的分享」用它显示文件夹徽标。</summary>
    public string TargetKindText => IsDirectory ? "文件夹" : "文件";

    /// <summary>下载入口文案：文件夹是流式 ZIP，界面上明确写「下载全部（ZIP）」。</summary>
    public string DownloadActionText => IsDirectory ? "下载全部（ZIP）" : "下载";

    /// <summary>
    /// 大小文案：文件夹后端创建时 fileSize=0（真实体积以下行 ZIP 计），不能显示「0 B」。
    /// </summary>
    public string SizeText => IsDirectory ? "ZIP 打包" : NodeItem.FormatSize(FileSize);

    public string CreatedText => CreatedAt.ToLocalTime().ToString("yyyy-MM-dd HH:mm");

    public string LastAccessText => LastAccessAt is { } t
        ? t.ToLocalTime().ToString("yyyy-MM-dd HH:mm")
        : "—";

    public string ExpiresText => ExpiresAt is { } e
        ? e.ToLocalTime().ToString("yyyy-MM-dd HH:mm")
        : "永久";

    /// <summary>列表徽标文案：永久链接（expiresAt=null）显示「永久有效」。</summary>
    public string ExpiresBadgeText => ExpiresAt is null
        ? "永久有效"
        : $"到期 {ExpiresAt.Value.ToLocalTime():yyyy-MM-dd HH:mm}";

    public string DownloadText => MaxDownloads > 0
        ? $"{DownloadCount} / {MaxDownloads}"
        : $"{DownloadCount} · 不限";

    /// <summary>列表「提取码」列文案：已设置只显示占位符（列表不返回明文），未设置为「公开」。</summary>
    public string CodeText => RequiresCode ? "••••" : "公开";

    public bool IsRevoked => string.Equals(Status, "revoked", StringComparison.OrdinalIgnoreCase);

    public bool IsExpired
    {
        get
        {
            if (string.Equals(Status, "expired", StringComparison.OrdinalIgnoreCase)) return true;
            return ExpiresAt is { } e && e <= DateTimeOffset.UtcNow;
        }
    }

    public string StatusText => IsRevoked ? "已撤销" : IsExpired ? "已过期" : "正常";

    /// <summary>状态徽标配色：正常=强调色、已撤销=危险色、已过期=次要色。</summary>
    public string StatusKind => IsRevoked ? "revoked" : IsExpired ? "expired" : "active";

    public bool IsActive => !IsRevoked && !IsExpired;
}

/// <summary>
/// 转存（服务端复制）结果。失败时 <see cref="Message"/> 为后端原文（不自造文案），
/// <see cref="Code"/> 为 HTTP/业务码，供界面按 413/400/404/403 分支。
/// </summary>
public sealed class CopyResult
{
    public bool Ok { get; init; }

    public int Code { get; init; }

    public string Message { get; init; } = string.Empty;

    /// <summary>复制出的文件大小（字节）；仅成功时有意义。</summary>
    public long SizeBytes { get; init; }

    public static CopyResult Success(long sizeBytes, string message = "")
        => new() { Ok = true, Code = 200, Message = message, SizeBytes = sizeBytes };

    public static CopyResult Failed(int code, string message)
        => new() { Ok = false, Code = code, Message = message };
}

// ═══════════════════════════════════════════════════════════════
//  task-7：系统公告（GET /api/announcements）
// ═══════════════════════════════════════════════════════════════

/// <summary>公告级别（后端 level 字段：info / warn / critical）。</summary>
public enum AnnouncementLevel
{
    Info = 0,
    Warn = 1,
    Critical = 2
}

/// <summary>
/// 一条系统公告。<see cref="IsRead"/> 是可变的：点击公告调
/// <c>POST /api/announcements/{id}/read</c> 成功后本地置为已读，界面立即取消加粗/红点。
/// </summary>
public sealed class AnnouncementItem : CommunityToolkit.Mvvm.ComponentModel.ObservableObject
{
    public required string Id { get; init; }

    public required string Title { get; init; }

    /// <summary>纯文本正文，可能含换行（界面用 TextWrapping 保留换行）。</summary>
    public string Content { get; init; } = "";

    public AnnouncementLevel Level { get; init; } = AnnouncementLevel.Info;

    /// <summary>发布时间（Unix 毫秒；后端可见列表一定有值）。</summary>
    public long? PublishedAtMillis { get; init; }

    /// <summary>过期时间（Unix 毫秒；null = 永不过期）。</summary>
    public long? ExpiresAtMillis { get; init; }

    private bool _isRead;

    public bool IsRead
    {
        get => _isRead;
        set
        {
            if (!SetProperty(ref _isRead, value)) return;
            OnPropertyChanged(nameof(IsUnread));
        }
    }

    public bool IsUnread => !IsRead;

    // ── 展示用派生属性（XAML 直接绑定，避免转换器）──

    public bool IsInfo => Level == AnnouncementLevel.Info;

    public bool IsWarn => Level == AnnouncementLevel.Warn;

    public bool IsCritical => Level == AnnouncementLevel.Critical;

    public string LevelText => Level switch
    {
        AnnouncementLevel.Critical => "重要",
        AnnouncementLevel.Warn => "警告",
        _ => "提示"
    };

    /// <summary>本机时区时间；当天只显示时分，跨天显示月-日 时:分。</summary>
    public string TimeText
    {
        get
        {
            if (PublishedAtMillis is not > 0) return "";
            var local = DateTimeOffset.FromUnixTimeMilliseconds(PublishedAtMillis.Value).ToLocalTime();
            return local.Date == DateTimeOffset.Now.Date
                ? local.ToString("今天 HH:mm")
                : local.ToString("MM-dd HH:mm");
        }
    }
}

