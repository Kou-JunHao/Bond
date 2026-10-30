using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Threading;
using System.Threading.Tasks;
using Bond.Models;

namespace Bond.Services;

// ═══════════════════════════════════════════════════════════════
//  服务契约层
//  ── 全部以接口暴露，ViewModel 只依赖接口：可替换、可 mock、可测。
//     这是对旧版 `static class ServiceProvider` 的直接纠正。
// ═══════════════════════════════════════════════════════════════

/// <summary>通知的语义级别（旧版用字符串关键字猜级别，这里显式传递）。</summary>
public enum NoticeKind
{
    Info,
    Success,
    Warning,
    Error
}

/// <summary>跨窗口通知中心：悬浮球与主窗口共享同一个实例。</summary>
public interface INotificationCenter
{
    event EventHandler<NoticeEventArgs>? Notified;

    void Notify(string message, NoticeKind kind = NoticeKind.Info);
}

public sealed class NoticeEventArgs(string message, NoticeKind kind) : EventArgs
{
    public string Message { get; } = message;
    public NoticeKind Kind { get; } = kind;
}

/// <summary>窗口协调器：主窗口 ↔ 悬浮球互操作的唯一入口。</summary>
public interface IWindowCoordinator
{
    void ShowMain();

    void HideMain();

    void ToggleMain();

    bool IsMainVisible { get; }

    /// <summary>让悬浮球展开为快捷面板。</summary>
    void ExpandBall();

    void CollapseBall();

    /// <summary>主窗口最小化到托盘（只留悬浮球）。</summary>
    void MinimizeToBall();

    void Quit();

    event EventHandler? MainVisibilityChanged;
}

/// <summary>
/// task-123：一页服务端搜索结果。<see cref="NextCursor"/> 不透明，原样回传给下一页。
/// </summary>
/// <param name="Items">本页结果（已映射成 <see cref="NodeItem"/>，携带 <c>PathHint/FullPath</c>）。</param>
/// <param name="NextCursor">下一页游标；<see cref="HasMore"/> 为 false 时通常为 null。</param>
/// <param name="HasMore">是否还有下一页。</param>
/// <param name="Total">服务端命中的总数（不随分页变化）。</param>
public sealed record FileSearchPage(IReadOnlyList<NodeItem> Items, string? NextCursor, bool HasMore, long Total);

/// <summary>task-123：一次搜索调用的结果（成功带分页，失败带可展示原文）。</summary>
/// <param name="Retryable">
/// task-127：失败是否值得「重试」——后端/网络来源为 true；本地校验（关键词空/超长）为 false。
/// </param>
public sealed record FileSearchOutcome(FileSearchPage? Page, string? Error, bool Retryable = false)
{
    public bool IsOk => Page is not null;
}

/// <summary>
/// 文件页数据源：默认展示**个人空间**（<c>/api/files/**</c>），
/// 也可切到我加入的团队工作区（<c>/api/workspaces/{id}/files</c>）。
/// </summary>
public interface IFileService
{
    ObservableCollection<NodeItem> Nodes { get; }

    ObservableCollection<NodeItem> Recent { get; }

    /// <summary>可切换的空间：个人空间（默认）+ 我加入的团队工作区。</summary>
    ObservableCollection<FileSpace> Spaces { get; }

    /// <summary>当前空间；未加载完成时为 null（此时按个人空间处理）。</summary>
    FileSpace? CurrentSpace { get; }

    bool IsPersonalSpace { get; }

    StorageQuota Quota { get; }

    /// <summary>当前目录路径，形如 <c>/</c> 或 <c>/设计稿/2026</c>。</summary>
    string CurrentPath { get; }

    /// <summary>正在拉取列表（首屏骨架 / 刷新进度条）。</summary>
    bool IsLoading { get; }

    /// <summary>最近一次加载失败的说明；成功时清空。</summary>
    string? LastError { get; }

    /// <summary>正在拉取空间列表（个人空间 + 团队工作区）。</summary>
    bool IsSpacesLoading { get; }

    /// <summary>空间列表加载失败的说明（与文件列表错误分开，避免把失败画成「没有空间」）。</summary>
    string? LastSpacesError { get; }

    /// <summary>重新拉取空间列表（错误态「重试」用）。</summary>
    Task RefreshSpacesAsync();

    /// <summary>节点列表、当前空间、加载状态或错误状态发生变化。</summary>
    event EventHandler? Changed;

    void EnterFolder(NodeItem folder);

    void GoUp();

    /// <summary>跳到指定路径（面包屑回跳）。</summary>
    void NavigateTo(string path);

    /// <summary>切换文件页所依附的空间，并把路径复位到根目录。</summary>
    Task SelectSpaceAsync(FileSpace space);

    Task UploadAsync(IReadOnlyList<string> localPaths, CancellationToken ct = default);

    /// <summary>新建文件夹（task-120）：成功标志 + 后端原始错误文案。</summary>
    Task<(bool Ok, string? Error)> CreateFolderAsync(string parentPath, string name,
        CancellationToken ct = default);

    /// <summary>重新加载当前目录（工具栏刷新 / 回收站操作后回刷）。</summary>
    Task ReloadListAsync();

    /// <summary>
    /// task-123：服务端搜索（<c>GET /api/files/search</c>）。
    /// <paramref name="workspaceId"/> 为 null 时搜「个人空间 + 我是成员的工作区」；
    /// <paramref name="cursor"/> 为上一页返回的 <see cref="FileSearchPage.NextCursor"/>。
    /// 关键词为空/超长不发请求，直接返回错误文案（后端会 400）。
    /// </summary>
    Task<FileSearchOutcome> SearchFilesAsync(string query, string? workspaceId, int limit, string? cursor);

    /// <summary>
    /// 单文件上传（悬浮球拖拽 / 快速发文件用）：带真实进度回调，成功后返回 true。
    /// 上传到个人空间根目录——后端没有「新建目录」接口，无法自动创建「快速上传」目录。
    /// </summary>
    Task<bool> UploadQuickAsync(string localPath, IProgress<double>? progress = null,
        CancellationToken ct = default);

    /// <summary>计费权益（本月流量剩余 / 空间用量 / 限速状态）。失败返回 null，界面隐藏该行。</summary>
    Task<EntitlementsInfo?> GetEntitlementsAsync(CancellationToken ct = default);

    Task DeleteAsync(NodeItem node);

    /// <summary>
    /// 创建分享链接（POST /api/shares）。<paramref name="expiresDays"/> 为 null 表示永久。
    /// 失败返回后端原始 message，界面直接展示，不自造文案。
    /// </summary>
    Task<(ShareLink? Share, string? Error)> CreateShareAsync(NodeItem file, bool requiresCode,
        string? code, int? expiresDays, int maxDownloads = 0, CancellationToken ct = default);

    /// <summary>我创建的分享链接（GET /api/shares），按创建时间倒序。</summary>
    Task<(IReadOnlyList<ShareLink>? Shares, string? Error)> GetSharesAsync(CancellationToken ct = default);

    /// <summary>撤销分享（DELETE /api/shares/{id}）；成功返回 null，失败返回后端 message。</summary>
    Task<string?> RevokeShareAsync(string shareId, CancellationToken ct = default);

    /// <summary>
    /// 编辑分享（PATCH /api/shares/{id}，task-163）：字段缺省=不改、显式 null=清空、有值=设置。
    /// <paramref name="changeExtractCode"/>/<paramref name="changeExpiresAt"/>/<paramref name="changeMaxDownloads"/>
    /// 表示该字段是否参与本次 PATCH；失败返回带后端原文的可读文案。
    /// </summary>
    Task<(ShareLink? Share, string? Error)> UpdateShareAsync(
        string shareId,
        bool changeExtractCode,
        string? extractCode,
        bool changeExpiresAt,
        long? expiresAtMillis,
        bool changeMaxDownloads,
        int? maxDownloads,
        CancellationToken ct = default);

    /// <summary>把云端文件下载到本地目录，返回保存路径；文件夹或不支持时返回 null。</summary>
    Task<string?> DownloadAsync(NodeItem node, string targetDirectory);

    Task RenameAsync(NodeItem node, string newName);

    /// <summary>
    /// 浏览目标空间的文件夹（转存目标选择器用）。<paramref name="space"/> 为 null 时浏览个人空间根。
    /// 失败返回后端 message；成功时只返回文件夹节点。
    /// </summary>
    Task<(IReadOnlyList<NodeItem>? Folders, string? Error)> ListFoldersAsync(FileSpace? space, string path,
        CancellationToken ct = default);

    /// <summary>
    /// 转存（服务端复制，不消耗下载流量）：个人空间 POST /api/files/{id}/copy；
    /// 工作区 POST /api/workspaces/{ws}/files/{id}/copy，body { targetWorkspaceId, targetParentPath, newName }。
    /// </summary>
    Task<CopyResult> CopyAsync(NodeItem source, string? targetWorkspaceId, string targetParentPath,
        string? newName, CancellationToken ct = default);

    /// <summary>
    /// 移动（task-166）：个人空间 POST /api/files/{id}/move；工作区
    /// POST /api/workspaces/{ws}/files/{id}/move，body { targetWorkspaceId, targetParentPath, newName }。
    /// 同空间移动只改路径，不复制、不消耗流量；目标同名 409、目标目录不存在 404 由后端原文回传。
    /// </summary>
    Task<CopyResult> MoveAsync(NodeItem source, string? targetWorkspaceId, string targetParentPath,
        string? newName, CancellationToken ct = default);
}

/// <summary>
/// 上传队列（task-42）：分片上传（v7 /api/files/uploads）+ 秒传 + 断点续传 + 逐文件真实进度。
/// <see cref="Uploads"/> 里的进度只来自本机真实读盘/网络字节。
/// </summary>
public interface IUploadService
{
    ObservableCollection<UploadTaskItem> Uploads { get; }

    /// <summary>队列内容或任一任务状态变化时触发（UI 刷新用）。</summary>
    event EventHandler? UploadsChanged;

    /// <summary>把本地文件加入上传队列（对应图标的父路径由服务内部决定）。</summary>
    void Enqueue(IReadOnlyList<string> localPaths);

    /// <summary>取消一条正在上传/排队的任务；未完成分片在服务端 abort。</summary>
    void Cancel(UploadTaskItem task);

    /// <summary>续传一条 Paused/Failed 任务（从已传分片继续）。</summary>
    Task ResumeAsync(UploadTaskItem task);

    /// <summary>从队列移除一条已结束的任务。</summary>
    void Remove(UploadTaskItem task);
}

/// <summary>传输队列。</summary>
public interface ITransferService
{
    ObservableCollection<TransferItem> Items { get; }

    bool HasActive { get; }

    double OverallProgress { get; }

    /// <summary>正在向服务端刷新任务列表。</summary>
    bool IsLoading { get; }

    /// <summary>最近一次传输列表刷新失败的原因；成功时清空。</summary>
    string? LastError { get; }

    event EventHandler? Changed;

    Task RefreshAsync();

    Task SendToAsync(DevicePeer peer, IReadOnlyList<string> localPaths);

    /// <summary>取消服务端传输任务（POST /api/transfer/tasks/{id}/cancel）；成功返回 true。</summary>
    Task<bool> CancelAsync(TransferItem item);

    /// <summary>
    /// 重试失败/已取消的任务：优先 POST /api/transfer/tasks/{id}/retry；
    /// 服务端记录不可重试时，若本机仍持有源文件则重建任务。
    /// </summary>
    Task<bool> RetryAsync(TransferItem item);

    /// <summary>客户端下载会话（task-14）。上传会话通过 <see cref="IUploadService.Uploads"/> 提供。</summary>
    ObservableCollection<TransferSession> Sessions { get; }

    /// <summary>会话集合或会话状态变化时触发（下载建立/推进/结束）。</summary>
    event EventHandler? SessionsChanged;

    /// <summary>取消一次正在进行的下载会话。</summary>
    void CancelSession(TransferSession session);

    /// <summary>收件箱：待接收 / 进行中的传输任务（服务端 /api/transfer/tasks 中我是接收方）。</summary>
    Task<IReadOnlyList<IncomingTaskInfo>> GetIncomingAsync(CancellationToken ct = default);

    /// <summary>接收：POST /api/transfer/tasks/{id}/complete。</summary>
    Task<bool> AcceptIncomingAsync(string id, CancellationToken ct = default);

    /// <summary>拒绝/取消：DELETE /api/transfer/tasks/{id}。</summary>
    Task<bool> RejectIncomingAsync(string id, CancellationToken ct = default);

    /// <summary>接收方接受任务（task-94：POST /api/transfer/tasks/{id}/accept，PENDING → UPLOADING）。</summary>
    Task<bool> AcceptAsync(TransferItem item, CancellationToken ct = default);

    /// <summary>接收方拒绝任务（task-94：POST /api/transfer/tasks/{id}/reject，PENDING → REJECTED）。</summary>
    Task<bool> RejectAsync(TransferItem item, CancellationToken ct = default);
}

/// <summary>收件箱里的一条待处理传输任务。</summary>
public sealed record IncomingTaskInfo(string Id, string FileName, long Size, string Sender, int Status);

/// <summary>计费权益快照（GET /api/entitlements/me）。</summary>
/// <summary>
/// 回收站批量操作结果（后端 207 时逐条返回失败原因）。
/// </summary>
public sealed class TrashOpResult
{
    public IReadOnlyList<string> Succeeded { get; init; } = [];

    public IReadOnlyList<(string Id, string Reason)> Failed { get; init; } = [];

    public bool Ok => Failed.Count == 0;
}

/// <summary>回收站（GET/POST /api/trash*）。</summary>
public interface ITrashService
{
    /// <summary>顶层条目列表。失败时 <c>Items</c> 为空且 <c>Error</c> 有文案（区分“空”和“失败”）。</summary>
    Task<(IReadOnlyList<TrashItem> Items, string? Error)> GetTrashAsync(CancellationToken ct = default);

    /// <summary>恢复；207 部分失败逐条返回原因。</summary>
    Task<TrashOpResult> RestoreAsync(IReadOnlyList<string> ids, CancellationToken ct = default);

    /// <summary>彻底删除；207 部分失败逐条返回原因。</summary>
    Task<TrashOpResult> PurgeAsync(IReadOnlyList<string> ids, CancellationToken ct = default);

    /// <summary>清空；返回清空条数与错误文案。</summary>
    Task<(int Purged, string? Error)> EmptyAsync(CancellationToken ct = default);
}

public sealed class EntitlementsInfo
{
    /// <summary>档位展示名（后端 planName，如「免费版」）；后端没返回时为 null，界面不得编造。</summary>
    public string? PlanName { get; init; }

    public long RelayBytesRemaining { get; init; }

    public long StorageBytes { get; init; }

    public long StorageLimitBytes { get; init; }

    public bool IsThrottled { get; init; }

    /// <summary>回收站保留天数（后端字段 trashRetentionDays；0 = 不限期；null = 后端未返回，按“过期后自动清理”展示）。</summary>
    public int? TrashRetentionDays { get; init; }
}

/// <summary>
/// WebDAV 挂载（专用密码 + 启用状态探测）。
/// 明文密码只经 <see cref="CreateWebDavPasswordAsync"/> 返回一次，界面关闭即丢弃，绝不落盘。
/// </summary>
public interface IWebDavService
{
    /// <summary>专用密码列表（GET /api/webdav/passwords），不含 hash / 明文。</summary>
    Task<(IReadOnlyList<Bond.Models.WebDavPasswordItem>? Items, string? Error)> GetWebDavPasswordsAsync(
        CancellationToken ct = default);

    /// <summary>生成专用密码（POST /api/webdav/passwords），返回一次性明文。</summary>
    Task<(string? Password, Bond.Models.WebDavPasswordItem? Item, string? Error)> CreateWebDavPasswordAsync(
        string name, CancellationToken ct = default);

    /// <summary>吊销专用密码（DELETE /api/webdav/passwords/{id}）；成功 null，失败后端 message。</summary>
    Task<string?> RevokeWebDavPasswordAsync(string id, CancellationToken ct = default);

    /// <summary>
    /// 探测 /dav/ 是否启用：OPTIONS 200/401 → true；404 → false（管理员未开启）；其它/网络异常 → null（未知，不误报）。
    /// </summary>
    Task<bool?> ProbeWebDavAsync(CancellationToken ct = default);
}

/// <summary>局域网 / 云端设备。</summary>
public interface IDeviceService
{
    ObservableCollection<DevicePeer> Peers { get; }

    bool IsScanning { get; }

    /// <summary>最近一次设备刷新失败的原因；成功时清空。</summary>
    string? LastError { get; }

    /// <summary>本机在服务端的设备行 id；未注册 / 被移除时为 null。</summary>
    string? ServerDeviceId { get; }

    Task RefreshAsync();

    /// <summary>
    /// 移除一台设备（DELETE /api/devices/{id}）。
    /// 成功返回 null，失败返回可以直接展示给用户的原因。
    /// </summary>
    Task<string?> RemoveAsync(DevicePeer peer);

    event EventHandler? Changed;
}

/// <summary>工作区。</summary>
public interface IWorkspaceService
{
    ObservableCollection<WorkspaceItem> Workspaces { get; }

    bool IsLoading { get; }

    string? LastError { get; }

    /// <summary>重新拉取工作区列表（含 memberCount/fileCount/quota）。</summary>
    Task RefreshAsync();

    /// <summary>创建工作区（名称 + 可选描述）。</summary>
    Task CreateAsync(string name, string? description = null);

    /// <summary>重命名 / 改描述（PUT /api/workspaces/{id}，owner 才能调用）。</summary>
    Task UpdateAsync(WorkspaceItem workspace, string name, string? description);

    /// <summary>删除工作区（owner 才能调用；后端会清理成员/文件/邀请）。</summary>
    Task DeleteAsync(WorkspaceItem workspace);

    /// <summary>成员列表（GET /api/workspaces/{id}/members）。</summary>
    Task<IReadOnlyList<WorkspaceMemberItem>> GetMembersAsync(WorkspaceItem workspace);

    /// <summary>移除成员（DELETE /api/workspaces/{id}/members/{userId}）。</summary>
    Task RemoveMemberAsync(WorkspaceItem workspace, WorkspaceMemberItem member);

    /// <summary>调整成员角色（PUT /api/workspaces/{id}/members/{userId}，role：1=管理员 2=成员；owner 才能调）。</summary>
    Task SetMemberRoleAsync(WorkspaceItem workspace, WorkspaceMemberItem member, int role);

    /// <summary>生成邀请链接（POST /api/workspaces/{id}/invite/link，返回可复制 URL）。</summary>
    Task<string?> GenerateInviteLinkAsync(WorkspaceItem workspace);

    /// <summary>邀请好友（POST /api/workspaces/{id}/invite/friend，按用户 ID）。</summary>
    Task InviteFriendAsync(WorkspaceItem workspace, string userIdInput);

    /// <summary>待处理邀请（GET /api/workspaces/invitations）。</summary>
    Task<IReadOnlyList<WorkspaceInvitation>> GetInvitationsAsync();

    /// <summary>接受邀请（POST /api/workspaces/invitations/{invId}/accept）。</summary>
    Task AcceptInvitationAsync(WorkspaceInvitation invitation);

    /// <summary>拒绝邀请（POST /api/workspaces/invitations/{invId}/reject）。</summary>
    Task RejectInvitationAsync(WorkspaceInvitation invitation);

    /// <summary>
    /// 校验邀请链接/邀请码（GET /api/invite/{token}，匿名可读）。
    /// 客户端「加入工作区」弹窗先调它预览工作区名/邀请人/有效期，再确认加入。
    /// </summary>
    Task<WorkspaceInvitePreview> VerifyInviteLinkAsync(string token);

    /// <summary>
    /// 通过邀请链接加入工作区（POST /api/invite/{token}/accept，需登录）。
    /// 成功后实现方负责刷新工作区列表；失败抛 <see cref="InvalidOperationException"/> 并带后端 message。
    /// </summary>
    Task AcceptInviteLinkAsync(string token);

    // ── task-8：成员文件级 ACL（owner/admin 管理；成员本人可读自己的有效范围） ──

    /// <summary>
    /// 读取某成员的文件访问范围（GET /api/workspaces/{id}/members/{userId}/acl，需 owner/admin）。
    /// <c>accessMode=all</c> 时 Grants 为空。失败抛 <see cref="InvalidOperationException"/> 并带后端原文。
    /// </summary>
    Task<WorkspaceMemberAcl> GetMemberAclAsync(WorkspaceItem workspace, WorkspaceMemberItem member,
        CancellationToken ct = default);

    /// <summary>
    /// 整包替换某成员的访问范围（PUT 同路径）。<paramref name="accessMode"/> 为 <c>all</c> 或 <c>restricted</c>；
    /// restricted 时未出现在 <paramref name="grants"/> 里的旧授权会被服务端删除。返回服务端归一化后的视图
    /// （路径规范化、canWrite⇒canRead、重复授权合并后的结果）。
    /// </summary>
    Task<WorkspaceMemberAcl> SetMemberAclAsync(WorkspaceItem workspace, WorkspaceMemberItem member,
        string accessMode, IReadOnlyList<WorkspaceAclGrant> grants, CancellationToken ct = default);

    /// <summary>
    /// 本人在该工作区的有效范围（GET /api/workspaces/{id}/acl/me，任意成员）。
    /// owner/admin 返回 all；restricted 时客户端据此提示「只能访问授权范围」。
    /// </summary>
    Task<WorkspaceMemberAcl> GetMyAclAsync(WorkspaceItem workspace, CancellationToken ct = default);

    /// <summary>
    /// 列出工作区某目录下的子项（文件夹 + 文件，目录在前），供 ACL 授权选择器浏览。
    /// 失败返回可直接展示的后端原文（restricted 成员进入未授权目录会拿到 404「目录不存在」）。
    /// </summary>
    Task<(IReadOnlyList<NodeItem>? Entries, string? Error)> ListAclEntriesAsync(WorkspaceItem workspace,
        string path, CancellationToken ct = default);

    event EventHandler? Changed;
}

/// <summary>聊天（REST + WebSocket 实时通道）。</summary>
public interface IChatService
{
    /// <summary>
    /// 会话列表（按最后消息时间倒序；随推送实时更新）。
    /// 引用整体替换：一次刷新/推送只换一次实例，绝不对绑定集合逐项增删或移动。
    /// </summary>
    IReadOnlyList<ChatThread> Threads { get; }

    /// <summary>某会话已加载的消息集合（升序，含日期分隔标记和乐观发送的消息）。</summary>
    ObservableCollection<ChatMessage> MessagesFor(string conversationId);

    /// <summary>实时通道状态（顶部细条 / 轮询降级判断）。</summary>
    ChatConnectionState ConnectionState { get; }

    /// <summary>已登录且接口可用；未登录为 false（界面显示未登录态而不是空列表）。</summary>
    bool IsChatAvailable { get; }

    /// <summary>最近一次失败原因（人话），成功后清空。</summary>
    string? ChatError { get; }

    /// <summary>当前打开（可见且窗口聚焦）的会话；推送到达时据此决定是否累加未读。</summary>
    string? ActiveConversationId { get; set; }

    /// <summary>会话列表或连接状态变化（已在 UI 线程触发）。</summary>
    event EventHandler? ChatChanged;

    /// <summary>WebSocket 连接状态变化。</summary>
    event EventHandler? ConnectionChanged;

    /// <summary>收到一条新消息（含自己消息在其它端的回显）；已在 UI 线程触发。</summary>
    event EventHandler<ChatMessage>? MessageReceived;

    /// <summary>登录后启动实时通道与会话加载（幂等）。</summary>
    Task StartChatAsync(CancellationToken cancellation = default);

    /// <summary>拉取会话列表（GET /api/chat/conversations）。</summary>
    Task RefreshConversationsAsync(CancellationToken cancellation = default);

    /// <summary>
    /// 加载消息：首屏不带游标；向上翻页传 <paramref name="beforeId"/>；重连补拉传 <paramref name="afterId"/>。
    /// 结果并入会话集合（首屏替换、before 前插、after 追加），全部按时间升序。
    /// </summary>
    Task<ChatMessagePage> LoadMessagesAsync(
        string conversationId,
        string? beforeId = null,
        string? afterId = null,
        int limit = 30,
        CancellationToken cancellation = default);

    /// <summary>创建一条本地待发消息并追加到会话集合（乐观插入）。</summary>
    ChatMessage AddPendingMessage(string conversationId, string text, string clientMsgId);

    /// <summary>发送（或重试）一条待发消息；成功后把本地消息升级为服务端行。</summary>
    Task<ChatSendResult> SendMessageAsync(ChatMessage pending, CancellationToken cancellation = default);

    /// <summary>上报已读到某条消息（POST /api/chat/conversations/{id}/read）。</summary>
    Task<bool> MarkReadAsync(string conversationId, string messageId, CancellationToken cancellation = default);

    /// <summary>确保与某好友的直聊会话存在（POST /api/chat/conversations；非好友会失败并返回 null）。</summary>
    Task<ChatThread?> EnsureDirectConversationAsync(string peerUserId, CancellationToken cancellation = default);

    /// <summary>好友列表（发起会话用）。</summary>
    Task<IReadOnlyList<ChatPeer>> GetFriendsAsync(CancellationToken cancellation = default);

    // ---------------------------------------------------------------- task-139

    /// <summary>当前登录用户 id（雪花字符串；判断每条消息是否为自己发的）。</summary>
    string CurrentUserId { get; }

    /// <summary>未读总数（GET /api/chat/unread；侧栏徽标用，不再逐会话累加轮询）。</summary>
    int UnreadTotal { get; }

    /// <summary>未读总数变化（聚合接口/推送/已读后触发）。</summary>
    event EventHandler? UnreadChanged;

    /// <summary>刷新未读聚合（GET /api/chat/unread）。</summary>
    Task RefreshUnreadAsync(CancellationToken cancellation = default);

    /// <summary>
    /// 把本地附件上传到「会话所属空间」并返回文件节点：
    /// 好友私聊走个人空间 <c>/api/files/upload</c>；工作区频道必须走
    /// <c>/api/workspaces/{ws}/files/upload</c>，否则服务端会以
    /// 「文件不在该会话可访问的空间内」拒绝引用（task-165 真机验证结论）。
    /// progress 为真实写入进度（0..1），供占位气泡显示进度条。
    /// </summary>
    Task<NodeItem?> UploadChatAttachmentAsync(
        string localPath,
        string? workspaceId,
        IProgress<double>? progress = null,
        CancellationToken cancellation = default);

    /// <summary>
    /// task-165：立刻插入一条本地附件占位消息（图片先渲染本地字节、文件显示类型图标+大小），
    /// 上传完成后再补 FileId 并发送；不先调 POST messages。
    /// </summary>
    ChatMessage AddPendingAttachmentMessage(
        string conversationId,
        string localPath,
        string kind,
        string clientMsgId,
        long fileSize,
        string? contentType);

    /// <summary>task-165：从消息流中移除本地占位（用户取消上传时，不留空壳）。</summary>
    void RemovePendingMessage(string conversationId, ChatMessage message);

    /// <summary>创建一条本地待发的文件/图片消息并追加到会话集合（已上传完成后的兼容入口）。</summary>
    ChatMessage AddPendingFileMessage(string conversationId, NodeItem file, string kind, string clientMsgId);

    /// <summary>在会话消息流里标出未读分割线（进入会话时第一条未读消息）。</summary>
    void MarkUnreadDivider(string conversationId, int unread);

    /// <summary>发送（或重试）一条待发的文件/图片消息。</summary>
    Task<ChatSendResult> SendFileMessageAsync(ChatMessage pending, CancellationToken cancellation = default);

    /// <summary>撤回自己的消息（POST /api/chat/messages/{id}/recall；本人 2 分钟内）。</summary>
    Task<ChatSendResult> RecallMessageAsync(ChatMessage message, CancellationToken cancellation = default);

    /// <summary>会话已读回执（GET /api/chat/conversations/{id}/read-state）。</summary>
    Task<ChatReadState?> LoadReadStateAsync(string conversationId, CancellationToken cancellation = default);

    /// <summary>置顶/取消置顶（PATCH /api/chat/conversations/{id}；每人最多 20 个）。</summary>
    Task<bool> SetPinnedAsync(string conversationId, bool pinned, CancellationToken cancellation = default);

    /// <summary>免打扰开/关（PATCH /api/chat/conversations/{id}）。</summary>
    Task<bool> SetMutedAsync(string conversationId, bool muted, CancellationToken cancellation = default);

    /// <summary>会话内搜索消息（GET .../messages/search；q 必填 ≤64，前缀匹配、大小写不敏感）。</summary>
    Task<IReadOnlyList<ChatSearchResult>> SearchMessagesAsync(
        string conversationId,
        string query,
        int limit = 20,
        CancellationToken cancellation = default);
}

/// <summary>待处理事项（收件箱 / 邀请 / 审批）。</summary>
public interface IInboxService
{
    ObservableCollection<InboxItem> Items { get; }

    int PendingCount { get; }

    event EventHandler? Changed;

    void Dismiss(InboxItem item);
}

/// <summary>缩略图：带鉴权拉取 + 内存 LRU + 并发限制 + 204 回退。</summary>
public interface IThumbnailService
{
    /// <summary>拿不到缩略图（204/类型不支持/失败）时返回 null，由界面回退类型图标。</summary>
    Task<Avalonia.Media.Imaging.Bitmap?> GetAsync(NodeItem node, int width, CancellationToken ct = default);

    void ClearCache();
}

/// <summary>预览：短签 URL / 内容流 / 落到临时文件（视频用系统播放器打开）。</summary>
public interface IPreviewService
{
    Task<Bond.Services.Api.PreviewUrlDto?> ResolveUrlsAsync(NodeItem node, CancellationToken ct = default);

    /// <summary>
    /// 办公文档预览（task-60）：取服务端转好的 PDF 字节。
    /// 返回 (null, 0) 表示后端未启用/不支持该类型；其它状态码原样带回（415=不支持，503=转换服务不可用）。
    /// 命中本地缓存（%LocalAppData%\\Bond\\office-cache）时直接读盘。
    /// </summary>
    Task<(byte[]? Data, int Status)> GetOfficePdfAsync(NodeItem node, CancellationToken ct = default);

    /// <summary>内容直链（绝对地址，token 在 query 里，可直接交给视频播放器）。</summary>
    Task<string?> GetContentUrlAsync(NodeItem node, CancellationToken ct = default);

    /// <summary>读取内容（maxBytes 非空时用 Range 只取前段，返回是否截断）。</summary>
    Task<(byte[]? Data, bool Truncated)> GetContentAsync(NodeItem node, long? maxBytes = null,
        CancellationToken ct = default);

    /// <summary>指定宽度的缩略图/PDF 页图字节；204 或无内容返回 null。</summary>
    Task<byte[]?> GetThumbnailBytesAsync(NodeItem node, int width, CancellationToken ct = default);

    /// <summary>下载到临时文件，返回本地路径（用于"用默认程序打开"）。</summary>
    Task<string?> DownloadToTempAsync(NodeItem node, CancellationToken ct = default);
}

/// <summary>会话/账户（此处为演示实现，真实版接 ApiClient）。</summary>
public interface ISessionService
{
    string DeviceName { get; set; }

    string AccountName { get; }

    bool IsSignedIn { get; }

    string DeviceCode { get; }

    string PresenceText { get; }

    PresenceState Presence { get; }

    /// <summary>服务端返回的头像地址（可能为空）。</summary>
    string? AvatarUrl { get; }

    /// <summary>已加载好的头像位图；为空时界面回退为首字母。</summary>
    object? AvatarSource { get; }

    /// <summary>登录用户 / 设备信息已刷新（设置页据此更新账户区）。</summary>
    event EventHandler? Changed;
}

public enum PresenceState
{
    Offline,
    LanOnly,
    Relay,
    Online
}

/// <summary>
/// 软件分发（task-86）：查询生产发布并下载安装包。
/// 只有查询与下载，不做自动静默安装；安装包 SHA-256 由客户端校验。
/// </summary>
public interface ISoftwareService
{
    /// <summary>客户端版本号（读程序集 InformationalVersion，不写死）。</summary>
    string CurrentVersion { get; }

    /// <summary>查询生产发布的更新状态（四态：有更新 / 已最新 / 暂无安装包 / 出错）。</summary>
    Task<SoftwareCheckResult> CheckForUpdatesAsync(CancellationToken ct = default);

    /// <summary>下载并校验 SHA-256；进度 0-100 为真实字节进度。</summary>
    Task<SoftwareDownloadResult> DownloadAsync(SoftwareReleaseInfo release, IProgress<double>? progress,
        CancellationToken ct = default);
}

/// <summary>
/// task-7：系统公告（用户侧只读 + 标记已读）。
/// <para>后端契约：<c>GET /api/announcements</c>（不自动标已读）、
/// <c>GET /api/announcements/unread-count</c>（<c>{count}</c>）、
/// <c>POST /api/announcements/{id}/read</c>（幂等）。</para>
/// </summary>
public interface IAnnouncementService
{
    /// <summary>当前用户可见公告（服务端按发布时间倒序，最多 50 条）。失败时 Items 为空且 Error 有文案。</summary>
    Task<(IReadOnlyList<AnnouncementItem> Items, string? Error)> GetAsync(CancellationToken ct = default);

    /// <summary>未读条数。失败时 Error 有文案（界面保留上一次值，不误报 0）。</summary>
    Task<(int Unread, string? Error)> GetUnreadCountAsync(CancellationToken ct = default);

    /// <summary>标记一条公告为已读（幂等）；成功返回 null，失败返回可展示原因。</summary>
    Task<string?> MarkReadAsync(string id, CancellationToken ct = default);
}
