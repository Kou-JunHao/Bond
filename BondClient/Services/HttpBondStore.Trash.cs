using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Bond.Models;
using Bond.Services.Api;

namespace Bond.Services;

/// <summary>回收站（Http 实现）。三态语义：Data 为 null 才算“失败”，空列表是“真空”。</summary>
public sealed partial class HttpBondStore : ITrashService
{
    /// <summary>
    /// 回收站跟随文件页的「当前空间」：后端 GET/DELETE /api/trash 都按 workspaceId 过滤，
    /// 不传即个人空间。此前客户端恒不传 → 在团队工作区里删除的文件，回收站里根本看不到（更无法恢复）。
    /// 个人空间的 <see cref="FileSpace.WorkspaceId"/> 为 null → 仍取个人回收站。
    /// </summary>
    private long? CurrentTrashWorkspaceId()
    {
        var raw = _currentSpace?.WorkspaceId;
        return long.TryParse(raw, NumberStyles.Integer, CultureInfo.InvariantCulture, out var id) && id > 0
            ? id
            : null;
    }

    public async Task<(IReadOnlyList<TrashItem> Items, string? Error)> GetTrashAsync(CancellationToken ct = default)
    {
        var result = await _api.GetTrashAsync(CurrentTrashWorkspaceId()).ConfigureAwait(false);
        if (result?.Data is null)
        {
            var error = result?.Message is { Length: > 0 } m
                ? m
                : _api.LastError ?? "加载回收站失败，请检查网络后重试";
            return (Array.Empty<TrashItem>(), error);
        }

        var items = result.Data.Select(d => new TrashItem(
            d.Id.ToString(CultureInfo.InvariantCulture),
            string.IsNullOrWhiteSpace(d.FileName) ? "(未命名)" : d.FileName!,
            d.SizeBytes ?? d.FileSize ?? 0,
            d.IsDirectory == true,
            string.IsNullOrWhiteSpace(d.OriginalPath) ? "/" : d.OriginalPath!,
            ToTime(d.DeletedAtMillis),
            d.ExpiresAtMillis is { } expires ? DateTimeOffset.FromUnixTimeMilliseconds(expires) : null,
            d.ItemCount ?? 0)).ToArray();

        return (items, null);
    }

    public Task<TrashOpResult> RestoreAsync(IReadOnlyList<string> ids, CancellationToken ct = default)
        => BatchAsync(ids, purge: false);

    public Task<TrashOpResult> PurgeAsync(IReadOnlyList<string> ids, CancellationToken ct = default)
        => BatchAsync(ids, purge: true);

    public async Task<(int Purged, string? Error)> EmptyAsync(CancellationToken ct = default)
        => await _api.EmptyTrashAsync(CurrentTrashWorkspaceId()).ConfigureAwait(false);

    private async Task<TrashOpResult> BatchAsync(IReadOnlyList<string> ids, bool purge)
    {
        var numeric = ids
            .Select(id => long.TryParse(id, NumberStyles.Integer, CultureInfo.InvariantCulture, out var value) ? value : (long?)null)
            .Where(v => v is not null)
            .Select(v => v!.Value)
            .ToList();

        if (numeric.Count == 0)
            return new TrashOpResult { Failed = [(ids.FirstOrDefault() ?? "", "无效的文件 id")] };

        var result = purge
            ? await _api.PurgeTrashAsync(numeric).ConfigureAwait(false)
            : await _api.RestoreTrashAsync(numeric).ConfigureAwait(false);

        // 207 部分失败：SendAsync 仍会解析 body（有 data），这里逐条带出原因。
        if (result?.Data is null)
        {
            var error = result?.Message is { Length: > 0 } m
                ? m
                : _api.LastError ?? (purge ? "彻底删除失败" : "恢复失败");
            return new TrashOpResult { Failed = [(ids.FirstOrDefault() ?? "", error)] };
        }

        return new TrashOpResult
        {
            Succeeded = (result.Data.Succeeded ?? []).Select(v => v.ToString(CultureInfo.InvariantCulture)).ToArray(),
            Failed = (result.Data.Failed ?? [])
                .Select(f => (f.Id.ToString(CultureInfo.InvariantCulture),
                    string.IsNullOrWhiteSpace(f.Reason) ? "操作失败" : f.Reason!))
                .ToArray()
        };
    }

    private static DateTimeOffset ToTime(long? millis)
        => millis is { } value && value > 0
            ? DateTimeOffset.FromUnixTimeMilliseconds(value)
            : DateTimeOffset.UnixEpoch;
}
