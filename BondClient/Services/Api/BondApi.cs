using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Threading.Tasks;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Bond.Services.Api;

// ═══════════════════════════════════════════════════════════════
//  DTO —— 与 BondServer 现有契约一致（字段名照抄旧客户端，避免猜）
// ═══════════════════════════════════════════════════════════════

/// <summary>
/// 最近一次失败的类别：用于把「网络不可达 / HTTP 错误 / 响应解析失败」区分开，
/// 避免像 2026-10-23 那样把 DTO 反序列化失败误报成「无法连接服务器」。
/// </summary>
public enum ApiFailureKind
{
    None,
    Network,
    Http,
    Json
}

public class ApiResult
{
    [JsonPropertyName("code")] public int Code { get; set; }
    [JsonPropertyName("message")] public string Message { get; set; } = "";
}

public class ApiResult<T> : ApiResult
{
    [JsonPropertyName("data")] public T? Data { get; set; }

    public bool IsOk => Code == 200;
}

public class AuthTokenDto
{
    [JsonPropertyName("accessToken")] public string AccessToken { get; set; } = "";
    [JsonPropertyName("refreshToken")] public string RefreshToken { get; set; } = "";
    [JsonPropertyName("expiresAt")] public long ExpiresAt { get; set; }
}

/// <summary>POST /api/auth/email/code 的返回：cool 倒计时秒数由服务端决定。</summary>
public class EmailCodeResult
{
    [JsonPropertyName("cooldownSeconds")] public int CooldownSeconds { get; set; }
}

public class UserDto
{    [JsonPropertyName("id")] public long Id { get; set; }
    [JsonPropertyName("username")] public string Username { get; set; } = "";
    [JsonPropertyName("nickname")] public string Nickname { get; set; } = "";
    [JsonPropertyName("email")] public string? Email { get; set; }
    [JsonPropertyName("avatarUrl")] public string? AvatarUrl { get; set; }
    [JsonPropertyName("accessToken")] public string? AccessToken { get; set; }
    [JsonPropertyName("refreshToken")] public string? RefreshToken { get; set; }
}

public class DeviceDto
{
    [JsonPropertyName("id")] public long Id { get; set; }
    [JsonPropertyName("userId")] public long UserId { get; set; }
    [JsonPropertyName("deviceName")] public string DeviceName { get; set; } = "";
    [JsonPropertyName("deviceType")] public string DeviceType { get; set; } = "";
    [JsonPropertyName("publicKey")] public string? PublicKey { get; set; }
    [JsonPropertyName("isOnline")] public bool IsOnline { get; set; }
    [JsonPropertyName("lastSeenAt")] public long? LastSeenAt { get; set; }
}

public class WorkspaceDto
{
    [JsonPropertyName("id")] public long Id { get; set; }
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("description")] public string? Description { get; set; }
    [JsonPropertyName("ownerId")] public long OwnerId { get; set; }
    [JsonPropertyName("memberCount")] public int MemberCount { get; set; }
    [JsonPropertyName("fileCount")] public int FileCount { get; set; }
    [JsonPropertyName("maxSize")] public long MaxSize { get; set; }
    [JsonPropertyName("usedSize")] public long UsedSize { get; set; }
    [JsonPropertyName("createdAt")] public long? CreatedAt { get; set; }
}

/// <summary>邀请链接（POST /api/workspaces/{id}/invite/link）。</summary>
public class InviteLinkDto
{
    [JsonPropertyName("url")] public string Url { get; set; } = "";
    [JsonPropertyName("token")] public string Token { get; set; } = "";
    [JsonPropertyName("expiresAt")] public long? ExpiresAt { get; set; }
}

public class WorkspaceFileDto
{
    [JsonPropertyName("id")] public long Id { get; set; }
    [JsonPropertyName("workspaceId")] public long WorkspaceId { get; set; }
    [JsonPropertyName("fileName")] public string FileName { get; set; } = "";
    [JsonPropertyName("fileSize")] public long FileSize { get; set; }
    [JsonPropertyName("contentType")] public string? ContentType { get; set; }
    [JsonPropertyName("uploadedBy")] public long UploadedBy { get; set; }
    [JsonPropertyName("parentPath")] public string ParentPath { get; set; } = "/";
    [JsonPropertyName("isDirectory")] public bool IsDirectory { get; set; }
    [JsonPropertyName("fileHash")] public string? FileHash { get; set; }
    [JsonPropertyName("createdAt")] public long? CreatedAt { get; set; }
}

public class WorkspaceMemberDto
{
    [JsonPropertyName("userId")] public long UserId { get; set; }
    [JsonPropertyName("username")] public string Username { get; set; } = "";
    [JsonPropertyName("nickname")] public string Nickname { get; set; } = "";
    [JsonPropertyName("role")] public int Role { get; set; }
}

// ── task-8：工作区成员文件级 ACL（Backend V34 / WorkspaceAclDtos） ──────────
//   GET  /api/workspaces/{id}/members/{userId}/acl  → WorkspaceAclDto（owner/admin）
//   PUT  同路径 body { accessMode, grants[] }        → 整包替换，返回替换后的视图
//   GET  /api/workspaces/{id}/acl/me                → 本人有效范围（任意成员）
// 字段照抄 Java record：View(Long workspaceId,Long userId,String accessMode,List<Grant> grants)、
// Grant(String scopeType,String scopePath,Long fileId,boolean canRead,boolean canWrite)。

/// <summary>一条 ACL 授权。</summary>
public class WorkspaceAclGrantDto
{
    /// <summary>folder / file。</summary>
    [JsonPropertyName("scopeType")] public string ScopeType { get; set; } = "";

    /// <summary>规范化逻辑路径（<c>/</c> 开头、无尾斜杠）；file 授权以服务端真实路径回填。</summary>
    [JsonPropertyName("scopePath")] public string ScopePath { get; set; } = "/";

    /// <summary>folder 授权为 null；file 授权必填（雪花 id，服务端按 Long 收）。</summary>
    [JsonPropertyName("fileId")] public long? FileId { get; set; }

    [JsonPropertyName("canRead")] public bool CanRead { get; set; }
    [JsonPropertyName("canWrite")] public bool CanWrite { get; set; }
}

/// <summary>成员 ACL 视图：<c>accessMode=all</c> 时 grants 恒为空（all 忽略授权表）。</summary>
public class WorkspaceAclDto
{
    [JsonPropertyName("workspaceId")] public long? WorkspaceId { get; set; }
    [JsonPropertyName("userId")] public long? UserId { get; set; }
    [JsonPropertyName("accessMode")] public string AccessMode { get; set; } = "all";
    [JsonPropertyName("grants")] public List<WorkspaceAclGrantDto>? Grants { get; set; }
}

public class TransferTaskDto
{
    [JsonPropertyName("id")] public long Id { get; set; }
    [JsonPropertyName("senderId")] public long SenderId { get; set; }
    [JsonPropertyName("receiverId")] public long ReceiverId { get; set; }

    /// <summary>发送方 / 接收方的设备行 id（设备互传时才有；用于客户端把 id 映射成设备名）。</summary>
    [JsonPropertyName("senderDeviceId")] public string? SenderDeviceId { get; set; }

    [JsonPropertyName("receiverDeviceId")] public string? ReceiverDeviceId { get; set; }

    /// <summary>对端用户名（服务端提供；拿不到设备名时的兜底展示，绝不再露雪花 id）。</summary>
    [JsonPropertyName("peerName")] public string? PeerName { get; set; }

    [JsonPropertyName("fileName")] public string FileName { get; set; } = "";
    [JsonPropertyName("fileSize")] public long FileSize { get; set; }
    [JsonPropertyName("chunkSize")] public int ChunkSize { get; set; }
    [JsonPropertyName("chunkCount")] public int ChunkCount { get; set; }
    [JsonPropertyName("status")] public int Status { get; set; }
    [JsonPropertyName("uploadedChunks")] public int UploadedChunks { get; set; }

    /// <summary>接收方已下载过的分片数（task-55 B：服务端幂等打点，下载方向进度用）。</summary>
    [JsonPropertyName("downloadedChunks")] public int DownloadedChunks { get; set; }

    [JsonPropertyName("transferType")] public string? TransferType { get; set; }
    [JsonPropertyName("createdAt")] public long? CreatedAt { get; set; }
}

/// <summary>个人空间配额（GET /api/files/quota）。</summary>
public class StorageQuotaDto
{
    [JsonPropertyName("usedBytes")] public long UsedBytes { get; set; }
    [JsonPropertyName("maxBytes")] public long MaxBytes { get; set; }
    [JsonPropertyName("fileCount")] public long FileCount { get; set; }
    [JsonPropertyName("planCode")] public string? PlanCode { get; set; }
}

/// <summary>计费权益（GET /api/entitlements/me）。</summary>
public class EntitlementsDto
{
    [JsonPropertyName("planCode")] public string? PlanCode { get; set; }

    [JsonPropertyName("planName")] public string? PlanName { get; set; }

    [JsonPropertyName("usage")] public EntitlementsUsageDto? Usage { get; set; }

    [JsonPropertyName("limits")] public EntitlementsLimitsDto? Limits { get; set; }

    [JsonPropertyName("isThrottled")] public bool IsThrottled { get; set; }
}

public class EntitlementsUsageDto
{
    [JsonPropertyName("relayBytesRemaining")] public long RelayBytesRemaining { get; set; }

    [JsonPropertyName("storageBytes")] public long StorageBytes { get; set; }
}

public class EntitlementsLimitsDto
{
    [JsonPropertyName("storageBytes")] public long StorageBytes { get; set; }

    [JsonPropertyName("relayBytesMonthly")] public long RelayBytesMonthly { get; set; }

    [JsonPropertyName("trashRetentionDays")] public int? TrashRetentionDays { get; set; }
}

/// <summary>
/// 分享链接（POST/GET/PATCH /api/shares）。
/// task-163 起时间字段以 <c>*Millis</c> 为准（ISO 的 createdAt/expiresAt 仅兼容保留），
/// 状态同时给 <c>state(active|expired|revoked)</c> 与数字 <c>status(0/1/2)</c>。
/// </summary>
public class ShareLinkDto
{
    [JsonPropertyName("id")] public long Id { get; set; }
    [JsonPropertyName("token")] public string Token { get; set; } = "";
    [JsonPropertyName("url")] public string Url { get; set; } = "";
    [JsonPropertyName("fileId")] public long FileId { get; set; }
    [JsonPropertyName("fileName")] public string FileName { get; set; } = "";
    [JsonPropertyName("fileSize")] public long FileSize { get; set; }

    /// <summary>
    /// task-183：文件夹分享为 true（<c>/download</c> 返回 ZIP）；文件分享为 false，
    /// 历史链接该字段可能为 null，客户端按 false 处理。
    /// </summary>
    [JsonPropertyName("isDirectory")] public bool? IsDirectory { get; set; }
    [JsonPropertyName("requiresCode")] public bool RequiresCode { get; set; }
    [JsonPropertyName("code")] public string? Code { get; set; }
    [JsonPropertyName("expiresAt")] public DateTimeOffset? ExpiresAt { get; set; }
    [JsonPropertyName("expiresAtMillis")] public long? ExpiresAtMillis { get; set; }
    [JsonPropertyName("maxDownloads")] public int MaxDownloads { get; set; }
    [JsonPropertyName("downloadCount")] public int DownloadCount { get; set; }
    [JsonPropertyName("status")] public int Status { get; set; }
    [JsonPropertyName("state")] public string? State { get; set; }
    [JsonPropertyName("createdAt")] public DateTimeOffset CreatedAt { get; set; }
    [JsonPropertyName("createdAtMillis")] public long? CreatedAtMillis { get; set; }
    [JsonPropertyName("lastAccessAtMillis")] public long? LastAccessAtMillis { get; set; }
}

/// <summary>预览短签 URL（GET .../preview-url），token 已在 query 里。</summary>
public class PreviewUrlDto
{
    [JsonPropertyName("contentUrl")] public string ContentUrl { get; set; } = "";
    [JsonPropertyName("thumbnailUrl")] public string ThumbnailUrl { get; set; } = "";
    /// <summary>办公文档（docx/xlsx/pptx…）的服务端转 PDF 地址；不支持/未启用时为 null 或空。</summary>
    [JsonPropertyName("officePdfUrl")] public string? OfficePdfUrl { get; set; }
    [JsonPropertyName("previewUrlTemplate")] public string? PreviewUrlTemplate { get; set; }
    [JsonPropertyName("expiresIn")] public long ExpiresIn { get; set; }
    [JsonPropertyName("contentType")] public string? ContentType { get; set; }
    [JsonPropertyName("fileName")] public string? FileName { get; set; }
}

/// <summary>聊天文件取流令牌（POST /api/chat/files/{messageId}/tokens）。URL 为相对路径，token 已在 query 里。</summary>
public class ChatFileTokenDto
{
    [JsonPropertyName("metaUrl")] public string MetaUrl { get; set; } = "";
    [JsonPropertyName("contentUrl")] public string ContentUrl { get; set; } = "";
    [JsonPropertyName("thumbnailUrl")] public string ThumbnailUrl { get; set; } = "";
    [JsonPropertyName("officePdfUrl")] public string? OfficePdfUrl { get; set; }
    [JsonPropertyName("expiresIn")] public long ExpiresIn { get; set; }
    [JsonPropertyName("fileName")] public string? FileName { get; set; }
    [JsonPropertyName("fileSize")] public long FileSize { get; set; }
    [JsonPropertyName("contentType")] public string? ContentType { get; set; }
    [JsonPropertyName("sha256")] public string? Sha256 { get; set; }
}

public class FriendRequestDto
{
    [JsonPropertyName("id")] public long Id { get; set; }
    [JsonPropertyName("fromUserId")] public long FromUserId { get; set; }
    [JsonPropertyName("fromUsername")] public string FromUsername { get; set; } = "";
    [JsonPropertyName("fromNickname")] public string FromNickname { get; set; } = "";
    [JsonPropertyName("message")] public string? Message { get; set; }
    [JsonPropertyName("createdAt")] public long? CreatedAt { get; set; }
}

public class InvitationDto
{
    [JsonPropertyName("id")] public long Id { get; set; }
    [JsonPropertyName("workspaceId")] public long WorkspaceId { get; set; }
    [JsonPropertyName("workspaceName")] public string WorkspaceName { get; set; } = "";
    [JsonPropertyName("inviterNickname")] public string InviterName { get; set; } = "";
    [JsonPropertyName("role")] public int Role { get; set; }
    [JsonPropertyName("createdAt")] public long? CreatedAt { get; set; }
    // 邀请链接校验（GET /api/invite/{token}）会返回下面两个字段：
    [JsonPropertyName("expiresAt")] public long? ExpiresAt { get; set; }
    [JsonPropertyName("status")] public int? Status { get; set; }
}

// ═══════════════════════════════════════════════════════════════
//  公告 DTO（task-7；GET /api/announcements，字段名照抄后端
//  com.bond.common.dto.AnnouncementDTO：id 由雪花 ID 序列化为字符串）
// ═══════════════════════════════════════════════════════════════

/// <summary>一条系统公告。<c>id</c> 后端用 ToStringSerializer 序列化，按字符串读最稳。</summary>
public class AnnouncementDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";

    [JsonPropertyName("title")] public string Title { get; set; } = "";

    [JsonPropertyName("content")] public string Content { get; set; } = "";

    /// <summary>info / warn / critical。</summary>
    [JsonPropertyName("level")] public string? Level { get; set; }

    [JsonPropertyName("publishedAtMillis")] public long? PublishedAtMillis { get; set; }

    [JsonPropertyName("expiresAtMillis")] public long? ExpiresAtMillis { get; set; }

    [JsonPropertyName("createdAtMillis")] public long? CreatedAtMillis { get; set; }

    [JsonPropertyName("read")] public bool Read { get; set; }

    /// <summary>管理端字段（用户端恒 0）。</summary>
    [JsonPropertyName("readCount")] public long ReadCount { get; set; }
}

/// <summary>GET /api/announcements/unread-count 的 data：<c>{"count": n}</c>。</summary>
public class AnnouncementUnreadCountDto
{
    [JsonPropertyName("count")] public long Count { get; set; }
}

// ═══════════════════════════════════════════════════════════════
//  ApiClient —— 直连 BondServer 网关
// ═══════════════════════════════════════════════════════════════

/// <summary>
/// Bond 后端客户端。
///
/// 与旧版 <c>BondClient/Services/ApiClient.cs</c> 的约定保持一致：
/// · 基址默认 https://bond-api.sg.skkk.uno（旧客户端 DefaultApiBaseUrl）
/// · Bearer token + 401 时自动用 refreshToken 换一次新 token 再重试
/// · token 落盘在 %LocalAppData%\Bond\auth.json
/// 差别：不再强制 JSON 源生成，改用大小写不敏感的反射序列化，便于快速对齐后端。
/// </summary>
public sealed partial class BondApiClient : IDisposable
{
    public const string DefaultBaseUrl = "https://bond-api.sg.skkk.uno";

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,

        // 服务端把雪花 ID（>2^53）序列化为字符串；桌面端同时兼容"数字或字符串"，
        // 保证后端灰度期间两种格式都不会炸。
        NumberHandling = JsonNumberHandling.AllowReadingFromString
    };

    private HttpClient _http;
    private readonly string _authFile;
    private string? _refreshToken;

    public BondApiClient(string? baseUrl = null)
    {
        BaseUrl = string.IsNullOrWhiteSpace(baseUrl) ? DefaultBaseUrl : baseUrl.Trim().TrimEnd('/');

        _http = CreateHttp(BaseUrl);

        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Bond");
        Directory.CreateDirectory(dir);
        _authFile = Path.Combine(dir, "auth.json");

        LoadTokens();
    }

    public string BaseUrl { get; private set; }

    private static HttpClient CreateHttp(string baseUrl)
    {
        var http = new HttpClient
        {
            BaseAddress = new Uri(baseUrl),
            // 上传 8MB 分片在低速链路上可能超过 30s；全局放宽到 300s，
            // 分片 PUT 另外用 CancellationToken 做 180s 单次超时。
            Timeout = TimeSpan.FromSeconds(300)
        };
        http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        return http;
    }

    /// <summary>
    /// 切换服务器地址并重建 HttpClient（BaseAddress 在首次请求后不可改）。
    /// 地址非法或与当前一致时返回 false；token 不在此处清理，由调用方决定。
    /// </summary>
    public bool ReconfigureBaseUrl(string? url)
    {
        var normalized = (url ?? string.Empty).Trim().TrimEnd('/');
        if (!Uri.TryCreate(normalized, UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps)
            || string.IsNullOrEmpty(uri.Host))
            return false;

        if (string.Equals(normalized, BaseUrl, StringComparison.OrdinalIgnoreCase)) return false;

        var http = CreateHttp(normalized);
        if (AccessToken is not null)
            http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", AccessToken);

        var old = _http;
        BaseUrl = normalized;
        _http = http;
        try { old.Dispose(); } catch { }

        LastError = null;
        return true;
    }


    public string? AccessToken { get; private set; }

    /// <summary>当前 token 所属用户名（令牌变化后立即更新）。</summary>
    public string? Username => Bond.Core.JwtUtil.GetUsername(AccessToken);

    /// <summary>有 token 且未过期才算已登录；过期时由 App 退回登录页。</summary>
    public bool IsLoggedIn =>
        !string.IsNullOrEmpty(AccessToken) && Bond.Core.JwtUtil.IsValid(AccessToken);

    public event EventHandler? AuthExpired;

    /// <summary>最近一次请求失败的真实原因（供界面展示，避免只显示"无法连接服务器"）。</summary>
    public string? LastError { get; private set; }

    /// <summary>最近一次响应的 HTTP 状态码（-1 = 没拿到响应，如网络异常）。</summary>
    public int LastHttpStatus { get; private set; } = -1;

    /// <summary>最近一次失败的类别（网络 / HTTP / JSON 解析）。</summary>
    public ApiFailureKind LastFailure { get; private set; } = ApiFailureKind.None;

    // ── token 管理 ──
    public void SetTokens(string accessToken, string? refreshToken)
    {
        AccessToken = accessToken;
        _refreshToken = refreshToken;
        _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", accessToken);

        try
        {
            File.WriteAllText(_authFile, JsonSerializer.Serialize(
                new AuthTokenDto { AccessToken = accessToken, RefreshToken = refreshToken ?? "" }, JsonOptions));
        }
        catch
        {
            // token 持久化失败不影响本次会话
        }
    }

    public void ClearTokens()
    {
        AccessToken = null;
        _refreshToken = null;
        _http.DefaultRequestHeaders.Authorization = null;
        try { if (File.Exists(_authFile)) File.Delete(_authFile); } catch { }
    }

    private void LoadTokens()
    {
        try
        {
            // 兼容旧客户端：首次运行时把 BondClient 的登录态迁移过来，避免重复登录
            if (!File.Exists(_authFile))
            {
                var legacy = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "BondClient", "auth.json");
                if (File.Exists(legacy)) File.Copy(legacy, _authFile, overwrite: false);
            }

            if (!File.Exists(_authFile)) return;
            var dto = JsonSerializer.Deserialize<AuthTokenDto>(File.ReadAllText(_authFile), JsonOptions);
            if (dto is null || string.IsNullOrEmpty(dto.AccessToken)) return;

            AccessToken = dto.AccessToken;
            _refreshToken = dto.RefreshToken;
            _http.DefaultRequestHeaders.Authorization =
                new AuthenticationHeaderValue("Bearer", dto.AccessToken);
        }
        catch
        {
            // 凭据损坏时按未登录处理
        }
    }

    private async Task<bool> TryRefreshAsync()
    {
        if (string.IsNullOrEmpty(_refreshToken)) return false;

        try
        {
            var body = new StringContent(
                JsonSerializer.Serialize(new { refreshToken = _refreshToken }, JsonOptions),
                Encoding.UTF8, "application/json");

            var resp = await _http.PostAsync("/api/auth/refresh", body).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return false;

            var result = await resp.Content
                .ReadFromJsonAsync<ApiResult<UserDto>>(JsonOptions).ConfigureAwait(false);

            if (result?.Data?.AccessToken is not { Length: > 0 } newToken) return false;

            SetTokens(newToken, result.Data.RefreshToken ?? _refreshToken);
            return true;
        }
        catch
        {
            return false;
        }
    }

    // ── 通用请求 ──

    private async Task<TResp?> SendAsync<TResp>(Func<HttpClient, Task<HttpResponseMessage>> send)
        where TResp : class
    {
        LastError = null;
        LastHttpStatus = -1;
        LastFailure = ApiFailureKind.None;

        HttpResponseMessage resp;
        try
        {
            resp = await send(_http).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            LastFailure = ApiFailureKind.Network;
            LastError = $"网络请求失败：{ex.Message}";
            Console.Error.WriteLine($"[Bond] {LastError}");
            return null;
        }

        LastHttpStatus = (int)resp.StatusCode;

        if (resp.StatusCode == HttpStatusCode.Unauthorized)
        {
            // task-151：401 刷新语义统一在 RetryPolicy.TokenRefresh（原请求 + 刷新成功后重放一次）；
            // 刷新失败即判定登录失效，由 AuthExpired 通知外层回登录页，不再二次重试。
            if (!await TryRefreshAsync().ConfigureAwait(false))
            {
                AuthExpired?.Invoke(this, EventArgs.Empty);
                LastFailure = ApiFailureKind.Http;
                LastError = $"登录状态已失效（HTTP {(int)resp.StatusCode}），请重新登录。";
                return null;
            }

            try
            {
                resp = await send(_http).ConfigureAwait(false);
                LastHttpStatus = (int)resp.StatusCode;
            }
            catch (Exception ex)
            {
                LastFailure = ApiFailureKind.Network;
                LastError = $"网络请求失败：{ex.Message}";
                return null;
            }
        }

        var jsonBody = false;
        try
        {
            var parsed = await resp.Content.ReadFromJsonAsync<TResp>(JsonOptions).ConfigureAwait(false);
            if (parsed is not null) return parsed;
            // 空响应体（如 204）视为无内容；若是错误状态则按下方的 HTTP/JSON 分类提示。
            jsonBody = true;
        }
        catch (Exception ex)
        {
            if (!resp.IsSuccessStatusCode)
            {
                LastFailure = ApiFailureKind.Http;
                LastError = $"请求失败（HTTP {LastHttpStatus}）。";
            }
            else
            {
                LastFailure = ApiFailureKind.Json;
                LastError = $"响应解析失败（HTTP {LastHttpStatus}）：{ex.Message}";
            }

            Console.Error.WriteLine($"[Bond] {LastError}");
            return null;
        }

        // 走到这里说明响应体为空（或 null JSON）。区分 HTTP 错误与真的空响应。
        if (!resp.IsSuccessStatusCode)
        {
            LastFailure = ApiFailureKind.Http;
            LastError = $"请求失败（HTTP {LastHttpStatus}）。";
            return null;
        }

        LastFailure = jsonBody ? ApiFailureKind.Json : ApiFailureKind.None;
        return null;
    }

    public Task<ApiResult<T>?> GetAsync<T>(string path)
        => SendAsync<ApiResult<T>>(http => http.GetAsync(path));

    // ── 公告（task-7：用户侧 /api/announcements，需登录） ──

    /// <summary>当前用户可见公告列表（按发布时间倒序，最多 50 条）。</summary>
    public Task<ApiResult<List<AnnouncementDto>>?> GetAnnouncementsAsync()
        => GetAsync<List<AnnouncementDto>>("/api/announcements");

    /// <summary>未读条数（data.count）。</summary>
    public Task<ApiResult<AnnouncementUnreadCountDto>?> GetAnnouncementUnreadCountAsync()
        => GetAsync<AnnouncementUnreadCountDto>("/api/announcements/unread-count");

    /// <summary>标记已读（幂等，POST 无 body）。</summary>
    public Task<ApiResult?> MarkAnnouncementReadAsync(string id)
        => SendAsync<ApiResult>(http => http.PostAsync($"/api/announcements/{id}/read", JsonBody(null)));

    // ── 分享链接（/api/shares，后端 2026-10-23 已上线） ──

    /// <summary>创建分享链接。expiresDays=0 表示永久（服务端要求 maxDownloads &gt; 0）。</summary>
    public Task<ApiResult<ShareLinkDto>?> CreateShareAsync(object fileId, bool requiresCode, string? code,
        int? expiresDays, int maxDownloads = 0)
        => SendAsync<ApiResult<ShareLinkDto>>(http => http.PostAsync("/api/shares", JsonBody(new
        {
            fileId,
            requiresCode,
            code = requiresCode ? code : null,
            expiresDays,
            maxDownloads
        })));

    /// <summary>我创建的分享链接，按创建时间倒序。</summary>
    public Task<ApiResult<List<ShareLinkDto>>?> GetSharesAsync()
        => SendAsync<ApiResult<List<ShareLinkDto>>>(http => http.GetAsync("/api/shares"));

    /// <summary>撤销分享；成功返回 null，失败返回后端原始 message。</summary>
    public async Task<string?> RevokeShareAsync(string shareId)
    {
        var result = await DeleteAsync($"/api/shares/{shareId}").ConfigureAwait(false);
        if (result is null) return "无法连接服务器，请检查网络后重试";
        if (result.Code is 200 or 204) return null;
        return string.IsNullOrWhiteSpace(result.Message) ? $"撤销失败（HTTP {result.Code}）" : result.Message;
    }

    /// <summary>
    /// 编辑分享（PATCH /api/shares/{id}，task-163）：字段缺省=不改、显式 null=清空、有值=设置。
    /// 三态映射由调用方决定；这里只把字典原样发出并回传后端响应。
    /// </summary>
    public Task<ApiResult<ShareLinkDto>?> PatchShareAsync(string shareId, IReadOnlyDictionary<string, object?> body)
        => PatchAsync<ShareLinkDto>($"/api/shares/{shareId}", body);

    /// <summary>
    /// 转存（服务端复制）：<paramref name="sourceBase"/> 为 <c>/api/files</c>（个人空间）或
    /// <c>/api/workspaces/{id}/files</c>（工作区）。接口尚未上线时返回 null 或非 2xx，
    /// 界面据此展示真实错误，不伪造成功。
    /// </summary>
    public Task<ApiResult?> CopyFileAsync(string sourceBase, string fileId, string? targetWorkspaceId,
        string targetParentPath, string? newName)
    {
        // targetWorkspaceId 服务端是 Long；个人空间目标时传 null。
        var wsId = long.TryParse(targetWorkspaceId, out var parsed) ? parsed : (long?)null;
        return SendAsync<ApiResult>(http => http.PostAsync($"{sourceBase}/{fileId}/copy", JsonBody(new
        {
            targetWorkspaceId = wsId,
            targetParentPath,
            newName
        })));
    }

    /// <summary>
    /// 移动（task-166）：与转存同一份 body 契约（<c>POST .../move</c>）。不传 targetWorkspaceId 时
    /// 目标仍是源所属空间（同空间移动只改路径）。
    /// </summary>
    public Task<ApiResult?> MoveFileAsync(string sourceBase, string fileId, string? targetWorkspaceId,
        string targetParentPath, string? newName)
    {
        var wsId = long.TryParse(targetWorkspaceId, out var parsed) ? parsed : (long?)null;
        return SendAsync<ApiResult>(http => http.PostAsync($"{sourceBase}/{fileId}/move", JsonBody(new
        {
            targetWorkspaceId = wsId,
            targetParentPath,
            newName
        })));
    }

    public Task<ApiResult<T>?> PostAsync<T>(string path, object? body = null)
        => SendAsync<ApiResult<T>>(http => http.PostAsync(path, JsonBody(body)));

    public Task<ApiResult<T>?> PutAsync<T>(string path, object? body = null)
        => SendAsync<ApiResult<T>>(http => http.PutAsync(path, JsonBody(body)));

    /// <summary>PATCH 请求（task-139 会话置顶/免打扰）。</summary>
    public Task<ApiResult<T>?> PatchAsync<T>(string path, object? body = null)
        => SendAsync<ApiResult<T>>(http => http.SendAsync(new HttpRequestMessage(HttpMethod.Patch, path)
        {
            Content = JsonBody(body)
        }));

    public Task<ApiResult<T>?> PostMultipartAsync<T>(string path, MultipartFormDataContent content)
        => SendAsync<ApiResult<T>>(http => http.PostAsync(path, content));

    public Task<ApiResult?> DeleteAsync(string path)
        => SendAsync<ApiResult>(http => http.DeleteAsync(path));

    /// <summary>探测路径的 OPTIONS 状态码（-1 表示网络失败）。用于 /dav/ 是否启用。</summary>
    public async Task<int> OptionsStatusAsync(string path)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Options, path);
            using var resp = await _http.SendAsync(request).ConfigureAwait(false);
            return (int)resp.StatusCode;
        }
        catch
        {
            return -1;
        }
    }

    public async Task<HttpResponseMessage?> GetStreamAsync(string path)
    {
        try
        {
            var resp = await _http.GetAsync(path, HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false);
            LastHttpStatus = (int)resp.StatusCode;
            if (resp.IsSuccessStatusCode)
            {
                LastFailure = ApiFailureKind.None;
                return resp;
            }

            LastFailure = ApiFailureKind.Http;
            LastError = $"请求失败（HTTP {LastHttpStatus}）。";
            resp.Dispose();
            return null;
        }
        catch (Exception ex)
        {
            LastHttpStatus = -1;
            LastFailure = ApiFailureKind.Network;
            LastError = $"网络请求失败：{ex.Message}";
            return null;
        }
    }

    /// <summary>下载二进制内容（头像等）。url 可为绝对地址或 /api 相对路径。</summary>
    public async Task<byte[]?> GetBytesAsync(string url)
    {
        try
        {
            using var resp = await _http.GetAsync(url).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return null;
            return await resp.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// 下载二进制内容，最多只读 <paramref name="maxBytes"/> 字节（预览文本用 Range 请求）。
    /// 返回的第二个值表示是否被截断。
    /// </summary>
    public async Task<(byte[]? Data, bool Truncated)> GetBytesLimitedAsync(string url, long maxBytes)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            request.Headers.Range = new RangeHeaderValue(0, maxBytes);

            using var resp = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead)
                .ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return (null, false);

            await using var stream = await resp.Content.ReadAsStreamAsync().ConfigureAwait(false);
            using var buffer = new MemoryStream();
            var chunk = new byte[81920];
            long total = 0;
            while (total <= maxBytes)
            {
                var read = await stream.ReadAsync(chunk.AsMemory(0, chunk.Length)).ConfigureAwait(false);
                if (read <= 0) break;
                var take = (int)Math.Min(read, maxBytes + 1 - total);
                if (take > 0) buffer.Write(chunk, 0, take);
                total += read;
            }

            var data = buffer.ToArray();
            var truncated = data.Length > maxBytes;
            if (truncated) Array.Resize(ref data, (int)maxBytes);
            return (data, truncated);
        }
        catch
        {
            return (null, false);
        }
    }

    private static StringContent JsonBody(object? body)
        => new(JsonSerializer.Serialize(body ?? new { }, JsonOptions), Encoding.UTF8, "application/json");

    public void Dispose() => _http.Dispose();
}
