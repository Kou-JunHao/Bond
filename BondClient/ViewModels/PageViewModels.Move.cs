using System;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Bond.Core;
using Bond.Models;
using Bond.Services;

namespace Bond.ViewModels;

/// <summary>
/// task-166：文件页「移动到…」目标目录选择器 + 悬浮操作条上的分享 / 复制链接命令。
/// <para>移动只在当前空间内换目录（后端 <c>POST /api/files/{id}/move</c> /
/// <c>POST /api/workspaces/{ws}/files/{id}/move</c>；不传 targetWorkspaceId 即同空间移动），
/// 不复制对象、不消耗流量；失败一律展示后端原文（409 同名 / 404 目标不存在 / 400 非法路径）。</para>
/// </summary>
public partial class FilesPageViewModel
{
    // ── 移动：目标目录选择器 ────────────────────────────────

    public ObservableCollection<NodeItem> MoveFolders { get; } = [];

    [ObservableProperty] private bool _isMoveOpen;
    [ObservableProperty] private NodeItem? _moveSource;
    [ObservableProperty] private string _movePath = RootPath;
    [ObservableProperty] private string? _moveError;
    [ObservableProperty] private string? _moveHint;
    [ObservableProperty] private bool _isMoving;

    public string MovePathText =>
        string.IsNullOrEmpty(MovePath) || MovePath == RootPath ? "/（根目录）" : MovePath;

    public bool CanMoveGoUp => !string.IsNullOrEmpty(MovePath) && MovePath != RootPath;

    partial void OnMovePathChanged(string value)
    {
        OnPropertyChanged(nameof(MovePathText));
        OnPropertyChanged(nameof(CanMoveGoUp));
    }

    /// <summary>右键菜单用：移动支持单个文件或文件夹；多选时置灰（服务端也是单条目接口）。</summary>
    public bool CanMoveNode(NodeItem? node) => node is not null && SelectionCount <= 1;

    // ── 操作条按钮可用性 ────────────────────────────────────

    /// <summary>
    /// 分享：单选任意条目（task-183 后端已支持文件夹分享：匿名下载为流式 ZIP）。
    /// 多选仍不支持（后端 POST /api/shares 是单条目接口）。
    /// </summary>
    public bool CanShareSelection => _selection.Count == 1;

    /// <summary>移动：仅单选（文件或文件夹均可，目标由服务端校验）。</summary>
    public bool CanMoveSelection => _selection.Count == 1;

    /// <summary>复制链接：单选文件或文件夹均可入口；是否已有链接在点击时判定（不伪造按钮状态）。</summary>
    public bool CanCopyLinkSelection => _selection.Count == 1;

    public string ShareSelectionHint
    {
        get
        {
            if (CanShareSelection)
            {
                return _selection.First().IsFolder
                    ? "生成文件夹分享链接（匿名下载为 ZIP，有效期/提取码可设置）"
                    : "生成文件下载链接（有效期/提取码可设置）";
            }

            return SelectionCount > 1
                ? "暂不支持批量分享（请单选一个文件或文件夹）"
                : "选择一个文件或文件夹后可创建分享链接";
        }
    }

    public string MoveSelectionHint => CanMoveSelection
        ? "移动到当前空间的另一个目录"
        : "暂不支持批量移动（后端为单条目接口）";

    public string CopyLinkSelectionHint
    {
        get
        {
            if (!CanCopyLinkSelection)
            {
                return SelectionCount > 1
                    ? "暂不支持批量复制链接"
                    : "选择一个文件或文件夹后可复制分享链接";
            }

            return _selection.First().IsFolder
                ? "复制该文件夹已有的分享链接（没有时会提示先用「分享」创建）"
                : "复制该文件已有的分享链接（没有时会提示先用「分享」创建）";
        }
    }

    // ── 入口 ────────────────────────────────────────────────

    /// <summary>操作条「移动」按钮入口（单选文件或文件夹）。</summary>
    [RelayCommand]
    private void MoveSelection() => BeginMove();

    /// <summary>右键 / 操作条「移动」入口。</summary>
    public void BeginMove()
    {
        var node = Selected;
        if (node is null)
        {
            _notify.Notify("请先选中要移动的文件或文件夹", NoticeKind.Warning);
            return;
        }

        if (SelectionCount > 1)
        {
            _notify.Notify("一次只能移动一个条目，请先取消多选", NoticeKind.Warning);
            return;
        }

        MoveSource = node;
        MovePath = RootPath;
        MoveError = null;
        MoveHint = null;
        CollectionSync.Apply(MoveFolders, Array.Empty<NodeItem>());
        IsMoveOpen = true;
        _ = LoadMoveFoldersAsync();
    }

    [RelayCommand]
    private void CancelMove() => IsMoveOpen = false;

    [RelayCommand]
    private async Task MoveGoUpAsync()
    {
        if (!CanMoveGoUp) return;

        var trimmed = MovePath.Trim('/');
        var slash = trimmed.LastIndexOf('/');
        MovePath = slash < 0 ? RootPath : "/" + trimmed[..slash];
        await LoadMoveFoldersAsync().ConfigureAwait(true);
    }

    /// <summary>进入某个目标文件夹（View 的目录按钮调用）。</summary>
    public async Task OpenMoveFolderAsync(NodeItem folder)
    {
        if (IsMoving || !folder.IsFolder) return;

        var parent = string.IsNullOrWhiteSpace(folder.ParentPath) || folder.ParentPath == RootPath
            ? string.Empty
            : folder.ParentPath.TrimEnd('/');
        MovePath = $"{parent}/{folder.Name}";
        await LoadMoveFoldersAsync().ConfigureAwait(true);
    }

    private async Task LoadMoveFoldersAsync()
    {
        CollectionSync.Apply(MoveFolders, Array.Empty<NodeItem>());
        MoveError = null;
        MoveHint = null;

        var (folders, error) = await _files
            .ListFoldersAsync(_files.CurrentSpace, MovePath)
            .ConfigureAwait(true);

        if (error is not null)
        {
            MoveError = error;
            return;
        }

        if (folders is null)
        {
            MoveError = "无法加载目标目录，请重试";
            return;
        }

        // 不允许「把文件夹移动到它自己里面」：目标列表里剔除源自身（其余交给服务端校验）。
        var visible = MoveSource is { IsFolder: true } source
            ? folders.Where(f => !string.Equals(f.Id, source.Id, StringComparison.Ordinal)).ToList()
            : folders.ToList();

        CollectionSync.Apply(MoveFolders, visible);
        if (MoveFolders.Count == 0) MoveHint = "该目录下没有子文件夹，可直接移动到此";
    }

    [RelayCommand]
    private async Task SubmitMoveAsync()
    {
        if (MoveSource is null || IsMoving) return;

        if (NormalizePath(MovePath) == NormalizePath(MoveSource.ParentPath))
        {
            MoveError = "目标目录就是当前所在目录，无需移动";
            return;
        }

        IsMoving = true;
        MoveError = null;
        try
        {
            var result = await _files
                .MoveAsync(MoveSource, null, MovePath, null)
                .ConfigureAwait(true);

            if (!result.Ok)
            {
                MoveError = MapMoveError(result);
                return;
            }

            var directory = MovePath == RootPath ? "/" : MovePath;
            _notify.Notify($"已移动到 {directory}", NoticeKind.Success);
            IsMoveOpen = false;
            ClearSelection();
            await _files.ReloadListAsync().ConfigureAwait(true);
        }
        finally
        {
            IsMoving = false;
        }
    }

    /// <summary>失败文案：409 同名 / 404 目标不存在 / 400 非法路径 / 403 无权限 + 后端原文。</summary>
    private static string MapMoveError(CopyResult result)
    {
        var message = LoadRetry.Humanize(result.Message, "服务器未返回原因，请稍后重试");
        return result.Code switch
        {
            409 => $"目标目录已有同名项目：{message}",
            404 => $"目标目录不存在或源已被删除：{message}",
            400 => $"无法移动：{message}",
            403 => $"没有权限写入目标目录：{message}",
            _ => message
        };
    }

    private static string NormalizePath(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return "/";
        var trimmed = path.Trim();
        return trimmed.Length > 1 ? trimmed.TrimEnd('/') : trimmed;
    }

    // ── 操作条：分享 / 复制链接 ─────────────────────────────

    /// <summary>
    /// 分享入口：单选文件或文件夹时打开「创建分享链接」对话框（复用 task-33 流程）。
    /// task-183：文件夹链接的匿名下载是流式 ZIP，文案由 ShareTarget.IsFolder 区分。
    /// </summary>
    public void BeginCreateShareFor(NodeItem node)
    {
        if (SelectionCount > 1)
        {
            _notify.Notify("暂不支持批量分享，请先单选一个文件或文件夹", NoticeKind.Warning);
            return;
        }

        ShareTarget = node;
        ShareExpiry = ShareExpiryOptions[1];
        ShareRequiresCode = false;
        ShareCode = string.Empty;
        ShareMaxDownloads = "100";
        CreateShareError = null;
        CreatedShare = null;
        IsCreateShareOpen = true;
    }

    [RelayCommand]
    private void ShareSelection()
    {
        var node = _selection.Count == 1 ? _selection.First() : null;
        if (node is null)
        {
            _notify.Notify("请先选中要分享的文件或文件夹", NoticeKind.Warning);
            return;
        }

        if (SelectionCount > 1)
        {
            _notify.Notify("暂不支持批量分享，请先单选一个文件或文件夹", NoticeKind.Warning);
            return;
        }

        BeginCreateShareFor(node);
    }

    /// <summary>
    /// 复制链接：从「我的分享」里找该文件/文件夹当前有效的链接并复制。
    /// 没有链接时明确提示去用「分享」，不自动创建（创建带有效期/次数的链接是用户决策）。
    /// </summary>
    [RelayCommand]
    private async Task CopySelectionLinkAsync()
    {
        var node = _selection.Count == 1 ? _selection.First() : null;
        if (node is null)
        {
            _notify.Notify("请先选中要复制链接的文件或文件夹", NoticeKind.Warning);
            return;
        }

        var (shares, error) = await _files.GetSharesAsync().ConfigureAwait(true);
        if (shares is null)
        {
            _notify.Notify(LoadRetry.Humanize(error, "读取分享链接失败，请稍后重试"), NoticeKind.Error);
            return;
        }

        // 分享记录没有 fileId，只能按「文件名 + 类型 + 大小」对齐（列表返回的就是这些字段）。
        // task-183：文件夹 fileSize 恒为 0（体积以下行 ZIP 计），文件夹只按名称+类型匹配。
        var link = shares
            .Where(s => string.Equals(s.Status, "active", StringComparison.OrdinalIgnoreCase))
            .Where(s => s.IsDirectory == node.IsFolder)
            .Where(s => string.Equals(s.FileName, node.Name, StringComparison.Ordinal))
            .Where(s => node.IsFolder || s.FileSize == node.Size)
            .OrderByDescending(s => s.CreatedAt)
            .FirstOrDefault();

        if (link is null)
        {
            _notify.Notify(
                node.IsFolder ? "该文件夹还没有分享链接，请先用「分享」创建" : "该文件还没有分享链接，请先用「分享」创建",
                NoticeKind.Warning);
            return;
        }

        // View 负责写剪贴板并提示「链接已复制」（ViewModel 不直接碰剪贴板）。
        CopyRequested?.Invoke(this, link.Url);
    }
}
