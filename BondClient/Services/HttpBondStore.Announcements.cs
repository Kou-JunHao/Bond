using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Bond.Models;
using Bond.Services.Api;

namespace Bond.Services;

/// <summary>
/// task-7：系统公告（Http 实现）。
///
/// <para>后端契约（BondServer AnnouncementController）：</para>
/// <list type="bullet">
///   <item><c>GET /api/announcements</c> → data 为 List&lt;AnnouncementDTO&gt;（按发布时间倒序，最多 50 条；
///         只读，不自动标已读）。</item>
///   <item><c>GET /api/announcements/unread-count</c> → data 为 <c>{"count": n}</c>。</item>
///   <item><c>POST /api/announcements/{id}/read</c> → 幂等标已读；公告不存在/未发布/已过期返回 404。</item>
/// </list>
/// <para>三态口径与全应用一致：Data 为 null 才算“失败”，空列表是“真的没有公告”。</para>
/// </summary>
public sealed partial class HttpBondStore : IAnnouncementService
{
    /// <summary>服务端用户侧列表上限（AnnouncementService.USER_LIST_LIMIT = 50），用于判断未读数能否由列表精确重算。</summary>
    public const int AnnouncementListLimit = 50;

    public async Task<(IReadOnlyList<AnnouncementItem> Items, string? Error)> GetAsync(
        CancellationToken ct = default)
    {
        if (!_api.IsLoggedIn) return ([], "登录状态已失效，请重新登录");

        var result = await _api.GetAnnouncementsAsync().ConfigureAwait(false);
        if (result is null)
            return ([], LoadRetry.Humanize(_api.LastError, "加载公告失败，请稍后重试"));

        if (result.Code != 200)
        {
            return ([], string.IsNullOrWhiteSpace(result.Message)
                ? $"加载公告失败（HTTP {result.Code}）"
                : result.Message);
        }

        // 200 但没有 data：按「空列表」处理会让界面误报“暂无公告”，这里是契约异常，如实报错。
        if (result.Data is null) return ([], "公告数据格式异常，请稍后重试");

        return (result.Data.Select(Map).ToArray(), null);
    }

    public async Task<(int Unread, string? Error)> GetUnreadCountAsync(CancellationToken ct = default)
    {
        if (!_api.IsLoggedIn) return (0, "登录状态已失效，请重新登录");

        var result = await _api.GetAnnouncementUnreadCountAsync().ConfigureAwait(false);
        if (result is null)
            return (0, LoadRetry.Humanize(_api.LastError, "获取公告未读数失败"));

        if (result.Code != 200 || result.Data is null)
        {
            // 后端旧版本没有该端点时也走这里：界面保留上一次未读数，不显示假 0。
            return (0, string.IsNullOrWhiteSpace(result.Message)
                ? $"获取公告未读数失败（HTTP {result.Code}）"
                : result.Message);
        }

        return ((int)Math.Max(0, result.Data.Count), null);
    }

    public async Task<string?> MarkReadAsync(string id, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(id)) return "公告标识为空";

        var result = await _api.MarkAnnouncementReadAsync(id).ConfigureAwait(false);
        if (result is null)
            return LoadRetry.Humanize(_api.LastError, "标记已读失败，请稍后重试");

        // 后端 Result.ok() 的 code=200；404（公告不存在/已过期）沿用服务端原文。
        if (result.Code is 200 or 204) return null;

        return string.IsNullOrWhiteSpace(result.Message)
            ? $"标记已读失败（HTTP {result.Code}）"
            : result.Message;
    }

    private static AnnouncementItem Map(AnnouncementDto dto) => new()
    {
        Id = string.IsNullOrWhiteSpace(dto.Id) ? Guid.NewGuid().ToString("N") : dto.Id,
        Title = string.IsNullOrWhiteSpace(dto.Title) ? "(未命名公告)" : dto.Title.Trim(),
        Content = dto.Content ?? "",
        Level = ParseLevel(dto.Level),
        PublishedAtMillis = dto.PublishedAtMillis,
        ExpiresAtMillis = dto.ExpiresAtMillis,
        IsRead = dto.Read
    };

    private static AnnouncementLevel ParseLevel(string? level)
    {
        if (string.Equals(level, "critical", StringComparison.OrdinalIgnoreCase))
            return AnnouncementLevel.Critical;

        if (string.Equals(level, "warn", StringComparison.OrdinalIgnoreCase))
            return AnnouncementLevel.Warn;

        return AnnouncementLevel.Info;
    }
}
