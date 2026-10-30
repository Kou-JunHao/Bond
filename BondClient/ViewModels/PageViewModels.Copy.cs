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
/// task-105：文件转存（服务端复制）目标选择器。
/// 目标空间 = 我的空间 + 我加入的工作区（可写性由服务端校验，403 时展示后端原文）；
/// 选目录（逐层进入/返回）→ 可改文件名 → 提交，成功后 Toast 并回刷列表（若当前页就是目标目录）。
/// </summary>
public partial class FilesPageViewModel
{
    private const string RootPath = "/";

    /// <summary>目标空间选项（我的空间 + 可写工作区；不确定可写时全列，失败交给服务端 403 原文）。</summary>
    public ObservableCollection<FileSpace> CopySpaces { get; } = [];

    public ObservableCollection<NodeItem> CopyFolders { get; } = [];

    [ObservableProperty] private bool _isCopyOpen;
    [ObservableProperty] private NodeItem? _copySource;
    [ObservableProperty] private FileSpace? _copySpace;
    [ObservableProperty] private string _copyPath = RootPath;
    [ObservableProperty] private string _copyNewName = string.Empty;
    [ObservableProperty] private string? _copyError;
    [ObservableProperty] private bool _isCopying;
    [ObservableProperty] private string? _copyHint;

    public string CopyPathText =>
        string.IsNullOrEmpty(CopyPath) || CopyPath == RootPath ? "/（根目录）" : CopyPath;

    public bool CanCopyGoUp => !string.IsNullOrEmpty(CopyPath) && CopyPath != RootPath;

    partial void OnCopyPathChanged(string value)
    {
        OnPropertyChanged(nameof(CopyPathText));
        OnPropertyChanged(nameof(CanCopyGoUp));
    }

    /// <summary>右键「转存到…」入口：只支持单个文件；文件夹与多选给出提示。</summary>
    public void BeginCopy()
    {
        var file = Selected;
        if (file is null)
        {
            _notify.Notify("请先选中一个文件", NoticeKind.Warning);
            return;
        }

        if (SelectionCount > 1)
        {
            _notify.Notify("本期仅支持单个文件转存（多选请逐个操作）", NoticeKind.Warning);
            return;
        }

        if (!file.IsFile)
        {
            _notify.Notify("文件夹暂不支持转存，请选择单个文件", NoticeKind.Warning);
            return;
        }

        CopySource = file;
        CopyNewName = file.Name;
        CopyPath = RootPath;
        CopyError = null;
        CopyHint = null;
        // task-135：打开转存面板时清空/填充都走 CollectionSync（Reset 会让目录行残留）。
        CollectionSync.Apply(CopyFolders, Array.Empty<NodeItem>());
        CollectionSync.Apply(CopySpaces, _files.Spaces.ToList());
        CopySpace = CopySpaces.FirstOrDefault(s => s.IsPersonal) ?? CopySpaces.FirstOrDefault();
        IsCopyOpen = true;
        _ = LoadCopyFoldersAsync();
    }

    [RelayCommand]
    private void CancelCopy() => IsCopyOpen = false;

    /// <summary>切换目标空间（View 的 ComboBox SelectionChanged 调用）。</summary>
    public async Task SetCopySpaceAsync(FileSpace? space)
    {
        if (IsCopying) return;
        CopySpace = space;
        CopyPath = RootPath;
        CopyError = null;
        await LoadCopyFoldersAsync().ConfigureAwait(true);
    }

    private async Task LoadCopyFoldersAsync()
    {
        // task-135：切换目录时逐项清空，避免 Reset 残留旧目录行。
        CollectionSync.Apply(CopyFolders, Array.Empty<NodeItem>());
        CopyError = null;
        CopyHint = null;

        var (folders, error) = await _files
            .ListFoldersAsync(CopySpace, CopyPath)
            .ConfigureAwait(true);

        if (error is not null)
        {
            CopyError = error;
            return;
        }

        if (folders is null)
        {
            CopyError = "无法加载目标目录，请重试";
            return;
        }

        CollectionSync.Apply(CopyFolders, folders);
        if (CopyFolders.Count == 0) CopyHint = "该目录下没有子文件夹，可直接转存到此处";
    }

    [RelayCommand]
    private async Task CopyGoUpAsync()
    {
        if (!CanCopyGoUp) return;

        var trimmed = CopyPath.Trim('/');
        var slash = trimmed.LastIndexOf('/');
        CopyPath = slash < 0 ? RootPath : "/" + trimmed[..slash];
        await LoadCopyFoldersAsync().ConfigureAwait(true);
    }

    /// <summary>进入某个目标文件夹（View 的目录按钮调用）。</summary>
    public async Task EnterCopyFolderAsync(NodeItem folder)
    {
        if (IsCopying || !folder.IsFolder) return;

        var parent = string.IsNullOrWhiteSpace(folder.ParentPath) || folder.ParentPath == RootPath
            ? string.Empty
            : folder.ParentPath.TrimEnd('/');
        CopyPath = $"{parent}/{folder.Name}";
        await LoadCopyFoldersAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private async Task SubmitCopyAsync()
    {
        if (CopySource is null || IsCopying) return;

        if (CopySpace is null)
        {
            CopyError = "请选择目标空间";
            return;
        }

        IsCopying = true;
        CopyError = null;
        try
        {
            var targetWorkspaceId = CopySpace.IsPersonal ? null : CopySpace.WorkspaceId;
            var newName = string.IsNullOrWhiteSpace(CopyNewName) ? CopySource.Name : CopyNewName.Trim();

            var result = await _files
                .CopyAsync(CopySource, targetWorkspaceId, CopyPath, newName)
                .ConfigureAwait(true);

            if (!result.Ok)
            {
                CopyError = MapCopyError(result);
                return;
            }

            var directory = CopyPath == RootPath ? "/" : CopyPath;
            _notify.Notify(
                $"已转存到 {CopySpace.Name}{directory}（占用 {NodeItem.FormatSize(result.SizeBytes)}）· 服务端复制，不消耗流量",
                NoticeKind.Success);

            IsCopyOpen = false;

            // 若当前文件页正好停在目标目录，立刻回刷，避免“复制了但看不见”。
            if (_files.CurrentSpace?.Id == CopySpace.Id
                && string.Equals(_files.CurrentPath ?? RootPath, CopyPath, StringComparison.OrdinalIgnoreCase))
            {
                await _files.ReloadListAsync().ConfigureAwait(true);
            }
        }
        finally
        {
            IsCopying = false;
        }
    }

    /// <summary>失败文案：413/400/404/403 分类前缀 + 后端原文（不自造整句）。task-151：无原文时不再显示「未知错误」。</summary>
    private static string MapCopyError(CopyResult result)
    {
        var message = LoadRetry.Humanize(result.Message, "服务器未返回原因，请稍后重试");
        return result.Code switch
        {
            413 => $"目标空间配额不足：{message}",
            400 => $"目标位置无效或已有同名文件：{message}",
            404 => $"源文件不存在或已被删除：{message}",
            403 => $"没有权限写入目标空间：{message}",
            _ => message
        };
    }
}
