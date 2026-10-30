using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Bond.Models;

namespace Bond.Services.Api;

/// <summary>文档 DTO（列表无 contentMd；id 为字符串）。</summary>
public sealed class DocumentDto
{
    [JsonPropertyName("id")] public string? Id { get; set; }

    [JsonPropertyName("workspaceId")] public string? WorkspaceId { get; set; }

    [JsonPropertyName("title")] public string? Title { get; set; }

    [JsonPropertyName("parentPath")] public string? ParentPath { get; set; }

    [JsonPropertyName("version")] public long Version { get; set; }

    [JsonPropertyName("sizeBytes")] public long? SizeBytes { get; set; }

    [JsonPropertyName("lastEditorName")] public string? LastEditorName { get; set; }

    [JsonPropertyName("createdAt")] public long? CreatedAt { get; set; }

    [JsonPropertyName("updatedAt")] public long? UpdatedAt { get; set; }

    [JsonPropertyName("contentMd")] public string? ContentMd { get; set; }
}

/// <summary>版本历史条目 DTO（后端不含正文）。</summary>
public sealed class DocumentVersionDto
{
    [JsonPropertyName("version")] public long Version { get; set; }

    [JsonPropertyName("editorName")] public string? EditorName { get; set; }

    [JsonPropertyName("createdAt")] public long? CreatedAt { get; set; }

    [JsonPropertyName("sizeBytes")] public long? SizeBytes { get; set; }
}

/// <summary>保存文档结果：区分成功 / 409 冲突 / 其它错误。</summary>
public sealed class DocumentSaveResult
{
    public DocumentDto? Document { get; init; }

    public int ServerVersion { get; init; }

    public string? LastEditorName { get; init; }

    public string? Error { get; init; }

    public bool IsConflict => ServerVersion > 0;
}

public sealed partial class BondApiClient
{
    public Task<ApiResult<List<DocumentDto>>?> GetDocumentsAsync(long? workspaceId, string parentPath)
        => GetAsync<List<DocumentDto>>(
            workspaceId is long w
                ? $"/api/documents?workspaceId={w}&path={Uri.EscapeDataString(parentPath)}"
                : $"/api/documents?path={Uri.EscapeDataString(parentPath)}");

    public Task<ApiResult<DocumentDto>?> CreateDocumentAsync(long? workspaceId, string parentPath, string title, string contentMd)
        => PostAsync<DocumentDto>("/api/documents", new
        {
            workspaceId,
            parentPath,
            title,
            contentMd,
        });

    public Task<ApiResult<DocumentDto>?> GetDocumentAsync(long id)
        => GetAsync<DocumentDto>($"/api/documents/{id}");

    /// <summary>PUT 保存；409 时后端 data 为 {serverVersion,lastEditorName,updatedAt}。</summary>
    public async Task<DocumentSaveResult> SaveDocumentAsync(long id, string title, string contentMd, long version, bool force)
    {
        using var request = new HttpRequestMessage(HttpMethod.Put, $"/api/documents/{id}")
        {
            Content = JsonBody(new { title, contentMd, version, force }),
        };

        using var resp = await _http.SendAsync(request).ConfigureAwait(false);
        var text = await resp.Content.ReadAsStringAsync().ConfigureAwait(false);

        if (resp.IsSuccessStatusCode)
        {
            try
            {
                var ok = JsonSerializer.Deserialize<ApiResult<DocumentDto>>(text, JsonOptions);
                if (ok?.Data is not null)
                {
                    return new DocumentSaveResult { Document = ok.Data };
                }
            }
            catch (JsonException)
            {
                // fall through
            }

            return new DocumentSaveResult { Error = "保存成功但响应无法解析" };
        }

        if ((int)resp.StatusCode == 409)
        {
            int serverVersion = 0;
            string? editor = null;
            try
            {
                using var doc = JsonDocument.Parse(text);
                var node = doc.RootElement;
                if (node.TryGetProperty("data", out var data) && data.ValueKind == JsonValueKind.Object)
                {
                    node = data;
                }

                if (node.TryGetProperty("serverVersion", out var sv) && sv.TryGetInt64(out var svv))
                {
                    serverVersion = (int)svv;
                }

                if (node.TryGetProperty("lastEditorName", out var en) && en.ValueKind == JsonValueKind.String)
                {
                    editor = en.GetString();
                }
            }
            catch (JsonException)
            {
                // ignore
            }

            return new DocumentSaveResult
            {
                ServerVersion = serverVersion == 0 ? 1 : serverVersion,
                LastEditorName = editor,
            };
        }

        var message = TryReadMessage(text) ?? $"HTTP {(int)resp.StatusCode}";
        return new DocumentSaveResult { Error = message };
    }

    public Task<ApiResult?> DeleteDocumentAsync(long id) => DeleteAsync($"/api/documents/{id}");

    public Task<ApiResult<List<DocumentVersionDto>>?> GetDocumentVersionsAsync(long id)
        => GetAsync<List<DocumentVersionDto>>($"/api/documents/{id}/versions");

    public Task<ApiResult<DocumentDto>?> RestoreDocumentVersionAsync(long id, long version)
        => PostAsync<DocumentDto>($"/api/documents/{id}/restore/{version}");

    /// <summary>导出 md/html，返回原始字节与建议文件名（若含 Content-Disposition）。</summary>
    public async Task<(byte[]? Data, string? FileName)> ExportDocumentAsync(long id, string format)
    {
        using var resp = await _http.GetAsync($"/api/documents/{id}/export?format={format}").ConfigureAwait(false);
        if (!resp.IsSuccessStatusCode)
        {
            return (null, null);
        }

        var data = await resp.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
        string? fileName = null;
        if (resp.Content.Headers.ContentDisposition is { } cd)
        {
            fileName = cd.FileNameStar ?? cd.FileName?.Trim('"');
        }

        return (data, fileName);
    }

    private static string? TryReadMessage(string text)
    {
        try
        {
            using var doc = JsonDocument.Parse(text);
            if (doc.RootElement.TryGetProperty("message", out var m) && m.ValueKind == JsonValueKind.String)
            {
                return m.GetString();
            }
        }
        catch (JsonException)
        {
            // ignore
        }

        return null;
    }
}
