using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Bond.Models;

namespace Bond.Services;

/// <summary>WebDAV 挂载相关实现（专用密码 + /dav/ 启用探测）。</summary>
public sealed partial class HttpBondStore : IWebDavService
{
    public async Task<(IReadOnlyList<WebDavPasswordItem>? Items, string? Error)> GetWebDavPasswordsAsync(
        CancellationToken ct = default)
    {
        var res = await _api.GetAsync<List<DavPasswordDto>>("/api/webdav/passwords").ConfigureAwait(false);
        if (res?.IsOk != true)
            return (null, res?.Message ?? _api.LastError ?? "获取专用密码失败");

        var items = res.Data is null
            ? new List<WebDavPasswordItem>()
            : res.Data.Select(ToItem).ToList();
        return (items, null);
    }

    public async Task<(string? Password, WebDavPasswordItem? Item, string? Error)> CreateWebDavPasswordAsync(
        string name, CancellationToken ct = default)
    {
        var res = await _api.PostAsync<DavCreatedDto>("/api/webdav/passwords", new { name }).ConfigureAwait(false);
        if (res?.IsOk != true || res.Data is null)
            return (null, null, res?.Message ?? _api.LastError ?? "生成专用密码失败");

        var item = new WebDavPasswordItem
        {
            Id = res.Data.Id?.ToString() ?? string.Empty,
            Name = string.IsNullOrWhiteSpace(res.Data.Name) ? name : res.Data.Name!,
            CreatedAt = DateTimeOffset.Now,
            LastUsedAt = null
        };
        return (res.Data.Password, item, null);
    }

    public async Task<string?> RevokeWebDavPasswordAsync(string id, CancellationToken ct = default)
    {
        var res = await _api.DeleteAsync($"/api/webdav/passwords/{id}").ConfigureAwait(false);
        return res?.Code == 200 ? null : res?.Message ?? _api.LastError ?? "吊销专用密码失败";
    }

    public async Task<bool?> ProbeWebDavAsync(CancellationToken ct = default)
    {
        // OPTIONS /dav/ → 200 表示已启用；webdav.enabled=false 时返回 404。
        var status = await _api.OptionsStatusAsync("/dav/").ConfigureAwait(false);
        return status switch
        {
            200 or 401 => true, // 401 也说明服务在（只是未认证）
            404 => false,
            _ => null
        };
    }

    private static WebDavPasswordItem ToItem(DavPasswordDto d) => new()
    {
        Id = d.Id?.ToString() ?? string.Empty,
        Name = d.Name ?? "未命名",
        CreatedAt = FromMillis(d.CreatedAt),
        LastUsedAt = FromMillis(d.LastUsedAt),
        IsRevoked = d.Revoked
    };

    private sealed class DavPasswordDto
    {
        [JsonPropertyName("id")] public long? Id { get; set; }
        [JsonPropertyName("name")] public string? Name { get; set; }
        [JsonPropertyName("createdAt")] public long? CreatedAt { get; set; }
        [JsonPropertyName("lastUsedAt")] public long? LastUsedAt { get; set; }
        [JsonPropertyName("revoked")] public bool Revoked { get; set; }
    }

    private sealed class DavCreatedDto
    {
        [JsonPropertyName("id")] public long? Id { get; set; }
        [JsonPropertyName("name")] public string? Name { get; set; }
        [JsonPropertyName("password")] public string? Password { get; set; }
    }
}
