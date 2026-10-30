using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Bond.Models;
using Bond.Services.Api;

namespace Bond.Services;

public sealed partial class HttpBondStore : IDocService
{
    public async Task<IReadOnlyList<DocumentSummary>> GetDocumentsAsync(long? workspaceId, string parentPath, CancellationToken ct = default)
    {
        var result = await _api.GetDocumentsAsync(workspaceId, parentPath).ConfigureAwait(false);
        if (result?.Data is null)
        {
            throw new InvalidOperationException(result?.Message ?? "无法加载文档列表");
        }

        return result.Data.Select(MapSummary).ToList();
    }

    public async Task<DocumentDetail> CreateAsync(long? workspaceId, string parentPath, string title, string contentMd, CancellationToken ct = default)
    {
        var result = await _api.CreateDocumentAsync(workspaceId, parentPath, title, contentMd).ConfigureAwait(false);
        if (result?.Data is null)
        {
            throw new InvalidOperationException(result?.Message ?? "新建文档失败");
        }

        return MapDetail(result.Data);
    }

    public async Task<DocumentDetail?> GetAsync(long id, CancellationToken ct = default)
    {
        var result = await _api.GetDocumentAsync(id).ConfigureAwait(false);
        return result?.Data is null ? null : MapDetail(result.Data);
    }

    public async Task<DocPutResult> SaveAsync(long id, string title, string contentMd, long version, bool force, CancellationToken ct = default)
    {
        var r = await _api.SaveDocumentAsync(id, title, contentMd, version, force).ConfigureAwait(false);
        if (r.Document is not null)
        {
            return new DocPutResult { Document = MapDetail(r.Document) };
        }

        if (r.IsConflict)
        {
            return new DocPutResult { Conflict = new DocConflict { ServerVersion = r.ServerVersion, LastEditorName = r.LastEditorName } };
        }

        return new DocPutResult { Error = r.Error ?? "保存失败" };
    }

    public async Task DeleteAsync(long id, CancellationToken ct = default)
        => await _api.DeleteDocumentAsync(id).ConfigureAwait(false);

    public async Task<IReadOnlyList<DocumentVersionItem>> GetVersionsAsync(long id, CancellationToken ct = default)
    {
        var result = await _api.GetDocumentVersionsAsync(id).ConfigureAwait(false);
        if (result?.Data is null)
        {
            throw new InvalidOperationException(result?.Message ?? "无法加载版本历史");
        }

        return result.Data.Select(v => new DocumentVersionItem
        {
            Version = (int)v.Version,
            CreatedAt = v.CreatedAt is { } ms ? DateTimeOffset.FromUnixTimeMilliseconds(ms) : null,
            EditorName = v.EditorName,
            WordCount = v.SizeBytes ?? 0,
            SizeBytes = v.SizeBytes ?? 0,
        }).ToList();
    }

    public async Task<DocumentDetail?> RestoreVersionAsync(long id, long version, CancellationToken ct = default)
    {
        var result = await _api.RestoreDocumentVersionAsync(id, version).ConfigureAwait(false);
        return result?.Data is null ? null : MapDetail(result.Data);
    }

    public async Task<(byte[]? Data, string? FileName)> ExportAsync(long id, string format, CancellationToken ct = default)
        => await _api.ExportDocumentAsync(id, format).ConfigureAwait(false);

    private static DocumentSummary MapSummary(DocumentDto dto) => new()
    {
        Id = ParseId(dto.Id),
        Title = string.IsNullOrWhiteSpace(dto.Title) ? "未命名文档" : dto.Title!,
        ContentPreview = dto.ContentMd,
        WordCount = CountWords(dto.ContentMd),
        SizeBytes = dto.SizeBytes ?? 0,
        Version = (int)dto.Version,
        UpdatedAt = dto.UpdatedAt is { } ms ? DateTimeOffset.FromUnixTimeMilliseconds(ms) : null,
        LastEditorName = dto.LastEditorName,
    };

    private static DocumentDetail MapDetail(DocumentDto dto) => new()
    {
        Id = ParseId(dto.Id),
        Title = string.IsNullOrWhiteSpace(dto.Title) ? "未命名文档" : dto.Title!,
        ContentMd = dto.ContentMd ?? "",
        Version = (int)dto.Version,
        WordCount = CountWords(dto.ContentMd),
        SizeBytes = dto.SizeBytes ?? 0,
        UpdatedAt = dto.UpdatedAt is { } ms ? DateTimeOffset.FromUnixTimeMilliseconds(ms) : null,
        LastEditorName = dto.LastEditorName,
    };

    private static long ParseId(string? raw) => long.TryParse(raw, out var v) ? v : 0;

    private static long CountWords(string? text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return 0;
        }

        long n = 0;
        foreach (var ch in text)
        {
            if (!char.IsWhiteSpace(ch))
            {
                n++;
            }
        }

        return n;
    }
}
