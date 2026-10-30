using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Bond.Models;

namespace Bond.Services;

/// <summary>在线文档服务（task-56；后端 Documents API）。</summary>
public interface IDocService
{
    bool IsLoading { get; }

    string? LastError { get; }

    event EventHandler? Changed;

    Task<IReadOnlyList<DocumentSummary>> GetDocumentsAsync(long? workspaceId, string parentPath, CancellationToken ct = default);

    Task<DocumentDetail> CreateAsync(long? workspaceId, string parentPath, string title, string contentMd, CancellationToken ct = default);

    Task<DocumentDetail?> GetAsync(long id, CancellationToken ct = default);

    Task<DocPutResult> SaveAsync(long id, string title, string contentMd, long version, bool force, CancellationToken ct = default);

    Task DeleteAsync(long id, CancellationToken ct = default);

    Task<IReadOnlyList<DocumentVersionItem>> GetVersionsAsync(long id, CancellationToken ct = default);

    Task<DocumentDetail?> RestoreVersionAsync(long id, long version, CancellationToken ct = default);

    Task<(byte[]? Data, string? FileName)> ExportAsync(long id, string format, CancellationToken ct = default);
}
