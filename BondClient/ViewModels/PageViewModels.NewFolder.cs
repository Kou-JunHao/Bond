using System;
using System.Linq;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Bond.Models;

namespace Bond.ViewModels;

/// <summary>
/// task-120：新建文件夹。入口在文件页工具栏与右键菜单；
/// task-130：对话框重排（标题 → 目标目录说明 → 输入框 → 预留错误行 → 按钮），
/// 输入框改为实时校验，命名规则与后端 FileFolderService 完全一致（# % 等字符合法）。
/// </summary>
public partial class FilesPageViewModel
{
    // 与后端 FileFolderService 实际规则对齐（实测：/ \ : * ? " < > | 与 . / .. 被拒；# % 允许）
    private static readonly char[] InvalidFolderChars = ['/', '\\', ':', '*', '?', '"', '<', '>', '|'];

    private const int MaxFolderNameLength = 255;
    private const int TargetTextMaxLength = 58;

    /// <summary>创建成功后请求把该行/卡片滚动到可见（由页面 code-behind 处理）。</summary>
    public event Action<NodeItem>? RevealRequested;

    [ObservableProperty]
    private bool _isNewFolderOpen;

    [ObservableProperty]
    private string _newFolderName = "新建文件夹";

    [ObservableProperty]
    private string? _newFolderError;

    [ObservableProperty]
    private bool _isCreatingFolder;

    /// <summary>task-130：本地实时校验错误（与服务端错误共用错误行，但只有它才禁用「创建」）。</summary>
    private string? _folderValidationError;

    /// <summary>task-130：说明行「将创建在 个人空间 · /zz-probe」，过长中间省略。</summary>
    public string NewFolderTargetText
    {
        get
        {
            var parent = string.IsNullOrWhiteSpace(Path) || Path == "/" ? "根目录" : Path!;
            return EllipsizeMiddle($"将创建在 {CurrentSpaceName} · {parent}", TargetTextMaxLength);
        }
    }

    /// <summary>task-130：提交中按钮文案「正在创建…」。</summary>
    public string NewFolderSubmitText => IsCreatingFolder ? "正在创建…" : "创建";

    /// <summary>打开命名弹窗：预填「新建文件夹」并全选文本（由弹窗 OnOpened 处理）。</summary>
    public void BeginCreateFolder()
    {
        _folderValidationError = null;
        NewFolderName = "新建文件夹";
        NewFolderError = null;
        IsNewFolderOpen = true;
        OnPropertyChanged(nameof(NewFolderTargetText));
    }

    [RelayCommand]
    private void CancelCreateFolder()
    {
        if (IsCreatingFolder)
        {
            return;
        }

        IsNewFolderOpen = false;
        NewFolderError = null;
        _folderValidationError = null;
    }

    [RelayCommand]
    private async Task CreateFolderAsync()
    {
        if (IsCreatingFolder)
        {
            return;
        }

        // 实时校验已经同步过错误行；这里再跑一次，避免「错误提示出现后按钮又被键盘触发」。
        ValidateNewFolderName();
        if (NewFolderError is not null)
        {
            return;
        }

        var name = (NewFolderName ?? "").Trim();
        if (name.Length == 0)
        {
            return;
        }

        try
        {
            IsCreatingFolder = true;
            NewFolderError = null;

            var (ok, error) = await _files.CreateFolderAsync(_files.CurrentPath, name);
            if (!ok)
            {
                // 后端 400/409/404 的 message 原样显示在错误行，不弹 toast。
                NewFolderError = error ?? "新建文件夹失败";
                return;
            }

            IsNewFolderOpen = false;
            await _files.ReloadListAsync();

            // 真实回刷后按名称找到新目录（不伪造行），1.5 秒高亮并滚动到可见。
            var created = View.FirstOrDefault(n => n.IsFolder
                && string.Equals(n.Name, name, StringComparison.Ordinal));
            if (created is not null)
            {
                created.IsNewlyCreated = true;
                Avalonia.Threading.DispatcherTimer.RunOnce(
                    () => created.IsNewlyCreated = false, TimeSpan.FromMilliseconds(1500));
                RevealRequested?.Invoke(created);
            }
        }
        catch (Exception ex)
        {
            NewFolderError = ex.Message;
        }
        finally
        {
            IsCreatingFolder = false;
        }
    }

    /// <summary>task-128/130：名称为空或本地校验不通过时禁用「创建」；服务端错误不禁用（可改后再试）。</summary>
    public bool CanSubmitNewFolder =>
        !IsCreatingFolder && !string.IsNullOrWhiteSpace(NewFolderName) && _folderValidationError is null;

    public bool CanCancelNewFolder => !IsCreatingFolder;

    /// <summary>task-130：输入框实时校验；空值不报错（只是禁用提交）。</summary>
    private void ValidateNewFolderName()
    {
        var name = (NewFolderName ?? string.Empty).Trim();
        _folderValidationError =
            name.Length == 0 ? null
            : name.Length > MaxFolderNameLength ? $"名称最多 {MaxFolderNameLength} 个字符"
            : name.IndexOfAny(InvalidFolderChars) >= 0 ? "名称不能包含 / \\ : * ? \" < > |"
            : name is "." or ".." ? "不能使用 . 或 .."
            : null;
        NewFolderError = _folderValidationError;
        OnPropertyChanged(nameof(CanSubmitNewFolder));
    }

    /// <summary>中间省略，保留首尾（用于「个人空间 · /很长的/路径」这类说明行）。</summary>
    private static string EllipsizeMiddle(string text, int maxLength)
    {
        if (text.Length <= maxLength)
        {
            return text;
        }

        var left = (maxLength - 1) / 2;
        var right = maxLength - 1 - left;
        return string.Concat(text.AsSpan(0, left), "…", text.AsSpan(text.Length - right, right));
    }

    partial void OnIsCreatingFolderChanged(bool value)
    {
        OnPropertyChanged(nameof(CanSubmitNewFolder));
        OnPropertyChanged(nameof(CanCancelNewFolder));
        OnPropertyChanged(nameof(NewFolderSubmitText));
    }

    partial void OnNewFolderNameChanged(string value)
    {
        ValidateNewFolderName();
        OnPropertyChanged(nameof(CanSubmitNewFolder));
    }
}
