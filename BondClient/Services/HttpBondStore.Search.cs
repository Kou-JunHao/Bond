using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Bond.Models;

namespace Bond.Services;

/// <summary>
/// task-123：文件搜索（服务端 <c>GET /api/files/search</c>）。
/// <para>
/// 只做「请求 + 契约映射」，不缓存、不改 <c>Nodes</c>：
/// 搜索结果是独立列表，由文件页 ViewModel 持有并决定何时展示/追加。
/// </para>
/// </summary>
public sealed partial class HttpBondStore
{
    /// <summary>
    /// 搜索文件与文件夹。关键词 trim 后为空或超过 100 字符时不发请求（后端会 400），
    /// 直接返回可展示文案；失败时优先用后端 <c>message</c>，其次 <see cref="Api.BondApiClient.LastError"/>。
    /// </summary>
    public async Task<FileSearchOutcome> SearchFilesAsync(string query, string? workspaceId, int limit, string? cursor)
    {
        var q = (query ?? string.Empty).Trim();
        if (q.Length == 0) return new FileSearchOutcome(null, "搜索关键词不能为空");
        if (q.Length > 100) return new FileSearchOutcome(null, "搜索关键词过长（最多 100 字符）");

        var result = await _api
            .SearchFilesAsync(q, string.IsNullOrWhiteSpace(workspaceId) ? null : workspaceId,
                Math.Clamp(limit, 1, 100), cursor)
            .ConfigureAwait(false);

        if (result is null)
            return new FileSearchOutcome(null, _api.LastError ?? "搜索失败，请检查网络后重试", Retryable: true);

        if (result.Code != 200 || result.Data is null)
        {
            var message = string.IsNullOrWhiteSpace(result.Message)
                ? $"搜索失败（HTTP {result.Code}）"
                : result.Message;
            return new FileSearchOutcome(null, message, Retryable: true);
        }

        var page = result.Data;
        var items = new List<NodeItem>(page.Items?.Count ?? 0);

        // task-123：同一文件可能同时存在于个人空间与工作区，列表 key 必须用 workspaceId+id 去重。
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var f in page.Items ?? [])
        {
            if (string.IsNullOrEmpty(f.Id) || string.IsNullOrEmpty(f.Name)) continue;

            var spaceId = string.IsNullOrEmpty(f.WorkspaceId) ? "personal" : f.WorkspaceId;
            if (!seen.Add(spaceId + ":" + f.Id)) continue;

            items.Add(new NodeItem
            {
                Id = f.Id,
                Name = f.Name,
                Kind = f.IsDirectory ? NodeKind.Folder : NodeKind.File,
                Size = f.Size,
                ContentType = f.ContentType,
                ParentPath = ParentOf(f.Path),
                SpaceId = spaceId,
                PathHint = f.Path,
                FullPath = f.Path,
                SourceName = ResolveSourceName(spaceId),
                ModifiedAt = f.UpdatedAtMillis > 0
                    ? DateTimeOffset.FromUnixTimeMilliseconds(f.UpdatedAtMillis).LocalDateTime
                    : DateTime.Now
            });
        }

        return new FileSearchOutcome(new FileSearchPage(items, page.NextCursor, page.HasMore, page.Total), null);
    }

    /// <summary>
    /// task-127：把空间 id 翻译成副行里的来源名——个人空间固定「个人空间」，
    /// 工作区从已加载的空间列表取名字；取不到返回 null（上层只显示路径，不编造）。
    /// </summary>
    private string? ResolveSourceName(string spaceId)
    {
        if (string.Equals(spaceId, "personal", StringComparison.OrdinalIgnoreCase)) return "个人空间";

        // task-127：服务端个人空间的 workspaceId 与本地空间的 "personal" 不同，
        // 用它缓存下来的真实 id 才能把个人空间文件标成「个人空间」而不是留空。
        if (_personalWorkspaceId > 0
            && string.Equals(spaceId, _personalWorkspaceId.ToString(System.Globalization.CultureInfo.InvariantCulture), StringComparison.Ordinal))
        {
            return "个人空间";
        }

        foreach (var space in Spaces)
        {
            if (string.Equals(space.WorkspaceId, spaceId, StringComparison.Ordinal)
                || string.Equals(space.Id, spaceId, StringComparison.Ordinal))
            {
                return space.Name;
            }
        }

        return null;
    }

    private static string ParentOf(string? fullPath)
    {
        if (string.IsNullOrWhiteSpace(fullPath) || fullPath == "/") return "/";
        var trimmed = fullPath.TrimEnd('/');
        var idx = trimmed.LastIndexOf('/');
        return idx <= 0 ? "/" : trimmed[..idx];
    }
}
