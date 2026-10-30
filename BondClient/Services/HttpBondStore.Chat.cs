using System;
using System.Buffers;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Bond.Core;
using Bond.Models;
using Bond.Services.Api;

namespace Bond.Services;

/// <summary>
/// task-3：聊天附件下载结果。<see cref="Path"/> 非空表示成功并已落盘；
/// 否则 <see cref="Error"/> 是可直接展示给用户的中文失败原因。
/// </summary>
public sealed record ChatAttachmentDownload(string? Path, string? Error);

/// <summary>
/// task-3：聊天附件下载能力（聊天页「点击文件消息 → 下载」用真实逐字节进度）。
/// <para>单独抽接口是因为 <see cref="IChatService"/> 在 Contracts.cs（本任务不改），
/// 而下载只需要聊天页这一个调用方；Demo 数据源不实现时界面会明确提示不支持。</para>
/// </summary>
public interface IChatAttachmentDownloader
{
    Task<ChatAttachmentDownload> DownloadChatAttachmentAsync(
        ChatMessage message,
        string targetDirectory,
        IProgress<double>? progress = null,
        CancellationToken cancellation = default);
}

/// <summary>
/// 聊天数据层（task-52）：REST（会话 / 消息 / 已读 / 好友）+ WebSocket 实时推送 + 断线 5 秒轮询降级。
/// <para>约定：所有雪花 id 一律按字符串处理（<see cref="SnowflakeStringConverter"/> 兼容字符串/数字两种编码）。</para>
/// <para>发送消息只带 <c>body</c> 字段（生产实测后端只认它），<c>kind</c> 必填、<c>clientMsgId</c> 必填（幂等重试）。</para>
/// </summary>
public sealed partial class HttpBondStore : IChatAttachmentDownloader
{
    private const int ChatInitialPageSize = 30;

    // task-151：断线降级轮询周期统一由 RetryPolicy.BackgroundPollInterval 定义（5s），不在此处另存一份。
    private static readonly int ChatPollIntervalMs =
        (int)RetryPolicy.BackgroundPollInterval.TotalMilliseconds;
    private const int ChatMaxTextLength = 4_000;

    /// <summary>连续消息合并窗口（task-166）：同一发送者 5 分钟内的消息合并为一组。</summary>
    private static readonly TimeSpan GroupWindow = TimeSpan.FromMinutes(5);

    private static readonly JsonSerializerOptions ChatJsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        NumberHandling = JsonNumberHandling.AllowReadingFromString
    };

    private readonly Dictionary<string, ObservableCollection<ChatMessage>> _chatMessages = new(StringComparer.Ordinal);
    private readonly Dictionary<string, bool> _chatHasMore = new(StringComparer.Ordinal);
    private readonly object _chatGate = new();

    private ChatSocket? _chatSocket;
    private CancellationTokenSource? _chatPollCts;
    private Task? _chatPollTask;
    private volatile ChatConnectionState _chatState = ChatConnectionState.Offline;
    private string? _chatError;
    private string? _chatActiveConversationId;
    private int _chatRefreshRunning;
    private int _chatUnreadTotal;
    private int _chatUnreadRefreshPending;

    /// <summary>每个会话里「对方」已读到的最后一条消息 id（已读回执）。</summary>
    private readonly Dictionary<string, string?> _chatPeerReadIds = new(StringComparer.Ordinal);

    // 临时诊断开关（BOND_CHAT_DIAG=1 时写 %TEMP%\bond-chat-diag.log），定位未读链路后删除。
    private static readonly bool ChatDiagEnabled =
        Environment.GetEnvironmentVariable("BOND_CHAT_DIAG") == "1";

    internal static void ChatDiag(string text)
    {
        if (!ChatDiagEnabled)
        {
            return;
        }

        try
        {
            File.AppendAllText(
                Path.Combine(Path.GetTempPath(), "bond-chat-diag.log"),
                $"{DateTime.Now:HH:mm:ss.fff} {text}{Environment.NewLine}");
        }
        catch
        {
            // 诊断日志失败不影响功能
        }
    }

    // ── IChatService ──────────────────────────────────────────

    /// <summary>会话列表；引用整体替换（一次刷新/推送只换一次实例，不逐项增删）。</summary>
    public IReadOnlyList<ChatThread> Threads => _threads;

    /// <summary>当前登录用户 id（雪花字符串）。</summary>
    public string CurrentUserId => CurrentChatUserId;

    public ObservableCollection<ChatMessage> MessagesFor(string conversationId)
    {
        lock (_chatGate)
        {
            if (!_chatMessages.TryGetValue(conversationId, out var list))
            {
                list = [];
                _chatMessages[conversationId] = list;
            }

            return list;
        }
    }

    public ChatConnectionState ConnectionState => _chatState;

    public bool IsChatAvailable => _api.IsLoggedIn;

    public string? ChatError => _chatError;

    public string? ActiveConversationId
    {
        get => _chatActiveConversationId;
        set => _chatActiveConversationId = string.IsNullOrEmpty(value) ? null : value;
    }

    /// <summary>未读总数（GET /api/chat/unread 的 total；推送/已读时增量更新）。</summary>
    public int UnreadTotal => _chatUnreadTotal;

    public event EventHandler? ChatChanged;

    public event EventHandler? UnreadChanged;

    public event EventHandler? ConnectionChanged;

    public event EventHandler<ChatMessage>? MessageReceived;

    public async Task StartChatAsync(CancellationToken cancellation = default)
    {
        if (!_api.IsLoggedIn)
        {
            SetChatState(ChatConnectionState.Offline);
            return;
        }

        StartChatSocket();
        StartChatPolling();
        await RefreshConversationsAsync(cancellation).ConfigureAwait(false);
        await RefreshUnreadAsync(cancellation).ConfigureAwait(false);
    }

    /// <summary>登出：停掉实时通道与轮询，清空内存中的聊天数据。</summary>
    public void StopChat()
    {
        try
        {
            _chatPollCts?.Cancel();
            _chatPollCts?.Dispose();
        }
        catch
        {
            // ignore
        }

        _chatPollCts = null;
        _chatPollTask = null;

        var socket = _chatSocket;
        _chatSocket = null;
        if (socket is not null)
        {
            _ = socket.DisposeAsync();
        }

        _chatActiveConversationId = null;
        SetChatState(ChatConnectionState.Offline);
        Ui(() =>
        {
            _threads = [];
            _chatUnreadTotal = 0;
            _chatPeerReadIds.Clear();
            lock (_chatGate)
            {
                foreach (var list in _chatMessages.Values)
                {
                    // task-135：清空也走逐项移除（Clear 的 Reset 会残留消息容器）。
                    CollectionSync.Apply(list, Array.Empty<ChatMessage>());
                }

                _chatMessages.Clear();
            }

            _chatError = null;
            UnreadChanged?.Invoke(this, EventArgs.Empty);
            ChatChanged?.Invoke(this, EventArgs.Empty);
        });
    }

    public async Task RefreshConversationsAsync(CancellationToken cancellation = default)
    {
        if (!_api.IsLoggedIn)
        {
            SetChatState(ChatConnectionState.Offline);
            return;
        }

        if (Interlocked.Exchange(ref _chatRefreshRunning, 1) == 1)
        {
            return;
        }

        try
        {
            var result = await _api.GetAsync<List<ChatConversationDto>>("/api/chat/conversations")
                .ConfigureAwait(false);
            if (result?.Data is null)
            {
                SetChatError(LoadRetry.Humanize(_api.LastError, "会话列表加载失败，请检查网络后重试"));
                return;
            }

            var fresh = result.Data
                .Select(MapThread)
                .Where(thread => thread is not null)
                .Select(thread => thread!)
                .ToList();

            Ui(() =>
            {
                ApplyConversations(fresh);
                _chatError = null;
                ChatChanged?.Invoke(this, EventArgs.Empty);
            });
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            // 调用方取消
        }
        catch (Exception ex)
        {
            SetChatError($"会话列表加载失败：{ex.Message}");
        }
        finally
        {
            Interlocked.Exchange(ref _chatRefreshRunning, 0);
        }
    }

    /// <summary>未读聚合：一次请求拿总数与逐会话未读（侧栏徽标用，避免逐会话轮询）。</summary>
    public async Task RefreshUnreadAsync(CancellationToken cancellation = default)
    {
        if (!_api.IsLoggedIn)
        {
            return;
        }

        try
        {
            var result = await _api.GetAsync<ChatUnreadDto>("/api/chat/unread").ConfigureAwait(false);
            if (result?.Data is null)
            {
                return;
            }

            var data = result.Data;
            var total = (int)Math.Clamp(data.Total, 0, int.MaxValue);
            var byConversation = new Dictionary<string, int>(StringComparer.Ordinal);
            foreach (var item in data.ByConversation ?? [])
            {
                if (!string.IsNullOrEmpty(item.Id))
                {
                    byConversation[item.Id!] = (int)Math.Clamp(item.Unread, 0, int.MaxValue);
                }
            }

            Ui(() =>
            {
                ChatDiag($"unread total={total} by={string.Join(",", byConversation.Select(pair => pair.Key + ":" + pair.Value))}");
                _chatUnreadTotal = total;
                foreach (var thread in _threads)
                {
                    thread.Unread = byConversation.TryGetValue(thread.Id, out var unread) ? unread : 0;
                }

                UnreadChanged?.Invoke(this, EventArgs.Empty);
                ChatChanged?.Invoke(this, EventArgs.Empty);
            });
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            // 调用方取消
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[chat] unread failed: {ex.Message}");
        }
    }

    /// <summary>未读总数增量更新（推送到达/已读上报后立刻反映到侧栏，不额外请求）。</summary>
    private void BumpUnreadTotal(int delta)
    {
        var next = Math.Max(0, _chatUnreadTotal + delta);
        if (next == _chatUnreadTotal)
        {
            return;
        }

        _chatUnreadTotal = next;
        ChatDiag($"bump delta={delta} total={next}");
        Ui(() => UnreadChanged?.Invoke(this, EventArgs.Empty));
    }

    /// <summary>
    /// 推送到达后以服务端聚合为准再校准一次未读（合并 300ms 内的多次推送）。
    /// 本地增量只是「先让界面动起来」，最终数字必须和 GET /api/chat/unread 一致。
    /// </summary>
    private void RefreshUnreadSoon()
    {
        if (Interlocked.Exchange(ref _chatUnreadRefreshPending, 1) == 1)
        {
            return;
        }

        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(300).ConfigureAwait(false);
                await RefreshUnreadAsync().ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"[chat] unread refresh failed: {ex.Message}");
            }
            finally
            {
                Interlocked.Exchange(ref _chatUnreadRefreshPending, 0);
            }
        });
    }

    public async Task<ChatMessagePage> LoadMessagesAsync(
        string conversationId,
        string? beforeId = null,
        string? afterId = null,
        int limit = ChatInitialPageSize,
        CancellationToken cancellation = default)
    {
        if (string.IsNullOrEmpty(conversationId))
        {
            return ChatMessagePage.Failure("会话不存在");
        }

        limit = Math.Clamp(limit, 1, 100);

        var url = $"/api/chat/conversations/{conversationId}/messages?limit={limit}";
        if (!string.IsNullOrEmpty(beforeId))
        {
            url += $"&beforeId={Uri.EscapeDataString(beforeId)}";
        }
        else if (!string.IsNullOrEmpty(afterId))
        {
            url += $"&afterId={Uri.EscapeDataString(afterId)}";
        }

        try
        {
            var result = await _api.GetAsync<List<ChatMessageDto>>(url).ConfigureAwait(false);
            if (result?.Data is null)
            {
                var error = LoadRetry.Humanize(_api.LastError, "消息加载失败，请重试");
                SetChatError(error);
                return ChatMessagePage.Failure(error);
            }

            var dm = result.Data;
            var spaceId = FindThread(conversationId)?.WorkspaceId ?? "personal";
            var mapped = dm
                .Select(dto => MapMessage(dto, spaceId))
                .Where(message => message is not null)
                .Select(message => message!)
                .ToList();
            var hasMore = dm.Count >= limit;

            Ui(() =>
            {
                var list = MessagesFor(conversationId);
                if (string.IsNullOrEmpty(beforeId) && string.IsNullOrEmpty(afterId))
                {
                    // 首屏：保留本地未确认的消息（乐观发送失败的重试气泡不能因为刷新而消失）。
                    var pending = list
                        .Where(m => m.SendState != ChatSendState.Sent || string.IsNullOrEmpty(m.Id))
                        .ToList();

                    // task-135：先攒「新的一批」，再用 CollectionSync 逐项同步。
                    // 直接 list.Clear() 会发 Reset，Avalonia 12 的 ItemsControl 会残留旧容器，
                    // 表现就是「刷新一次消息翻倍」。
                    var fresh = new List<ChatMessage>(mapped.Count + pending.Count);
                    foreach (var message in mapped)
                    {
                        if (!fresh.Any(item => SameMessage(item, message)))
                        {
                            fresh.Add(message);
                        }
                    }

                    foreach (var pendingMessage in pending)
                    {
                        if (!fresh.Any(item => SameMessage(item, pendingMessage)))
                        {
                            fresh.Add(pendingMessage);
                        }
                    }

                    CollectionSync.Apply(list, fresh, SameMessage);
                }
                else if (!string.IsNullOrEmpty(beforeId))
                {
                    // 向上翻页：按升序插到最前面。
                    var index = 0;
                    foreach (var message in mapped)
                    {
                        if (ContainsMessage(list, message))
                        {
                            continue;
                        }

                        list.Insert(Math.Min(index, list.Count), message);
                        index++;
                    }
                }
                else
                {
                    // 断线补拉：追加缺的（服务端已按升序返回）。
                    foreach (var message in mapped)
                    {
                        if (!ContainsMessage(list, message))
                        {
                            list.Add(message);
                        }
                    }
                }

                ApplyDaySeparators(list);
                _chatHasMore[conversationId] = hasMore;
                _chatError = null;
                ChatChanged?.Invoke(this, EventArgs.Empty);
            });

            return new ChatMessagePage { Messages = mapped, HasMore = hasMore };
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            return ChatMessagePage.Failure("已取消");
        }
        catch (Exception ex)
        {
            var error = $"消息加载失败：{ex.Message}";
            SetChatError(error);
            return ChatMessagePage.Failure(error);
        }
    }

    public ChatMessage AddPendingMessage(string conversationId, string text, string clientMsgId)
    {
        var message = new ChatMessage
        {
            ConversationId = conversationId,
            Kind = "text",
            SenderId = CurrentChatUserId,
            SenderName = AccountName,
            IsMine = true,
            ClientMsgId = clientMsgId,
            Text = text,
            SendState = ChatSendState.Sending,
            SentAt = DateTime.Now
        };

        Ui(() =>
        {
            var list = MessagesFor(conversationId);
            list.Add(message);
            ApplyDaySeparators(list);
            UpdateThreadForMessage(message, incoming: false);
        });

        return message;
    }

    public async Task<ChatSendResult> SendMessageAsync(ChatMessage pending, CancellationToken cancellation = default)
    {
        if (pending is null)
        {
            return ChatSendResult.Failure("内部错误：消息为空");
        }

        if (string.IsNullOrEmpty(pending.ClientMsgId))
        {
            return ChatSendResult.Failure("内部错误：缺少消息幂等键（clientMsgId）");
        }

        if (!pending.IsMine || pending.ConversationId.Length == 0)
        {
            return ChatSendResult.Failure("内部错误：消息不属于当前会话");
        }

        if (!string.IsNullOrEmpty(pending.Id))
        {
            // 已经由服务端确认（例如重连补拉/推送先到），无需重发。
            Ui(() => pending.SendState = ChatSendState.Sent);
            return new ChatSendResult { Success = true, Message = pending };
        }

        if (pending.Text.Length > ChatMaxTextLength)
        {
            return ChatSendResult.Failure($"消息过长（最多 {ChatMaxTextLength} 字）");
        }

        Ui(() => pending.SendState = ChatSendState.Sending);

        try
        {
            // 生产契约（lead 亲测）：正文只有 body 一个字段，kind / clientMsgId 必填。
            var body = new Dictionary<string, object?>
            {
                ["kind"] = "text",
                ["body"] = pending.Text,
                ["clientMsgId"] = pending.ClientMsgId
            };

            var result = await _api
                .PostAsync<ChatMessageDto>($"/api/chat/conversations/{pending.ConversationId}/messages", body)
                .ConfigureAwait(false);

            if (result?.Data is null)
            {
                var error = HumanizeSendError(result);
                Ui(() =>
                {
                    pending.SendState = ChatSendState.Failed;
                    UpdateThreadForMessage(pending, incoming: false);
                    ChatChanged?.Invoke(this, EventArgs.Empty);
                });
                SetChatError(error);
                return ChatSendResult.Failure(error);
            }

            var sent = MapMessage(result.Data, FindThread(pending.ConversationId)?.WorkspaceId ?? "personal");
            Ui(() =>
            {
                if (sent is not null)
                {
                    pending.Id = sent.Id;
                    pending.SentAt = sent.SentAt;
                }
                pending.SendState = ChatSendState.Sent;
                pending.IsDeleted = false;
                var list = MessagesFor(pending.ConversationId);
                DeduplicateAndSort(list);
                ApplyDaySeparators(list);
                UpdateThreadForMessage(pending, incoming: false);
                _chatError = null;
                ChatChanged?.Invoke(this, EventArgs.Empty);
            });

            return new ChatSendResult { Success = true, Message = pending };
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            Ui(() => pending.SendState = ChatSendState.Failed);
            return ChatSendResult.Failure("已取消");
        }
        catch (Exception ex)
        {
            var error = $"发送失败：{ex.Message}";
            Ui(() => pending.SendState = ChatSendState.Failed);
            SetChatError(error);
            return ChatSendResult.Failure(error);
        }
    }

    public async Task<bool> MarkReadAsync(
        string conversationId,
        string messageId,
        CancellationToken cancellation = default)
    {
        if (string.IsNullOrEmpty(conversationId) || string.IsNullOrEmpty(messageId))
        {
            return false;
        }

        try
        {
            // 生产契约：body 必须带 messageId（字符串雪花 id），否则 400。
            var result = await _api
                .PostAsync<object>($"/api/chat/conversations/{conversationId}/read", new Dictionary<string, object?>
                {
                    ["messageId"] = messageId
                })
                .ConfigureAwait(false);

            if (result is null)
            {
                SetChatError(LoadRetry.Humanize(_api.LastError, "已读上报失败"));
                return false;
            }

            if (result.Code is not (200 or 204))
            {
                SetChatError(LoadRetry.Humanize(result.Message, "已读上报失败"));
                return false;
            }

            Ui(() =>
            {
                var thread = FindThread(conversationId);
                ChatDiag($"markread conv={conversationId} msg={messageId} threadUnread={thread?.Unread} total={_chatUnreadTotal}");
                if (thread is not null)
                {
                    if (thread.Unread > 0)
                    {
                        _chatUnreadTotal = Math.Max(0, _chatUnreadTotal - thread.Unread);
                    }

                    thread.Unread = 0;
                    UnreadChanged?.Invoke(this, EventArgs.Empty);
                }

                ChatChanged?.Invoke(this, EventArgs.Empty);
            });
            return true;
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            return false;
        }
        catch (Exception ex)
        {
            SetChatError($"已读上报失败：{ex.Message}");
            return false;
        }
    }

    public async Task<ChatThread?> EnsureDirectConversationAsync(
        string peerUserId,
        CancellationToken cancellation = default)
    {
        if (string.IsNullOrEmpty(peerUserId))
        {
            return null;
        }

        var existing = _threads.FirstOrDefault(thread =>
            !thread.IsChannel && string.Equals(thread.PeerUserId, peerUserId, StringComparison.Ordinal));
        if (existing is not null)
        {
            return existing;
        }

        try
        {
            var result = await _api
                .PostAsync<ChatConversationDto>("/api/chat/conversations", new Dictionary<string, object?>
                {
                    ["peerUserId"] = peerUserId
                })
                .ConfigureAwait(false);

            if (result?.Data is null)
            {
                var error = result is null
                    ? LoadRetry.Humanize(_api.LastError, "无法创建会话，请检查网络后重试")
                    : LoadRetry.Humanize(result.Message, "无法创建会话（你们还不是好友？）");
                SetChatError(error);
                return null;
            }

            var thread = MapThread(result.Data);
            if (thread is null)
            {
                SetChatError("无法创建会话：服务端返回的会话数据不完整");
                return null;
            }

            ChatThread? resolved = null;
            Ui(() =>
            {
                var found = FindThread(thread.Id);
                if (found is not null)
                {
                    resolved = found;
                    UpdateThread(found, thread);
                }
                else
                {
                    // 整体替换引用，不原地 Insert（见 ApplyConversations）。
                    _threads = Ordered(_threads.Prepend(thread));
                    resolved = thread;
                }

                _chatError = null;
                ChatChanged?.Invoke(this, EventArgs.Empty);
            });

            return resolved ?? thread;
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            return null;
        }
        catch (Exception ex)
        {
            SetChatError($"无法创建会话：{ex.Message}");
            return null;
        }
    }

    public async Task<IReadOnlyList<ChatPeer>> GetFriendsAsync(CancellationToken cancellation = default)
    {
        try
        {
            var result = await _api.GetAsync<List<ChatFriendDto>>("/api/friends").ConfigureAwait(false);
            if (result?.Data is null)
            {
                SetChatError(LoadRetry.Humanize(_api.LastError, "好友列表加载失败，请重试"));
                return [];
            }

            return result.Data
                .Select(friend => new ChatPeer
                {
                    UserId = string.IsNullOrEmpty(friend.UserId) ? friend.Id ?? "" : friend.UserId!,
                    DisplayName = FirstNotEmpty(friend.Nickname, friend.Username) ?? "好友",
                    AvatarUrl = friend.AvatarUrl
                })
                .Where(peer => !string.IsNullOrEmpty(peer.UserId))
                .ToList();
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            return [];
        }
        catch (Exception ex)
        {
            SetChatError($"好友列表加载失败：{ex.Message}");
            return [];
        }
    }

    // ── task-139：文件/图片消息、撤回、已读回执、置顶/免打扰、搜索 ──

    public async Task<NodeItem?> UploadChatAttachmentAsync(
        string localPath,
        string? workspaceId,
        IProgress<double>? progress = null,
        CancellationToken cancellation = default)
    {
        if (string.IsNullOrWhiteSpace(localPath) || !System.IO.File.Exists(localPath))
        {
            SetChatError("找不到要发送的文件");
            return null;
        }

        try
        {
            await using var stream = System.IO.File.OpenRead(localPath);
            using var content = new System.Net.Http.MultipartFormDataContent();

            // task-165：真实字节进度（流式写入时每 64KB 回调一次），不是假动画。
            var upload = new ProgressStreamContent(stream, stream.Length, progress, cancellation);
            var contentType = ContentTypeFor(System.IO.Path.GetFileName(localPath));
            if (!string.IsNullOrEmpty(contentType))
            {
                upload.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue(contentType);
            }

            content.Add(upload, "file", System.IO.Path.GetFileName(localPath));
            content.Add(new System.Net.Http.StringContent("/"), "parentPath");

            // task-165：工作区频道的附件必须落在该工作区空间里，否则发送消息会被
            // 「文件不在该会话可访问的空间内」拒绝；私聊仍走个人空间。
            var filesBase = string.IsNullOrWhiteSpace(workspaceId)
                ? "/api/files"
                : $"/api/workspaces/{workspaceId}/files";

            var result = await _api
                .PostMultipartAsync<WorkspaceFileDto>($"{filesBase}/upload", content)
                .ConfigureAwait(false);

            if (result?.Data is null)
            {
                SetChatError(LoadRetry.Humanize(_api.LastError, "附件上传失败，请重试"));
                return null;
            }

            var node = MapFile(result.Data, workspaceId ?? "personal");
            _ = RefreshFilesAsync();
            return node;
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            return null;
        }
        catch (Exception ex)
        {
            SetChatError($"附件上传失败：{ex.Message}");
            return null;
        }
    }

    public ChatMessage AddPendingAttachmentMessage(
        string conversationId,
        string localPath,
        string kind,
        string clientMsgId,
        long fileSize,
        string? contentType)
    {
        var fileName = System.IO.Path.GetFileName(localPath);

        var message = new ChatMessage
        {
            ConversationId = conversationId,
            Kind = string.IsNullOrEmpty(kind) ? "file" : kind,
            SenderId = CurrentChatUserId,
            SenderName = AccountName,
            IsMine = true,
            ClientMsgId = clientMsgId,
            FileName = fileName,
            FileSize = fileSize,
            FileContentType = contentType,
            LocalPath = localPath,
            IsUploading = true,
            UploadProgress = 0,
            SpaceId = FindThread(conversationId)?.WorkspaceId is { Length: > 0 } ws ? ws : "personal",
            SendState = ChatSendState.Sending,
            SentAt = DateTime.Now
        };

        Ui(() =>
        {
            var list = MessagesFor(conversationId);
            list.Add(message);
            ApplyDaySeparators(list);
            UpdateThreadForMessage(message, incoming: false);
        });

        return message;
    }

    public void RemovePendingMessage(string conversationId, ChatMessage message)
    {
        // 只有「还没拿到服务端 id」的本地占位才能取消移除；已发送的要走撤回。
        if (message is null || !string.IsNullOrEmpty(message.Id)) return;

        Ui(() =>
        {
            var list = MessagesFor(conversationId);
            list.Remove(message);
            ApplyDaySeparators(list);
        });
    }

    public ChatMessage AddPendingFileMessage(string conversationId, NodeItem file, string kind, string clientMsgId)
    {
        var message = new ChatMessage
        {
            ConversationId = conversationId,
            Kind = string.IsNullOrEmpty(kind) ? "file" : kind,
            SenderId = CurrentChatUserId,
            SenderName = AccountName,
            IsMine = true,
            ClientMsgId = clientMsgId,
            FileId = file.Id,
            FileName = file.Name,
            FileSize = file.Size,
            FileContentType = file.ContentType,
            SpaceId = file.SpaceId,
            SendState = ChatSendState.Sending,
            SentAt = DateTime.Now
        };

        Ui(() =>
        {
            var list = MessagesFor(conversationId);
            list.Add(message);
            ApplyDaySeparators(list);
            UpdateThreadForMessage(message, incoming: false);
        });

        return message;
    }

    public async Task<ChatSendResult> SendFileMessageAsync(ChatMessage pending, CancellationToken cancellation = default)
    {
        if (pending is null)
        {
            return ChatSendResult.Failure("内部错误：消息为空");
        }

        if (string.IsNullOrEmpty(pending.ClientMsgId) || string.IsNullOrEmpty(pending.FileId))
        {
            return ChatSendResult.Failure("内部错误：附件信息缺失");
        }

        if (!pending.IsMine || pending.ConversationId.Length == 0)
        {
            return ChatSendResult.Failure("内部错误：消息不属于当前会话");
        }

        if (!string.IsNullOrEmpty(pending.Id))
        {
            Ui(() => pending.SendState = ChatSendState.Sent);
            return new ChatSendResult { Success = true, Message = pending };
        }

        Ui(() => pending.SendState = ChatSendState.Sending);

        try
        {
            var body = new Dictionary<string, object?>
            {
                ["kind"] = string.IsNullOrEmpty(pending.Kind) ? "file" : pending.Kind,
                ["fileId"] = pending.FileId,
                ["clientMsgId"] = pending.ClientMsgId
            };

            var result = await _api
                .PostAsync<ChatMessageDto>($"/api/chat/conversations/{pending.ConversationId}/messages", body)
                .ConfigureAwait(false);

            if (result?.Data is null)
            {
                var error = HumanizeSendError(result);
                Ui(() =>
                {
                    pending.SendState = ChatSendState.Failed;
                    UpdateThreadForMessage(pending, incoming: false);
                    ChatChanged?.Invoke(this, EventArgs.Empty);
                });
                SetChatError(error);
                return ChatSendResult.Failure(error);
            }

            var sent = MapMessage(result.Data, FindThread(pending.ConversationId)?.WorkspaceId ?? "personal");
            Ui(() =>
            {
                if (sent is not null)
                {
                    pending.Id = sent.Id;
                    pending.SentAt = sent.SentAt;
                }

                pending.SendState = ChatSendState.Sent;
                var list = MessagesFor(pending.ConversationId);
                DeduplicateAndSort(list);
                ApplyDaySeparators(list);
                UpdateThreadForMessage(pending, incoming: false);
                _chatError = null;
                ChatChanged?.Invoke(this, EventArgs.Empty);
            });

            return new ChatSendResult { Success = true, Message = pending };
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            Ui(() => pending.SendState = ChatSendState.Failed);
            return ChatSendResult.Failure("已取消");
        }
        catch (Exception ex)
        {
            var error = $"发送失败：{ex.Message}";
            Ui(() => pending.SendState = ChatSendState.Failed);
            SetChatError(error);
            return ChatSendResult.Failure(error);
        }
    }

    // ── task-3：聊天附件下载（点击文件消息 → 下载目录，真实进度 + 失败提示） ──

    /// <summary>
    /// 把一条聊天附件消息（对方发来的 / 自己发的 / 工作区频道里的）下载到本地目录。
    /// <para>URL 统一走聊天文件令牌链路：消息是唯一根，收发双方同一路径，撤回/删除后统一 404；
    /// 不依赖「当前浏览空间」，也不要求调用方是文件 owner。</para>
    /// </summary>
    public async Task<ChatAttachmentDownload> DownloadChatAttachmentAsync(
        ChatMessage message,
        string targetDirectory,
        IProgress<double>? progress = null,
        CancellationToken cancellation = default)
    {
        if (message?.FileNode is not { } node)
        {
            return new ChatAttachmentDownload(null, "附件信息不完整，无法下载");
        }

        string? target = null;
        TransferSession? session = null;
        CancellationTokenSource? linked = null;
        try
        {
            System.IO.Directory.CreateDirectory(targetDirectory);

            var safeName = string.Join("_", node.Name.Split(
                System.IO.Path.GetInvalidFileNameChars(), StringSplitOptions.RemoveEmptyEntries));
            if (string.IsNullOrWhiteSpace(safeName)) safeName = "download";
            target = UniqueDownloadTarget(targetDirectory, safeName);

            // 上传中的本地占位（local:<path>）：还没有服务端副本，直接复制本地字节，
            // 保证「发送中的图片/文件也能先存到本地」，不假装走网络。
            if (LocalFileSpace.IsLocal(node))
            {
                var source = LocalFileSpace.Path(node);
                await using (var input = System.IO.File.OpenRead(source))
                await using (var output = System.IO.File.Create(target))
                {
                    await CopyWithProgressAsync(input, output, input.Length, progress, cancellation)
                        .ConfigureAwait(false);
                }

                return new ChatAttachmentDownload(target, null);
            }

            var url = await ResolveDownloadUrlAsync(node, cancellation).ConfigureAwait(false);
            if (string.IsNullOrEmpty(url))
            {
                return new ChatAttachmentDownload(null,
                    LoadRetry.Humanize(_api.LastError, "文件不可访问（可能已被撤回或删除）"));
            }

            using var response = await _api.GetStreamAsync(url).ConfigureAwait(false);
            if (response is null)
            {
                return new ChatAttachmentDownload(null,
                    LoadRetry.Humanize(_api.LastError, "下载失败，请稍后重试"));
            }

            var totalBytes = response.Content.Headers.ContentLength ?? message.FileSize;
            linked = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            session = BeginDownloadSession(node.Name, DescribeDownloadTarget(targetDirectory), totalBytes, linked);

            await using (var source = await response.Content.ReadAsStreamAsync(linked.Token).ConfigureAwait(false))
            await using (var file = System.IO.File.Create(target))
            {
                await CopyWithProgressAsync(source, file, totalBytes, progress, linked.Token).ConfigureAwait(false);
            }

            progress?.Report(1);
            FinishDownloadSession(session, target, null);
            return new ChatAttachmentDownload(target, null);
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            if (session is not null) FinishDownloadSession(session, null, null, canceled: true);
            TryDeletePartial(target);
            return new ChatAttachmentDownload(null, "下载已取消");
        }
        catch (Exception ex)
        {
            if (session is not null) FinishDownloadSession(session, null, ex.Message);
            TryDeletePartial(target);
            return new ChatAttachmentDownload(null, LoadRetry.Humanize(ex.Message, "下载失败，请稍后重试"));
        }
        finally
        {
            linked?.Dispose();
        }
    }

    /// <summary>
    /// 解析文件下载 URL。
    /// <para><b>聊天文件不属于任何「浏览空间」</b>：<c>node.SpaceId = "chat:&lt;messageId&gt;"</c>、
    /// <c>node.Id</c> 是发送者个人空间里的 fileId，旧路径 <c>/api/files/{fileId}/download</c>
    /// 对接收方必然 404（owner 校验），所以按 messageId 签发短时令牌，取 contentUrl。</para>
    /// <para>非聊天节点按节点自己的 <see cref="NodeItem.SpaceId"/> 拼 URL（个人空间 / 对应工作区）：
    /// 不能再用 store 当前浏览的空间，否则「在别的空间时下载自己的文件」也会 404。</para>
    /// </summary>
    private async Task<string?> ResolveDownloadUrlAsync(NodeItem node, CancellationToken ct = default)
    {
        _ = ct;

        if (ChatFileSpace.IsChatFile(node))
        {
            var messageId = ChatFileSpace.MessageId(node);
            if (string.IsNullOrEmpty(messageId)) return null;

            var result = await _api
                .PostAsync<ChatFileTokenDto>($"/api/chat/files/{messageId}/tokens")
                .ConfigureAwait(false);
            var url = result?.Data?.ContentUrl;
            return string.IsNullOrEmpty(url) ? null : url;
        }

        // 本地占位节点没有服务端副本，不走网络下载。
        if (LocalFileSpace.IsLocal(node)) return null;

        return string.Equals(node.SpaceId, "personal", StringComparison.OrdinalIgnoreCase)
            ? $"/api/files/{node.Id}/download"
            : $"/api/workspaces/{node.SpaceId}/files/{node.Id}/download";
    }

    /// <summary>下载目标路径：同名文件自动追加序号，绝不覆盖用户已有文件。</summary>
    private static string UniqueDownloadTarget(string directory, string fileName)
    {
        var target = System.IO.Path.Combine(directory, fileName);
        var stem = System.IO.Path.GetFileNameWithoutExtension(fileName);
        var ext = System.IO.Path.GetExtension(fileName);
        for (var i = 1; System.IO.File.Exists(target); i++)
        {
            target = System.IO.Path.Combine(directory, $"{stem} ({i}){ext}");
        }

        return target;
    }

    /// <summary>真实字节进度（0..1），不是假动画。</summary>
    private static async Task CopyWithProgressAsync(
        Stream source, Stream target, long totalBytes, IProgress<double>? progress, CancellationToken ct)
    {
        var buffer = new byte[256 * 1024];
        long received = 0;
        int read;
        while ((read = await source.ReadAsync(buffer.AsMemory(), ct).ConfigureAwait(false)) > 0)
        {
            await target.WriteAsync(buffer.AsMemory(0, read), ct).ConfigureAwait(false);
            received += read;
            if (totalBytes > 0)
            {
                progress?.Report(Math.Clamp((double)received / totalBytes, 0, 1));
            }
        }

        progress?.Report(1);
    }

    public async Task<ChatSendResult> RecallMessageAsync(ChatMessage message, CancellationToken cancellation = default)
    {
        if (message is null || string.IsNullOrEmpty(message.Id))
        {
            return ChatSendResult.Failure("消息还没发送完成，无法撤回");
        }

        if (!message.IsMine)
        {
            return ChatSendResult.Failure("只能撤回自己的消息");
        }

        try
        {
            var result = await _api
                .PostAsync<ChatMessageDto>($"/api/chat/messages/{message.Id}/recall", new Dictionary<string, object?>())
                .ConfigureAwait(false);

            if (result is null)
            {
                var networkError = LoadRetry.Humanize(_api.LastError, "撤回失败，请重试");
                SetChatError(networkError);
                return ChatSendResult.Failure(networkError);
            }

            if (result.Code is not (200 or 201))
            {
                var status = _api.LastHttpStatus > 0 ? _api.LastHttpStatus : result.Code;
                var error = status switch
                {
                    400 => string.IsNullOrWhiteSpace(result.Message) ? "超过撤回时限（2 分钟内可撤回）" : result.Message!,
                    403 => "只能撤回自己的消息",
                    404 => "消息不存在或已被删除",
                    _ => string.IsNullOrWhiteSpace(result.Message) ? "撤回失败，请重试" : result.Message!
                };
                SetChatError(error);
                return ChatSendResult.Failure(error);
            }

            Ui(() => MarkMessageRecalled(message.Id, message.ConversationId));
            return new ChatSendResult { Success = true, Message = message };
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            return ChatSendResult.Failure("已取消");
        }
        catch (Exception ex)
        {
            var error = $"撤回失败：{ex.Message}";
            SetChatError(error);
            return ChatSendResult.Failure(error);
        }
    }

    public async Task<ChatReadState?> LoadReadStateAsync(string conversationId, CancellationToken cancellation = default)
    {
        if (string.IsNullOrEmpty(conversationId))
        {
            return null;
        }

        try
        {
            var result = await _api
                .GetAsync<ChatReadStateDto>($"/api/chat/conversations/{conversationId}/read-state")
                .ConfigureAwait(false);
            if (result?.Data is null)
            {
                return null;
            }

            var dto = result.Data;
            var members = dto.Members
                .Where(member => !string.IsNullOrEmpty(member.UserId))
                .Select(member => new ChatReadStateMember
                {
                    UserId = member.UserId!,
                    Nickname = member.Nickname ?? "",
                    LastReadMessageId = member.LastReadMessageId,
                    LastReadAt = member.LastReadAtMillis is > 0 ? FromMillisOrNow(member.LastReadAtMillis) : null
                })
                .ToList();

            var peer = members.FirstOrDefault(member =>
                !string.Equals(member.UserId, CurrentChatUserId, StringComparison.Ordinal));

            Ui(() =>
            {
                if (peer is not null)
                {
                    _chatPeerReadIds[conversationId] = peer.LastReadMessageId;
                }

                ApplyReadReceipts(conversationId);
                ChatChanged?.Invoke(this, EventArgs.Empty);
            });

            return new ChatReadState { ConversationId = conversationId, Members = members };
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            return null;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[chat] read-state failed: {ex.Message}");
            return null;
        }
    }

    public Task<bool> SetPinnedAsync(string conversationId, bool pinned, CancellationToken cancellation = default) =>
        PatchConversationAsync(conversationId, new Dictionary<string, object?> { ["pinned"] = pinned }, cancellation);

    public Task<bool> SetMutedAsync(string conversationId, bool muted, CancellationToken cancellation = default) =>
        PatchConversationAsync(conversationId, new Dictionary<string, object?> { ["muted"] = muted }, cancellation);

    private async Task<bool> PatchConversationAsync(
        string conversationId,
        Dictionary<string, object?> body,
        CancellationToken cancellation = default)
    {
        if (string.IsNullOrEmpty(conversationId))
        {
            return false;
        }

        try
        {
            var result = await _api
                .PatchAsync<ChatConversationDto>($"/api/chat/conversations/{conversationId}", body)
                .ConfigureAwait(false);

            if (result?.Data is null)
            {
                var status = _api.LastHttpStatus;
                var error = status switch
                {
                    400 => string.IsNullOrWhiteSpace(result?.Message) ? "操作不被允许（置顶最多 20 个）" : result!.Message!,
                    403 => "你不是该会话成员，无权修改",
                    404 => "会话不存在",
                    _ => LoadRetry.Humanize(_api.LastError, "操作失败，请重试")
                };
                SetChatError(error);
                return false;
            }

            var dto = result.Data;
            ChatDiag($"patch conv={conversationId} dtoPinned={dto.Pinned} dtoMuted={dto.Muted} body={string.Join(",", body.Select(pair => pair.Key + "=" + pair.Value))}");
            Ui(() =>
            {
                var thread = FindThread(conversationId);
                if (thread is not null)
                {
                    // 以「本次请求的目标值」为准：PATCH 返回的 DTO 在部分部署里可能不带
                    // pinned/muted（历史版本），若照抄响应会把刚点下的开关弹回去。
                    thread.Pinned = body.TryGetValue("pinned", out var pinnedValue) && pinnedValue is bool pinned
                        ? pinned
                        : dto.Pinned;
                    thread.Muted = body.TryGetValue("muted", out var mutedValue) && mutedValue is bool muted
                        ? muted
                        : dto.Muted;
                    _threads = Ordered(_threads);
                }

                _chatError = null;
                ChatChanged?.Invoke(this, EventArgs.Empty);
            });
            return true;
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            return false;
        }
        catch (Exception ex)
        {
            SetChatError($"操作失败：{ex.Message}");
            return false;
        }
    }

    public async Task<IReadOnlyList<ChatSearchResult>> SearchMessagesAsync(
        string conversationId,
        string query,
        int limit = 20,
        CancellationToken cancellation = default)
    {
        if (string.IsNullOrWhiteSpace(conversationId) || string.IsNullOrWhiteSpace(query))
        {
            return [];
        }

        var keyword = query.Trim();
        if (keyword.Length > 64)
        {
            keyword = keyword[..64];
        }

        var url = $"/api/chat/conversations/{conversationId}/messages/search"
            + $"?q={Uri.EscapeDataString(keyword)}&limit={Math.Clamp(limit, 1, 50)}";

        try
        {
            var result = await _api.GetAsync<List<ChatSearchItemDto>>(url).ConfigureAwait(false);
            if (result?.Data is null)
            {
                SetChatError(LoadRetry.Humanize(_api.LastError, "搜索失败，请重试"));
                return [];
            }

            return result.Data
                .Where(item => !string.IsNullOrEmpty(item.Id))
                .Select(item => new ChatSearchResult
                {
                    Id = item.Id!,
                    Body = item.Body ?? "",
                    SenderName = item.SenderNickname ?? "",
                    SentAt = FromMillisOrNow(item.CreatedAtMillis)
                })
                .ToList();
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            return [];
        }
        catch (Exception ex)
        {
            SetChatError($"搜索失败：{ex.Message}");
            return [];
        }
    }

    // ── WebSocket ─────────────────────────────────────────────

    private void StartChatSocket()
    {
        if (_chatSocket is not null || !_api.IsLoggedIn)
        {
            return;
        }

        _chatSocket = new ChatSocket(
            () => _api.BaseUrl,
            () => _api.AccessToken,
            OnChatSocketState,
            OnChatFrame);
        _chatSocket.Start();
    }

    private void OnChatSocketState(ChatConnectionState state)
    {
        var mapped = state switch
        {
            ChatConnectionState.Connected => ChatConnectionState.Connected,
            ChatConnectionState.Connecting => ChatConnectionState.Connecting,
            _ => _api.IsLoggedIn ? ChatConnectionState.Polling : ChatConnectionState.Offline
        };

        if (_chatState == mapped)
        {
            return;
        }

        _chatState = mapped;
        Ui(() => ConnectionChanged?.Invoke(this, EventArgs.Empty));

        if (mapped == ChatConnectionState.Connected)
        {
            // 重连成功：补拉会话列表 + 当前会话 afterId 之后的消息。
            _ = Task.Run(CatchUpAfterReconnectAsync);
        }
    }

    private async Task CatchUpAfterReconnectAsync()
    {
        try
        {
            await RefreshConversationsAsync().ConfigureAwait(false);
            await RefreshUnreadAsync().ConfigureAwait(false);
            var conversationId = _chatActiveConversationId;
            if (string.IsNullOrEmpty(conversationId))
            {
                return;
            }

            var lastId = LastMessageId(conversationId!);
            if (!string.IsNullOrEmpty(lastId))
            {
                await LoadMessagesAsync(conversationId!, afterId: lastId).ConfigureAwait(false);
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[chat] catch-up failed: {ex.Message}");
        }
    }

    private void OnChatFrame(string frame)
    {
        try
        {
            using var document = JsonDocument.Parse(frame);
            var root = document.RootElement;
            // 生产部署有两种帧格式：{"t":...,"d":...} 与 {"type":...,"data":...}，两种都认。
            if (root.ValueKind != JsonValueKind.Object
                || !TryFrameField(root, "t", "type", out var typeElement))
            {
                return;
            }

            var type = typeElement.GetString();
            if (!TryFrameField(root, "d", "data", out var data) || data.ValueKind != JsonValueKind.Object)
            {
                return;
            }

            if (string.Equals(type, "read", StringComparison.Ordinal))
            {
                // 已读回执推送：更新对端已读位置（自己最后一条消息显示「已读」）。
                HandleReadFrame(data);
                return;
            }

            if (!string.Equals(type, "msg", StringComparison.Ordinal))
            {
                // pong / connected / ping：连接保活用，界面无需处理。
                return;
            }

            var dto = data.Deserialize<ChatMessageDto>(ChatJsonOptions);
            if (dto?.Id is null)
            {
                return;
            }

            HandleIncomingDto(dto);
        }
        catch (JsonException)
        {
            // 坏帧忽略，不能因为一条推送解析失败把连接搞挂
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[chat] frame failed: {ex.GetType().Name}");
        }
    }

    private void HandleIncomingDto(ChatMessageDto dto)
    {
        if (dto.Recalled)
        {
            MarkMessageRecalled(dto.Id!, dto.ConversationId ?? "");
            return;
        }

        if (dto.Deleted)
        {
            MarkMessageDeleted(dto.Id!);
            return;
        }

        var thread = FindThread(dto.ConversationId ?? "");
        var message = MapMessage(dto, thread?.WorkspaceId ?? "personal");
        if (message is null)
        {
            return;
        }

        if (thread is null && !string.IsNullOrEmpty(dto.ConversationId))
        {
            // 新会话的第一条消息：列表里还没有，拉一次会话列表。
            _ = RefreshConversationsAsync();
        }

        ChatDiag($"incoming id={message.Id} conv={message.ConversationId} mine={message.IsMine} active={_chatActiveConversationId} thread={thread?.Id}");

        if (!message.IsMine)
        {
            // 本地增量先让界面动，随后以服务端聚合为准校准（合并 300ms 内的多次推送）。
            RefreshUnreadSoon();
        }

        Ui(() =>
        {
            var list = MessagesFor(message.ConversationId);

            // 自己的消息在别的端发出（当前后端只回显给其它端，但补拉/换端时会出现）：
            // 优先按 id/clientMsgId 合并到已有消息上，避免重复气泡。
            var existing = FindMessage(list, message.Id, message.ClientMsgId);
            if (existing is not null)
            {
                existing.Id = message.Id;
                existing.Text = message.Text;
                existing.SentAt = message.SentAt;
                existing.SendState = ChatSendState.Sent;
                existing.IsDeleted = false;
            }
            else
            {
                list.Add(message);
                ApplyDaySeparators(list);
            }

            UpdateThreadForMessage(message, incoming: true);
            MessageReceived?.Invoke(this, message);
        });
    }

    private void MarkMessageDeleted(string id)
    {
        if (string.IsNullOrEmpty(id))
        {
            return;
        }

        Ui(() =>
        {
            foreach (var list in _chatMessages.Values)
            {
                var message = FindMessage(list, id, null);
                if (message is null)
                {
                    continue;
                }

                message.IsDeleted = true;
                message.Text = "";
                var thread = FindThread(message.ConversationId);
                if (thread is not null && thread.LastMessage == "消息已删除")
                {
                    // 无需处理
                }
                else if (thread is not null)
                {
                    thread.LastMessage = "消息已删除";
                }

                ChatChanged?.Invoke(this, EventArgs.Empty);
                return;
            }
        });
    }

    /// <summary>把一条已发出的消息标记为撤回（正文与文件都隐藏，气泡换成一行提示）。</summary>
    private void MarkMessageRecalled(string id, string conversationId)
    {
        if (string.IsNullOrEmpty(id))
        {
            return;
        }

        Ui(() =>
        {
            foreach (var list in _chatMessages.Values)
            {
                var message = FindMessage(list, id, null);
                if (message is null)
                {
                    continue;
                }

                message.Recalled = true;
                message.Text = "";
                message.IsDeleted = false;
                var thread = FindThread(message.ConversationId);
                if (thread is not null && IsLastMessage(list, message))
                {
                    thread.LastMessage = "撤回了一条消息";
                }

                ApplyReadReceipts(message.ConversationId);
                ChatChanged?.Invoke(this, EventArgs.Empty);
                return;
            }
        });
    }

    /// <summary>该消息是否是列表里最后一条已确认（有 id）的消息。</summary>
    private static bool IsLastMessage(ObservableCollection<ChatMessage> list, ChatMessage message)
    {
        for (var i = list.Count - 1; i >= 0; i--)
        {
            if (!string.IsNullOrEmpty(list[i].Id))
            {
                return ReferenceEquals(list[i], message);
            }
        }

        return false;
    }

    /// <summary>已读回执推送：d = {conversationId,userId,messageId}。</summary>
    private void HandleReadFrame(JsonElement data)
    {
        var conversationId = ReadSnowflake(data, "conversationId");
        var userId = ReadSnowflake(data, "userId");
        var messageId = ReadSnowflake(data, "messageId");
        if (string.IsNullOrEmpty(conversationId) || string.IsNullOrEmpty(userId) || string.IsNullOrEmpty(messageId))
        {
            return;
        }

        if (string.Equals(userId, CurrentChatUserId, StringComparison.Ordinal))
        {
            // 自己的已读位置不改变「对方已读我的哪条」回执。
            return;
        }

        Ui(() =>
        {
            _chatPeerReadIds[conversationId!] = messageId;
            ApplyReadReceipts(conversationId!);
            ChatChanged?.Invoke(this, EventArgs.Empty);
        });
    }

    private static string? ReadSnowflake(JsonElement data, string property)
    {
        if (!data.TryGetProperty(property, out var element))
        {
            return null;
        }

        return element.ValueKind switch
        {
            JsonValueKind.String => element.GetString(),
            JsonValueKind.Number => element.GetRawText(),
            _ => null
        };
    }

    /// <summary>按对端已读位置刷新自己消息的「已读」回执（只标最后一条自己发出的消息）。</summary>
    private void ApplyReadReceipts(string conversationId)
    {
        var list = MessagesFor(conversationId);
        _chatPeerReadIds.TryGetValue(conversationId, out var peerReadId);

        ChatMessage? lastMine = null;
        foreach (var message in list)
        {
            if (message.IsMine && !string.IsNullOrEmpty(message.Id) && !message.Recalled && !message.IsDeleted)
            {
                lastMine = message;
            }
        }

        foreach (var message in list)
        {
            if (!message.IsMine)
            {
                if (message.ReadReceipt.Length > 0)
                {
                    message.ReadReceipt = "";
                }

                continue;
            }

            var read = !string.IsNullOrEmpty(peerReadId)
                && !string.IsNullOrEmpty(message.Id)
                && CompareSnowflake(message.Id, peerReadId!) <= 0;
            message.ReadByPeer = read;
            // 私聊：最后一条自己发的消息显示「已读/未读」；工作区频道不展示（多成员语义不同）。
            var isDirect = _threads.FirstOrDefault(thread => thread.Id == conversationId)?.IsChannel != true;
            message.ReadReceipt = ReferenceEquals(message, lastMine)
                ? (read ? "已读" : isDirect ? "未读" : "")
                : "";
        }
    }

    /// <summary>雪花 id 文本比较（等宽零填充后按序比较，绝不解析成数值）。</summary>
    internal static int CompareSnowflake(string left, string right) =>
        string.CompareOrdinal(left.PadLeft(20, '0'), right.PadLeft(20, '0'));

    /// <summary>兼容两种帧字段命名（t/d 与 type/data）。</summary>
    private static bool TryFrameField(JsonElement root, string primary, string secondary, out JsonElement value)
    {
        if (root.TryGetProperty(primary, out value))
        {
            return true;
        }

        return root.TryGetProperty(secondary, out value);
    }

    // ── 轮询降级 ──────────────────────────────────────────────

    private void StartChatPolling()
    {
        if (_chatPollTask is not null)
        {
            return;
        }

        _chatPollCts = new CancellationTokenSource();
        var ct = _chatPollCts.Token;
        _chatPollTask = Task.Run(async () =>
        {
            while (!ct.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(ChatPollIntervalMs, ct).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }

                if (ct.IsCancellationRequested || !_api.IsLoggedIn)
                {
                    continue;
                }

                if (_chatState == ChatConnectionState.Connected)
                {
                    continue;
                }

                try
                {
                    await RefreshConversationsAsync(ct).ConfigureAwait(false);
                    await RefreshUnreadAsync(ct).ConfigureAwait(false);
                    var conversationId = _chatActiveConversationId;
                    if (string.IsNullOrEmpty(conversationId))
                    {
                        continue;
                    }

                    var lastId = LastMessageId(conversationId!);
                    if (!string.IsNullOrEmpty(lastId))
                    {
                        await LoadMessagesAsync(conversationId!, afterId: lastId).ConfigureAwait(false);
                    }
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"[chat] poll failed: {ex.Message}");
                }
            }
        }, ct);
    }

    // ── 映射 / 合并 ───────────────────────────────────────────

    private string CurrentChatUserId =>
        _currentUserId > 0 ? _currentUserId.ToString() : Bond.Core.JwtUtil.GetUserId(_api.AccessToken ?? "")?.ToString() ?? "";

    private bool IsMine(string? senderId) =>
        !string.IsNullOrEmpty(senderId) && string.Equals(senderId, CurrentChatUserId, StringComparison.Ordinal);

    private ChatThread? FindThread(string conversationId)
    {
        if (string.IsNullOrEmpty(conversationId))
        {
            return null;
        }

        return _threads.FirstOrDefault(thread => string.Equals(thread.Id, conversationId, StringComparison.Ordinal));
    }

    private static ChatThread? MapThread(ChatConversationDto dto)
    {
        if (string.IsNullOrEmpty(dto.Id))
        {
            return null;
        }

        var isChannel = string.Equals(dto.Type, "workspace", StringComparison.OrdinalIgnoreCase);
        var title = isChannel
            ? FirstNotEmpty(dto.WorkspaceName, "工作区频道")
            : FirstNotEmpty(dto.PeerNickname, dto.PeerUserId, "私聊");

        return new ChatThread
        {
            Id = dto.Id!,
            Title = title ?? "会话",
            Type = isChannel ? "workspace" : "direct",
            PeerUserId = dto.PeerUserId,
            WorkspaceId = dto.WorkspaceId,
            AvatarUrl = dto.PeerAvatarUrl,
            LastMessage = PreviewText(dto.LastMessagePreview),
            LastAt = FromMillisOrNow(dto.LastMessageAtMillis ?? dto.UpdatedAtMillis),
            Unread = dto.UnreadCount,
            Pinned = dto.Pinned,
            Muted = dto.Muted
        };
    }

    private ChatMessage? MapMessage(ChatMessageDto dto, string spaceId)
    {
        if (string.IsNullOrEmpty(dto.Id) || string.IsNullOrEmpty(dto.ConversationId))
        {
            return null;
        }

        var kind = string.IsNullOrEmpty(dto.Kind) ? "text" : dto.Kind!;
        return new ChatMessage
        {
            ConversationId = dto.ConversationId!,
            Kind = kind,
            SenderId = dto.SenderId ?? "",
            SenderName = dto.SenderNickname ?? "",
            IsMine = IsMine(dto.SenderId),
            ClientMsgId = dto.ClientMsgId,
            FileId = dto.FileId,
            FileName = dto.FileName ?? "",
            FileSize = dto.FileSize ?? 0,
            FileContentType = dto.FileContentType,
            // 附件（图片/文件）所在空间随会话走；自己发的附件据此拼请求路径，
            // 私聊为 personal、工作区频道为 workspace id（错用会 404 -> 缩略图空白）。
            SpaceId = spaceId,
            Id = dto.Id!,
            Text = dto.Body ?? "",
            SentAt = FromMillisOrNow(dto.CreatedAtMillis),
            IsDeleted = dto.Deleted,
            Recalled = dto.Recalled
        };
    }

    private void ApplyConversations(List<ChatThread> fresh)
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var next = new List<ChatThread>(fresh.Count);
        foreach (var item in fresh)
        {
            if (!seen.Add(item.Id))
            {
                continue;
            }

            var existing = FindThread(item.Id);
            if (existing is not null)
            {
                UpdateThread(existing, item);
                next.Add(existing);
            }
            else
            {
                next.Add(item);
            }
        }

        // 整体替换引用：绑定方只感知一次重绑定，不会在我们自己的事件回调里
        // 对 ObservableCollection 做 Add/RemoveAt/Move（那是 Avalonia 弱事件派发崩溃的来源）。
        _threads = Ordered(next);
    }

    /// <summary>置顶会话优先，其次按最后消息时间倒序；同一时刻按 id 稳定排序。</summary>
    private static List<ChatThread> Ordered(IEnumerable<ChatThread> threads) => threads
        .OrderByDescending(thread => thread.Pinned)
        .ThenByDescending(thread => thread.LastAt)
        .ThenBy(thread => thread.Id, StringComparer.Ordinal)
        .ToList();

    private static void UpdateThread(ChatThread target, ChatThread source)
    {
        if (!string.IsNullOrEmpty(source.Title))
        {
            target.Title = source.Title;
        }

        target.Type = source.Type;
        target.PeerUserId = source.PeerUserId;
        target.WorkspaceId = source.WorkspaceId;
        target.AvatarUrl = source.AvatarUrl;
        target.LastMessage = source.LastMessage;
        target.LastAt = source.LastAt;
        target.Unread = source.Unread;
        target.Pinned = source.Pinned;
        target.Muted = source.Muted;
    }

    private void UpdateThreadForMessage(ChatMessage message, bool incoming)
    {
        var thread = FindThread(message.ConversationId);
        if (thread is null)
        {
            return;
        }

        thread.LastMessage = Preview(message);
        thread.LastAt = message.SentAt;

        // 只有「别人发的 + 当前没打开该会话」才计未读；打开中的会话由界面调用 MarkReadAsync 清零。
        if (incoming && !message.IsMine
            && !string.Equals(_chatActiveConversationId, message.ConversationId, StringComparison.Ordinal))
        {
            thread.Unread++;
            BumpUnreadTotal(+1);
        }

        ChatDiag($"update conv={message.ConversationId} incoming={incoming} mine={message.IsMine} active={_chatActiveConversationId} unread={thread.Unread} total={_chatUnreadTotal}");

        // 置顶同样用整体替换，避免 Move 触发的集合变更派发。
        _threads = Ordered(_threads);
    }

    private static string Preview(ChatMessage message)
    {
        if (message.Recalled)
        {
            return "撤回了一条消息";
        }

        if (message.IsDeleted)
        {
            return "消息已删除";
        }

        return message.Kind switch
        {
            "image" => string.IsNullOrEmpty(message.FileName) ? "[图片]" : $"[图片] {message.FileName}",
            "file" => string.IsNullOrEmpty(message.FileName) ? "[文件]" : $"[文件] {message.FileName}",
            "system" => message.Text,
            _ => Truncate(message.Text.Replace('\n', ' ').Replace('\r', ' '))
        };
    }

    private static string PreviewText(string? text) =>
        string.IsNullOrEmpty(text) ? "" : Truncate(text.Replace('\n', ' ').Replace('\r', ' '));

    private static string Truncate(string text) => text.Length <= 60 ? text : text[..60] + "…";

    private void ApplyDaySeparators(ObservableCollection<ChatMessage> list)
    {
        DateTime? previousDay = null;
        ChatMessage? previous = null;
        foreach (var message in list)
        {
            var day = message.SentAt.Date;
            var isNewDay = previousDay != day;
            message.ShowDaySeparator = isNewDay;
            if (isNewDay)
            {
                message.DayText = DayText(message.SentAt);
            }

            // task-166 UI 重做：同一发送者、同一天、5 分钟内的连续消息合并为一组，
            // 只有组末消息显示头像与 hover 时间；组内间距 2，组间 12。
            var continuesGroup = previous is not null
                && !isNewDay
                && !message.IsSystem
                && !previous.IsSystem
                && string.Equals(message.SenderId, previous.SenderId, StringComparison.Ordinal)
                && message.SentAt >= previous.SentAt
                && message.SentAt - previous.SentAt <= GroupWindow;
            message.IsGroupStart = !continuesGroup;
            if (previous is not null)
            {
                previous.IsGroupEnd = !continuesGroup;
            }

            message.IsGroupEnd = true;
            previousDay = day;
            previous = message;
        }

        MergeRecallNotices(list);
    }

    /// <summary>
    /// task-166 审阅修复：相邻同发送者的撤回提示合并成一行（「ccc 撤回了 3 条消息」），
    /// 其余行隐藏，避免每条撤回各占一行把消息流撑散。
    /// </summary>
    private static void MergeRecallNotices(ObservableCollection<ChatMessage> list)
    {
        foreach (var message in list)
        {
            message.RecallRowVisible = true;
            message.RecallAggregateText = null;
        }

        for (var i = 0; i < list.Count;)
        {
            if (!list[i].IsRecalled)
            {
                i++;
                continue;
            }

            var end = i;
            while (end + 1 < list.Count
                   && list[end + 1].IsRecalled
                   && string.Equals(list[end + 1].SenderId, list[i].SenderId, StringComparison.Ordinal)
                   && list[end + 1].SentAt.Date == list[i].SentAt.Date)
            {
                end++;
            }

            var count = end - i + 1;
            for (var k = i; k <= end; k++)
            {
                list[k].RecallRowVisible = k == i;
            }

            if (count > 1)
            {
                list[i].RecallAggregateText = list[i].IsMine
                    ? $"你撤回了 {count} 条消息"
                    : $"{list[i].SenderLabel} 撤回了 {count} 条消息";
            }

            i = end + 1;
        }
    }

    /// <summary>在消息流里标出「未读分割线」（进入会话时的第一条未读消息）。</summary>
    public void MarkUnreadDivider(string conversationId, int unread)
    {
        if (!_chatMessages.TryGetValue(conversationId, out var list))
        {
            return;
        }

        foreach (var message in list)
        {
            message.ShowUnreadDivider = false;
        }

        if (unread <= 0 || list.Count == 0)
        {
            return;
        }

        var index = Math.Max(0, list.Count - unread);
        list[index].ShowUnreadDivider = true;
    }

    private static string DayText(DateTime at)
    {
        if (at.Date == DateTime.Today)
        {
            return "今天";
        }

        if (at.Date == DateTime.Today.AddDays(-1))
        {
            return "昨天";
        }

        return at.Year == DateTime.Today.Year ? at.ToString("M月d日") : at.ToString("yyyy年M月d日");
    }

    private static bool ContainsMessage(ObservableCollection<ChatMessage> list, ChatMessage message) =>
        list.Any(item => SameMessage(item, message));

    private static ChatMessage? FindMessage(ObservableCollection<ChatMessage> list, string? id, string? clientMsgId) =>
        list.FirstOrDefault(item =>
            (!string.IsNullOrEmpty(id) && string.Equals(item.Id, id, StringComparison.Ordinal))
            || (!string.IsNullOrEmpty(clientMsgId) && string.Equals(item.ClientMsgId, clientMsgId, StringComparison.Ordinal)));

    private static bool SameMessage(ChatMessage left, ChatMessage right) =>
        (!string.IsNullOrEmpty(left.Id) && string.Equals(left.Id, right.Id, StringComparison.Ordinal))
        || (!string.IsNullOrEmpty(left.ClientMsgId) && string.Equals(left.ClientMsgId, right.ClientMsgId, StringComparison.Ordinal));

    /// <summary>去重（同 id / 同 clientMsgId）并按时间升序重排。</summary>
    private static void DeduplicateAndSort(ObservableCollection<ChatMessage> list)
    {
        var unique = new List<ChatMessage>();
        foreach (var message in list)
        {
            if (unique.Any(item => SameMessage(item, message)))
            {
                continue;
            }

            unique.Add(message);
        }

        if (unique.Count == list.Count)
        {
            return;
        }

        // task-135：去重后同样不能 Clear（Reset 会残留容器），逐项同步即可。
        CollectionSync.Apply(list, unique);
    }

    private string? LastMessageId(string conversationId)
    {
        var list = MessagesFor(conversationId);
        for (var i = list.Count - 1; i >= 0; i--)
        {
            if (!string.IsNullOrEmpty(list[i].Id))
            {
                return list[i].Id;
            }
        }

        return null;
    }

    private static string? FirstNotEmpty(params string?[] values) =>
        values.FirstOrDefault(value => !string.IsNullOrWhiteSpace(value));

    private static string HumanizeSendError(ApiResult<ChatMessageDto>? result)
    {
        if (result is null)
        {
            return LoadRetry.Humanize(null, "发送失败，请检查网络后重试");
        }

        var message = result.Message;
        return result.Code switch
        {
            429 => "发送太频繁了，稍等一分钟再试",
            400 => string.IsNullOrWhiteSpace(message) ? "消息内容不合法" : message!,
            403 => "没有权限在这个会话里发消息",
            413 => "消息太长，超出了服务端限制",
            _ => string.IsNullOrWhiteSpace(message) ? "发送失败，请重试" : message!
        };
    }

    private void SetChatError(string? error)
    {
        _chatError = error;
        Ui(() => ChatChanged?.Invoke(this, EventArgs.Empty));
    }

    private void SetChatState(ChatConnectionState state)
    {
        if (_chatState == state)
        {
            return;
        }

        _chatState = state;
        Ui(() => ConnectionChanged?.Invoke(this, EventArgs.Empty));
    }

    // ── DTO（字段名与生产后端一致；雪花 id 用字符串）──────────

    private sealed class ChatConversationDto
    {
        [JsonPropertyName("id")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? Id { get; set; }

        [JsonPropertyName("type")]
        public string? Type { get; set; }

        [JsonPropertyName("workspaceId")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? WorkspaceId { get; set; }

        [JsonPropertyName("peerUserId")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? PeerUserId { get; set; }

        [JsonPropertyName("peerNickname")]
        public string? PeerNickname { get; set; }

        [JsonPropertyName("peerAvatarUrl")]
        public string? PeerAvatarUrl { get; set; }

        [JsonPropertyName("workspaceName")]
        public string? WorkspaceName { get; set; }

        [JsonPropertyName("lastMessageId")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? LastMessageId { get; set; }

        [JsonPropertyName("lastMessagePreview")]
        public string? LastMessagePreview { get; set; }

        [JsonPropertyName("lastMessageAtMillis")]
        public long? LastMessageAtMillis { get; set; }

        [JsonPropertyName("updatedAtMillis")]
        public long? UpdatedAtMillis { get; set; }

        [JsonPropertyName("unreadCount")]
        public int UnreadCount { get; set; }

        /// <summary>task-139：置顶（会话成员维度）。</summary>
        [JsonPropertyName("pinned")]
        public bool Pinned { get; set; }

        /// <summary>task-139：免打扰。</summary>
        [JsonPropertyName("muted")]
        public bool Muted { get; set; }
    }

    private sealed class ChatMessageDto
    {
        [JsonPropertyName("id")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? Id { get; set; }

        [JsonPropertyName("conversationId")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? ConversationId { get; set; }

        [JsonPropertyName("senderId")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? SenderId { get; set; }

        [JsonPropertyName("senderNickname")]
        public string? SenderNickname { get; set; }

        [JsonPropertyName("kind")]
        public string? Kind { get; set; }

        [JsonPropertyName("body")]
        public string? Body { get; set; }

        [JsonPropertyName("fileId")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? FileId { get; set; }

        [JsonPropertyName("fileName")]
        public string? FileName { get; set; }

        [JsonPropertyName("fileSize")]
        public long? FileSize { get; set; }

        [JsonPropertyName("fileContentType")]
        public string? FileContentType { get; set; }

        [JsonPropertyName("clientMsgId")]
        public string? ClientMsgId { get; set; }

        [JsonPropertyName("createdAtMillis")]
        public long? CreatedAtMillis { get; set; }

        [JsonPropertyName("deleted")]
        public bool Deleted { get; set; }

        /// <summary>task-139：已撤回（撤回后 body/fileId 为空）。</summary>
        [JsonPropertyName("recalled")]
        public bool Recalled { get; set; }

        /// <summary>task-139：撤回时间（毫秒）。</summary>
        [JsonPropertyName("recalledAtMillis")]
        public long? RecalledAtMillis { get; set; }
    }

    private sealed class ChatFriendDto
    {
        [JsonPropertyName("id")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? Id { get; set; }

        [JsonPropertyName("userId")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? UserId { get; set; }

        [JsonPropertyName("username")]
        public string? Username { get; set; }

        [JsonPropertyName("nickname")]
        public string? Nickname { get; set; }

        [JsonPropertyName("avatarUrl")]
        public string? AvatarUrl { get; set; }
    }

    // task-139：未读聚合 / 已读回执 / 会话内搜索的响应 DTO。

    private sealed class ChatUnreadDto
    {
        [JsonPropertyName("total")]
        public int Total { get; set; }

        [JsonPropertyName("byConversation")]
        public List<ChatUnreadItemDto>? ByConversation { get; set; }
    }

    private sealed class ChatUnreadItemDto
    {
        [JsonPropertyName("id")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? Id { get; set; }

        [JsonPropertyName("unread")]
        public int Unread { get; set; }
    }

    private sealed class ChatReadStateDto
    {
        [JsonPropertyName("conversationId")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? ConversationId { get; set; }

        [JsonPropertyName("members")]
        public List<ChatReadStateMemberDto> Members { get; set; } = [];
    }

    private sealed class ChatReadStateMemberDto
    {
        [JsonPropertyName("userId")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? UserId { get; set; }

        [JsonPropertyName("nickname")]
        public string? Nickname { get; set; }

        [JsonPropertyName("lastReadMessageId")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? LastReadMessageId { get; set; }

        [JsonPropertyName("lastReadAtMillis")]
        public long? LastReadAtMillis { get; set; }
    }

    private sealed class ChatSearchItemDto
    {
        [JsonPropertyName("id")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? Id { get; set; }

        [JsonPropertyName("conversationId")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? ConversationId { get; set; }

        [JsonPropertyName("senderId")]
        [JsonConverter(typeof(SnowflakeStringConverter))]
        public string? SenderId { get; set; }

        [JsonPropertyName("senderNickname")]
        public string? SenderNickname { get; set; }

        [JsonPropertyName("body")]
        public string? Body { get; set; }

        [JsonPropertyName("createdAtMillis")]
        public long? CreatedAtMillis { get; set; }
    }
}

/// <summary>
/// 雪花 id 读取器：后端序列化成字符串，但也容忍数字编码（按原始文本返回，绝不经过 double/long 转换丢精度）。
/// </summary>
internal sealed class SnowflakeStringConverter : JsonConverter<string?>
{
    public override string? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        reader.TokenType switch
        {
            JsonTokenType.String => reader.GetString(),
            JsonTokenType.Number => ReadRawNumber(ref reader),
            JsonTokenType.Null => null,
            _ => throw new JsonException($"无法把 {reader.TokenType} 解析成雪花 id")
        };

    public override void Write(Utf8JsonWriter writer, string? value, JsonSerializerOptions options)
    {
        if (value is null)
        {
            writer.WriteNullValue();
        }
        else
        {
            writer.WriteStringValue(value);
        }
    }

    private static string ReadRawNumber(ref Utf8JsonReader reader)
    {
        if (!reader.HasValueSequence)
        {
            return Encoding.UTF8.GetString(reader.ValueSpan);
        }

        return Encoding.UTF8.GetString(reader.ValueSequence.ToArray());
    }
}
