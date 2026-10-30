using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Text.Json.Serialization;

namespace Bond.Services.Api;

/// <summary>GET /api/trash 的列表项（时间字段是 epoch 毫秒）。</summary>
public sealed class TrashItemDto
{
    [JsonPropertyName("id")] public long Id { get; set; }

    [JsonPropertyName("fileName")] public string? FileName { get; set; }

    [JsonPropertyName("fileSize")] public long? FileSize { get; set; }

    /// <summary>后端新版本字段名；与 fileSize 二选一。</summary>
    [JsonPropertyName("sizeBytes")] public long? SizeBytes { get; set; }

    [JsonPropertyName("isDirectory")] public bool? IsDirectory { get; set; }

    [JsonPropertyName("originalPath")] public string? OriginalPath { get; set; }

    [JsonPropertyName("deletedAtMillis")] public long? DeletedAtMillis { get; set; }

    [JsonPropertyName("expiresAtMillis")] public long? ExpiresAtMillis { get; set; }

    [JsonPropertyName("itemCount")] public long? ItemCount { get; set; }
}

/// <summary>restore / purge 的逐条结果（207 时 failed 非空）。</summary>
public sealed class TrashBatchDto
{
    [JsonPropertyName("succeeded")] public List<long>? Succeeded { get; set; }

    [JsonPropertyName("failed")] public List<TrashFailedDto>? Failed { get; set; }
}

public sealed class TrashFailedDto
{
    [JsonPropertyName("id")] public long Id { get; set; }

    [JsonPropertyName("reason")] public string? Reason { get; set; }
}

public sealed partial class BondApiClient
{
    /// <summary>GET /api/trash（不传 workspaceId 取个人空间）。</summary>
    public Task<ApiResult<List<TrashItemDto>>?> GetTrashAsync(long? workspaceId = null)
        => workspaceId is { } id
            ? GetAsync<List<TrashItemDto>>($"/api/trash?workspaceId={id}")
            : GetAsync<List<TrashItemDto>>("/api/trash");

    /// <summary>POST /api/trash/restore（全成功 200；部分失败 207，body 仍有 data）。</summary>
    public Task<ApiResult<TrashBatchDto>?> RestoreTrashAsync(IReadOnlyList<long> ids)
        => PostAsync<TrashBatchDto>("/api/trash/restore", new { ids });

    /// <summary>POST /api/trash/purge。</summary>
    public Task<ApiResult<TrashBatchDto>?> PurgeTrashAsync(IReadOnlyList<long> ids)
        => PostAsync<TrashBatchDto>("/api/trash/purge", new { ids });

    /// <summary>
    /// DELETE /api/trash（204 No Content，清空数量在 <c>X-Trash-Purged</c> 响应头）。
    /// 204 没有 body，不能走 SendAsync（其 JSON 解析会失败）。
    /// </summary>
    public async Task<(int Purged, string? Error)> EmptyTrashAsync(long? workspaceId = null)
    {
        try
        {
            var path = workspaceId is { } id ? $"/api/trash?workspaceId={id}" : "/api/trash";
            using var resp = await _http.DeleteAsync(path).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode)
                return (0, $"清空失败（HTTP {(int)resp.StatusCode}）");

            var purged = resp.Headers.TryGetValues("X-Trash-Purged", out var values)
                && int.TryParse(values.FirstOrDefault(), out var n)
                ? n
                : 0;
            return (purged, null);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[Bond] 清空回收站失败: {ex.Message}");
            return (0, "网络请求失败，请稍后重试");
        }
    }
}
