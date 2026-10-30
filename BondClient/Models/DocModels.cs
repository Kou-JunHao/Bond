using System;
using System.Collections.Generic;

namespace Bond.Models;

/// <summary>文档摘要（GET /api/documents 列表项；不含正文）。</summary>
public sealed class DocumentSummary
{
    public long Id { get; init; }

    public string Title { get; init; } = "";

    public string? ContentPreview { get; init; }

    public long WordCount { get; init; }

    public long SizeBytes { get; init; }

    public int Version { get; init; }

    public DateTimeOffset? UpdatedAt { get; init; }

    public string? LastEditorName { get; init; }

    public string SizeText => FormatSize(SizeBytes);

    public string PreviewText => string.IsNullOrWhiteSpace(ContentPreview)
        ? (SizeBytes > 0 ? "Markdown 文档" : "（空文档）")
        : ContentPreview!.Trim();

    public string MetaText
    {
        get
        {
            var parts = new List<string> { $"v{Version}", SizeText };
            if (UpdatedAt is { } t) parts.Add(t.ToLocalTime().ToString("MM-dd HH:mm"));
            if (!string.IsNullOrWhiteSpace(LastEditorName)) parts.Add(LastEditorName!);
            return string.Join(" · ", parts);
        }
    }

    internal static string FormatSize(long bytes)
    {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return $"{bytes} B";
        if (bytes < 1024 * 1024) return $"{bytes / 1024.0:0.#} KB";
        if (bytes < 1024L * 1024 * 1024) return $"{bytes / (1024.0 * 1024):0.#} MB";
        return $"{bytes / (1024.0 * 1024 * 1024):0.##} GB";
    }
}

/// <summary>文档详情（GET /api/documents/{id}）。</summary>
public sealed class DocumentDetail
{
    public long Id { get; init; }

    public string Title { get; init; } = "";

    public string ContentMd { get; init; } = "";

    public string? ContentMd5 { get; init; }

    public int Version { get; init; }

    public long WordCount { get; init; }

    public long SizeBytes { get; init; }

    public DateTimeOffset? UpdatedAt { get; init; }

    public string? LastEditorName { get; init; }
}

/// <summary>版本历史条目（GET /api/documents/{id}/versions）。</summary>
public sealed class DocumentVersionItem
{
    public int Version { get; init; }

    public DateTimeOffset? CreatedAt { get; init; }

    public string? EditorName { get; init; }

    public long WordCount { get; init; }

    public long SizeBytes { get; init; }

    public string TimeText => CreatedAt?.ToLocalTime().ToString("MM-dd HH:mm") ?? "—";

    public string MetaText => $"{Version} · {DocumentSummary.FormatSize(SizeBytes)} · {TimeText}"
        + (string.IsNullOrWhiteSpace(EditorName) ? "" : $" · {EditorName}");
}

/// <summary>编辑态。</summary>
public enum DocEditState
{
    Saved,
    Dirty,
    Saving,
    Conflict,
    Error,
}

/// <summary>版本冲突信息（409）。</summary>
public sealed class DocConflict
{
    public int ServerVersion { get; init; }

    public string? LastEditorName { get; init; }
}

public sealed class DocPutResult
{
    public DocumentDetail? Document { get; init; }

    public DocConflict? Conflict { get; init; }

    public string? Error { get; init; }

    public bool IsConflict => Conflict is not null;
}

/// <summary>文档所属空间（列表下拉用）。</summary>
public sealed class DocSpaceOption
{
    public long? WorkspaceId { get; init; }

    public string Name { get; init; } = "个人空间";

    public override string ToString() => Name;
}
