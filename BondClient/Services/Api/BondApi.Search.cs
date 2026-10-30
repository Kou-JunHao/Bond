using System;
using System.Collections.Generic;
using System.Threading.Tasks;

namespace Bond.Services.Api;

/// <summary>
/// task-123：文件搜索（<c>GET /api/files/search</c>，V25 后端已上线）。
/// <para>
/// 契约：<c>Result&lt;{items,nextCursor,hasMore,total}&gt;</c>；<c>q</c> trim 后为空或超过 100 字符服务端返回 400；
/// <c>limit</c> 默认 50、上限 100；<c>cursor</c> 不透明；不传 <c>workspaceId</c> 时搜「个人空间 + 我是成员的工作区」。
/// </para>
/// </summary>
public sealed partial class BondApiClient
{
    /// <summary>
    /// 搜索文件/文件夹。失败时返回 null，具体原因看 <see cref="LastError"/>（网络异常）或
    /// 返回值的 <c>Code/Message</c>（HTTP 400/403 等业务错误）。
    /// </summary>
    public Task<ApiResult<FileSearchPageDto>?> SearchFilesAsync(
        string query, string? workspaceId, int limit, string? cursor)
    {
        var url = $"/api/files/search?q={Uri.EscapeDataString(query)}&limit={limit}";
        if (!string.IsNullOrEmpty(workspaceId))
            url += $"&workspaceId={Uri.EscapeDataString(workspaceId)}";
        if (!string.IsNullOrEmpty(cursor))
            url += $"&cursor={Uri.EscapeDataString(cursor)}";
        return GetAsync<FileSearchPageDto>(url);
    }
}

/// <summary>task-123：搜索分页信封。</summary>
public sealed class FileSearchPageDto
{
    public List<FileSearchItemDto>? Items { get; set; }
    public string? NextCursor { get; set; }
    public bool HasMore { get; set; }
    public long Total { get; set; }
}

/// <summary>task-123：搜索结果条目（字段与后端固定契约一致）。</summary>
public sealed class FileSearchItemDto
{
    public string? Id { get; set; }
    public string? Name { get; set; }

    /// <summary>完整路径（含文件名），例如 <c>/zz-probe/sub/zz-probe.txt</c>。</summary>
    public string? Path { get; set; }

    public bool IsDirectory { get; set; }
    public long Size { get; set; }
    public string? ContentType { get; set; }

    /// <summary>更新时间（epoch 毫秒）。</summary>
    public long UpdatedAtMillis { get; set; }

    /// <summary>所属工作区 id；个人空间可能为空字符串。</summary>
    public string? WorkspaceId { get; set; }
}
