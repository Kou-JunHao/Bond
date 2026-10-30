using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using Bond.Core;
using Bond.Models;
using Bond.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Bond.ViewModels;

/// <summary>
/// 文件页的「上传中虚拟行」（task-42）：上传中的任务在服务端 complete 之前不会出现在列表里，
/// 这里按队列顺序把它们按一个正常行位注入列表/平铺头部，让用户原地看到真实进度。
/// 完成时先刷新列表、再移除虚拟行（同位置替换，避免闪一下消失又出现）。
/// </summary>
public partial class FilesPageViewModel
{
    /// <summary>尚未完成的上传任务（永远排在最前，按队列顺序）。</summary>
    public ObservableCollection<UploadTaskItem> UploadingRows { get; } = [];

    /// <summary>把上传队列里的任务同步成虚拟行（在 UploadsChanged 与初始加载时调用）。</summary>
    private void SyncUploadRows()
    {
        if (!Dispatcher.UIThread.CheckAccess())
        {
            Dispatcher.UIThread.Post(SyncUploadRows);
            return;
        }

        // 1) 队列里已消失（被移除）的任务，行也移除
        for (var i = UploadingRows.Count - 1; i >= 0; i--)
        {
            if (!_uploads.Uploads.Contains(UploadingRows[i]))
                UnhookAndRemove(UploadingRows[i]);
        }

        // 2) 按队列顺序增量插入
        for (var queueIndex = 0; queueIndex < _uploads.Uploads.Count; queueIndex++)
        {
            var task = _uploads.Uploads[queueIndex];
            if (task.IsFinished || task.Status == UploadTaskStatus.Cancelled)
                continue;
            if (UploadingRows.Contains(task))
                continue;

            // 找到队列中排在它后面的第一个虚拟行，插到它前面；都没有就追加
            var insertAt = UploadingRows.Count;
            for (var i = 0; i < UploadingRows.Count; i++)
            {
                var otherIndex = _uploads.Uploads.IndexOf(UploadingRows[i]);
                if (otherIndex > queueIndex)
                {
                    insertAt = i;
                    break;
                }
            }

            UploadingRows.Insert(insertAt, task);
            task.PropertyChanged += OnUploadRowPropertyChanged;
        }
    }

    private void OnUploadRowPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (sender is not UploadTaskItem task) return;
        if (e.PropertyName != nameof(UploadTaskItem.Status)) return;

        if (task.Status == UploadTaskStatus.Completed)
        {
            _ = ReplaceCompletedRowAsync(task);
        }
        else if (task.Status == UploadTaskStatus.Cancelled)
        {
            UnhookAndRemove(task);
        }
    }

    /// <summary>
    /// 完成 → 同位置替换：先刷新列表（真实文件已在服务端），再移除虚拟行。
    /// 刷新后虚拟行与真实行会在同一位置短暂共存（虚拟在上），不会出现"闪一下消失又出现"。
    /// </summary>
    private async Task ReplaceCompletedRowAsync(UploadTaskItem task)
    {
        try
        {
            await _files.ReloadListAsync().ConfigureAwait(true);
        }
        catch
        {
            // 刷新失败也让虚拟行消失：真实文件在下一次刷新会出现
        }

        await Task.Delay(80).ConfigureAwait(true);
        UnhookAndRemove(task);
    }

    private void UnhookAndRemove(UploadTaskItem task)
    {
        if (!Dispatcher.UIThread.CheckAccess())
        {
            Dispatcher.UIThread.Post(() => UnhookAndRemove(task));
            return;
        }

        task.PropertyChanged -= OnUploadRowPropertyChanged;
        UploadingRows.Remove(task);
    }

    [RelayCommand]
    private void CancelUploadRow(UploadTaskItem? task)
    {
        if (task is null) return;
        _uploads.Cancel(task);
    }

    [RelayCommand]
    private async Task ResumeUploadRow(UploadTaskItem? task)
    {
        if (task is null) return;
        await _uploads.ResumeAsync(task).ConfigureAwait(true);
    }

    // ═══════════════════════════════════════════════════════════
    //  task-4：上传到指定文件夹（「上传到…」）
    //  复用「移动到…」的目标目录选择器模式：在当前空间里逐级浏览目标目录，
    //  确认后把文件页真实切到该目录再入队 —— 上传任务的 ParentPath 由服务在
    //  Enqueue 时按文件页当前目录固化，这样上传结果与用户选择的目录严格一致。
    // ═══════════════════════════════════════════════════════════

    /// <summary>目标目录选择器里当前层的子文件夹。</summary>
    public ObservableCollection<NodeItem> UploadFolders { get; } = [];

    [ObservableProperty] private bool _isUploadTargetOpen;
    [ObservableProperty] private string _uploadTargetPath = RootPath;
    [ObservableProperty] private string? _uploadTargetError;
    [ObservableProperty] private string? _uploadTargetHint;
    [ObservableProperty] private bool _isUploadTargetBusy;

    public string UploadTargetPathText =>
        string.IsNullOrEmpty(UploadTargetPath) || UploadTargetPath == RootPath
            ? "/（根目录）"
            : UploadTargetPath;

    public bool CanUploadTargetGoUp =>
        !string.IsNullOrEmpty(UploadTargetPath) && UploadTargetPath != RootPath;

    /// <summary>目标空间提示：目录选择器只在当前空间里浏览（换工作区请先在页面顶部切换空间）。</summary>
    public string UploadTargetSpaceText => $"目标空间：{CurrentSpaceName}";

    partial void OnUploadTargetPathChanged(string value)
    {
        OnPropertyChanged(nameof(UploadTargetPathText));
        OnPropertyChanged(nameof(CanUploadTargetGoUp));
    }

    /// <summary>工具栏「上传到…」入口。</summary>
    [RelayCommand]
    private void OpenUploadTarget()
    {
        UploadTargetPath = RootPath;
        UploadTargetError = null;
        UploadTargetHint = null;
        CollectionSync.Apply(UploadFolders, Array.Empty<NodeItem>());
        OnPropertyChanged(nameof(UploadTargetSpaceText));
        IsUploadTargetOpen = true;
        _ = LoadUploadFoldersAsync();
    }

    [RelayCommand]
    private void CancelUploadTarget() => IsUploadTargetOpen = false;

    [RelayCommand]
    private async Task UploadTargetGoUpAsync()
    {
        if (!CanUploadTargetGoUp) return;

        var trimmed = UploadTargetPath.Trim('/');
        var slash = trimmed.LastIndexOf('/');
        UploadTargetPath = slash < 0 ? RootPath : "/" + trimmed[..slash];
        await LoadUploadFoldersAsync().ConfigureAwait(true);
    }

    /// <summary>进入某个目标文件夹（目录按钮调用）。</summary>
    public async Task OpenUploadTargetFolderAsync(NodeItem? folder)
    {
        if (IsUploadTargetBusy || folder is null || !folder.IsFolder) return;

        var parent = string.IsNullOrWhiteSpace(folder.ParentPath) || folder.ParentPath == RootPath
            ? string.Empty
            : folder.ParentPath.TrimEnd('/');
        UploadTargetPath = $"{parent}/{folder.Name}";
        await LoadUploadFoldersAsync().ConfigureAwait(true);
    }

    private async Task LoadUploadFoldersAsync()
    {
        CollectionSync.Apply(UploadFolders, Array.Empty<NodeItem>());
        UploadTargetError = null;
        UploadTargetHint = null;

        var (folders, error) = await _files
            .ListFoldersAsync(_files.CurrentSpace, UploadTargetPath)
            .ConfigureAwait(true);

        if (error is not null)
        {
            UploadTargetError = error;
            return;
        }

        if (folders is null)
        {
            UploadTargetError = "无法加载目标目录，请重试";
            return;
        }

        CollectionSync.Apply(UploadFolders, folders.ToList());
        if (UploadFolders.Count == 0) UploadTargetHint = "该目录下没有子文件夹，可直接上传到此";
    }

    /// <summary>打开系统文件选择器（多选），返回本机绝对路径；取消/拿不到路径时返回空数组。</summary>
    private async Task<string[]> PickUploadFilesAsync(string title)
    {
        var top = App.ShellTopLevel;
        if (top is null) return [];

        var picked = await top.StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = title,
            AllowMultiple = true
        }).ConfigureAwait(true);

        return picked.Where(f => f.TryGetLocalPath() is not null)
                     .Select(f => f.TryGetLocalPath()!)
                     .ToArray();
    }

    /// <summary>
    /// 「上传到此」：先选本地文件，再切到目标目录并入队。
    /// 顺序很重要——服务在 Enqueue 时读取文件页当前目录作为 ParentPath，
    /// 因此必须先 NavigateTo 目标目录、再 Enqueue，上传才真正落在用户选择的位置。
    /// 取消文件选择时不切换目录、不入队。
    /// </summary>
    [RelayCommand]
    private async Task SubmitUploadTargetAsync()
    {
        if (IsUploadTargetBusy) return;
        IsUploadTargetBusy = true;
        try
        {
            var paths = await PickUploadFilesAsync($"上传到 {UploadTargetPathText}").ConfigureAwait(true);
            if (paths.Length == 0) return;   // 用户取消：保持对话框，不做任何切换

            var target = UploadTargetPath;
            IsUploadTargetOpen = false;

            // 切到目标目录（服务端列表按该路径刷新，上传完成后文件就出现在这里）
            if (NormalizeFolderPath(_files.CurrentPath) != NormalizeFolderPath(target))
                _files.NavigateTo(target);
            Path = _files.CurrentPath;
            Keyword = string.Empty;
            ClearSelection();
            SyncFromService();

            _uploads.Enqueue(paths);
            _notify.Notify(
                $"已加入上传队列：{paths.Length} 个文件 → {(target == RootPath ? "根目录" : target)}",
                NoticeKind.Success);
            SyncUploadOverlays();
        }
        finally
        {
            IsUploadTargetBusy = false;
        }
    }
}
