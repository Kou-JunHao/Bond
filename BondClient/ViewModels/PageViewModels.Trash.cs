using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Threading.Tasks;
using Bond.Core;
using Bond.Models;
using Bond.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Bond.ViewModels;

/// <summary>
/// FilesPageViewModel 的回收站部分（task-38）。
/// 三态分离：IsTrashLoading / TrashError / ShowTrashEmpty（请求成功且 0 条才算空）。
/// </summary>
public partial class FilesPageViewModel
{
    /// <summary>回收站列表（顶层条目）。</summary>
    public ObservableCollection<TrashRow> TrashItems { get; } = [];

    [ObservableProperty] private bool _isTrashOpen;

    [ObservableProperty] private bool _isTrashLoading;

    [ObservableProperty] private string? _trashError;

    /// <summary>顶部说明：回收站保留 N 天 / 不限期。</summary>
    [ObservableProperty] private string _trashRetentionText = "过期后自动清理";

    /// <summary>操作结果横幅（恢复/删除/清空的成功与部分失败逐条原因）。</summary>
    [ObservableProperty] private string? _trashResult;

    [ObservableProperty] private bool _isPurgeTrashConfirmOpen;

    [ObservableProperty] private bool _isEmptyTrashConfirmOpen;

    [ObservableProperty] private string _emptyTrashConfirmText = string.Empty;

    [ObservableProperty] private bool _isTrashBusy;

    public bool ShowTrashError => !IsTrashLoading && !string.IsNullOrEmpty(TrashError);

    public bool ShowTrashEmpty => !IsTrashLoading && !ShowTrashError && TrashItems.Count == 0;

    public bool ShowTrashResult => !string.IsNullOrEmpty(TrashResult);

    public bool HasTrashSelection => TrashItems.Any(t => t.IsSelected);

    /// <summary>
    /// task-4：回收站跟随文件页的当前空间。后端 <c>GET /api/trash</c> 按 workspaceId 过滤，
    /// 不传即个人空间——所以团队工作区里删除的文件必须在该工作区的回收站里看/恢复。
    /// </summary>
    public string TrashSpaceCaption => IsPersonalSpace ? "个人空间" : CurrentSpaceName;

    /// <summary>作用域说明（悬停）：讲清「这个回收站属于哪个空间、恢复会回到哪里」。</summary>
    public string TrashSpaceHint => IsPersonalSpace
        ? "这里是个人空间的回收站；切到团队工作区后，回收站会显示该工作区的文件"
        : $"这里是「{CurrentSpaceName}」的回收站；恢复的文件回到该工作区删除前的原路径";

    /// <summary>头部保留说明是否可显示（拿不到档位/保留信息时整句隐藏，不编造）。</summary>
    public bool HasTrashRetentionNote => !string.IsNullOrEmpty(TrashRetentionText);

    /// <summary>彻底删除确认文案：明确「永久」+ 具体文件名。</summary>
    public string PurgeTrashConfirmDetail
    {
        get
        {
            var names = TrashItems.Where(t => t.IsSelected).Select(t => t.FileName).ToList();
            var detail = names.Count == 0
                ? "所选文件"
                : string.Join("、", names.Take(3)) + (names.Count > 3 ? $" 等 {names.Count} 项" : "");
            return $"将永久删除：{detail}。文件将被永久删除，无法恢复，分享链接会立即失效。";
        }
    }

    public int TrashSelectionCount => TrashItems.Count(t => t.IsSelected);

    public string TrashSelectionText => TrashSelectionCount == 1
        ? $"已选 1 项 · {TrashItems.First(t => t.IsSelected).FileName}"
        : $"已选 {TrashSelectionCount} 项";

    /// <summary>全选开关：列表非空且全部选中时为 true；设为 true 全选、false 全不选。</summary>
    public bool TrashAllSelected
    {
        get => TrashItems.Count > 0 && TrashItems.All(t => t.IsSelected);
        set
        {
            if (TrashItems.Count == 0) return;
            foreach (var row in TrashItems) row.IsSelected = value;
            NotifyTrashComputed();
        }
    }

    public bool IsEmptyTrashConfirmValid => string.Equals(
        EmptyTrashConfirmText?.Trim(), "清空", StringComparison.Ordinal);

    partial void OnIsTrashLoadingChanged(bool value) => NotifyTrashComputed();

    partial void OnTrashErrorChanged(string? value) => NotifyTrashComputed();

    partial void OnTrashResultChanged(string? value) => OnPropertyChanged(nameof(ShowTrashResult));

    partial void OnTrashRetentionTextChanged(string value)
        => OnPropertyChanged(nameof(HasTrashRetentionNote));

    partial void OnEmptyTrashConfirmTextChanged(string value)
        => OnPropertyChanged(nameof(IsEmptyTrashConfirmValid));

    // ── 命令 ──────────────────────────────────────────────────────────

    [RelayCommand]
    private async Task OpenTrashAsync()
    {
        IsTrashOpen = true;
        await LoadTrashAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private void CloseTrash()
    {
        IsTrashOpen = false;
        TrashError = null;
        TrashResult = null;
        IsPurgeTrashConfirmOpen = false;
        IsEmptyTrashConfirmOpen = false;
        ClearTrashSelection();
        // 回收站里的恢复会改文件列表，返回时刷新一次（真实接口）。
        _ = _files.ReloadListAsync();
    }

    [RelayCommand]
    private Task RefreshTrashAsync() => LoadTrashAsync();

    [RelayCommand]
    private void CancelTrashAction()
    {
        IsPurgeTrashConfirmOpen = false;
        IsEmptyTrashConfirmOpen = false;
        EmptyTrashConfirmText = string.Empty;
    }

    [RelayCommand]
    private void ClearTrashSelection()
    {
        foreach (var row in TrashItems) row.IsSelected = false;
        NotifyTrashComputed();
    }

    /// <summary>
    /// task-4：平铺视图里单击卡片 = 勾选/取消（平铺没有列表视图那种勾选框，
    /// 没有这个入口就无法多选，底部批量「恢复」永远不会出现）。
    /// </summary>
    public void ToggleTrashRowSelection(TrashRow? row)
    {
        if (row is null) return;
        row.IsSelected = !row.IsSelected;
    }

    [RelayCommand]
    private async Task RestoreTrashRowAsync(TrashRow? row)
    {
        if (row is null) return;
        await RunRestoreAsync([row.Id]).ConfigureAwait(true);
    }

    [RelayCommand]
    private async Task RestoreTrashSelectionAsync()
    {
        var ids = TrashItems.Where(t => t.IsSelected).Select(t => t.Id).ToList();
        if (ids.Count == 0)
        {
            TrashResult = "先勾选要恢复的文件";
            return;
        }

        await RunRestoreAsync(ids).ConfigureAwait(true);
    }

    [RelayCommand]
    private void RequestPurgeTrash()
    {
        if (!HasTrashSelection)
        {
            TrashResult = "先勾选要彻底删除的文件";
            return;
        }

        IsPurgeTrashConfirmOpen = true;
    }

    [RelayCommand]
    private void RequestClearTrash()
    {
        EmptyTrashConfirmText = string.Empty;
        IsEmptyTrashConfirmOpen = true;
    }

    [RelayCommand]
    private async Task ConfirmPurgeTrashAsync()
    {
        var ids = TrashItems.Where(t => t.IsSelected).Select(t => t.Id).ToList();
        IsPurgeTrashConfirmOpen = false;
        if (ids.Count == 0) return;
        await RunPurgeAsync(ids).ConfigureAwait(true);
    }

    [RelayCommand]
    private async Task ConfirmClearTrashAsync()
    {
        if (!IsEmptyTrashConfirmValid) return;
        IsEmptyTrashConfirmOpen = false;
        IsTrashBusy = true;
        try
        {
            var (purged, error) = await _trash.EmptyAsync().ConfigureAwait(true);
            if (error is not null)
            {
                TrashResult = $"清空失败：{error}";
                _notify.Notify(TrashResult, NoticeKind.Error);
                return;
            }

            TrashResult = purged > 0 ? $"已清空回收站（{purged} 项）" : "回收站已经是空的";
            _notify.Notify(TrashResult, NoticeKind.Success);
            ClearTrashSelection();
            await LoadTrashAsync().ConfigureAwait(true);
            await _files.ReloadListAsync().ConfigureAwait(true);
        }
        finally
        {
            IsTrashBusy = false;
        }
    }

    // ── 实现 ──────────────────────────────────────────────────────────

    private async Task LoadTrashAsync()
    {
        IsTrashLoading = true;
        TrashError = null;
        try
        {
            var (items, error) = await _trash.GetTrashAsync().ConfigureAwait(true);

            // task-135：回收站是「Clear+Add」的重灾区——Clear 的 Reset 会让 ItemsControl
            // 残留旧行，表现为刷新一次多一行。这里按 Id 复用已有行，再逐项同步。
            var previous = TrashItems.ToList();
            var existing = previous.ToDictionary(row => row.Id, StringComparer.Ordinal);
            var fresh = new List<TrashRow>(items.Count);
            foreach (var item in items)
            {
                if (existing.TryGetValue(item.Id, out var reused))
                {
                    fresh.Add(reused);
                    continue;
                }

                var row = new TrashRow(item);
                row.PropertyChanged += OnTrashRowPropertyChanged;
                fresh.Add(row);
            }

            CollectionSync.Apply(TrashItems, fresh, ReferenceEquals);

            foreach (var stale in previous.Except(fresh))
            {
                stale.PropertyChanged -= OnTrashRowPropertyChanged;
            }

            TrashError = error;
            await UpdateTrashRetentionAsync().ConfigureAwait(true);
        }
        catch (Exception ex)
        {
            TrashError = $"加载回收站失败：{ex.Message}";
            foreach (var row in TrashItems)
            {
                row.PropertyChanged -= OnTrashRowPropertyChanged;
            }

            CollectionSync.Apply(TrashItems, Array.Empty<TrashRow>());
        }
        finally
        {
            IsTrashLoading = false;
            NotifyTrashComputed();
        }
    }

    private async Task UpdateTrashRetentionAsync()
    {
        string? planName = null;
        int? days = null;
        try
        {
            var entitlements = await _files.GetEntitlementsAsync().ConfigureAwait(true);
            planName = entitlements?.PlanName;
            days = entitlements?.TrashRetentionDays;
        }
        catch
        {
            // 拿不到档位信息就不显示档位文案，不编造。
        }

        // 只认后端字段；没有该字段就不显示整句（task-113 修正：禁止从列表反推冒充事实）。
        TrashRetentionText = BuildRetentionText(planName, days);
        OnPropertyChanged(nameof(HasTrashRetentionNote));
    }

    private static string BuildRetentionText(string? planName, int? days)
    {
        if (days is null) return string.Empty;
        var tier = string.IsNullOrWhiteSpace(planName) ? "当前档位" : planName.Trim();
        return days switch
        {
            0 => $"按{tier}删除的文件不限期保留，不会被自动清理",
            var d => $"按{tier}删除的文件在回收站保留 {d} 天，到期后自动清理"
        };
    }

    private async Task RunRestoreAsync(IReadOnlyList<string> ids)
    {
        IsTrashBusy = true;
        try
        {
            // 先记住要恢复的行：接口返回后列表会刷新，这些行就不在 TrashItems 里了。
            var wanted = TrashItems.Where(t => ids.Contains(t.Id)).ToList();

            var result = await _trash.RestoreAsync(ids).ConfigureAwait(true);
            TrashResult = FormatBatchResult("已恢复", result);
            _notify.Notify(
                result.Failed.Count == 0 ? $"已恢复 {result.Succeeded.Count} 项" : "部分文件恢复失败，详见回收站提示",
                result.Failed.Count == 0 ? NoticeKind.Success : NoticeKind.Warning);

            await LoadTrashAsync().ConfigureAwait(true);
            await _files.ReloadListAsync().ConfigureAwait(true);

            // task-4：恢复成功后把用户带到文件真正落地的地方并选中它。
            // 只在单选时跳转（批量恢复各自回到自己的原目录，跳其中一个反而困惑）。
            var restored = wanted
                .Where(t => result.Succeeded.Contains(t.Id, StringComparer.Ordinal))
                .ToList();
            if (restored.Count == 1)
                await RevealRestoredRowAsync(restored[0]).ConfigureAwait(true);
        }
        finally
        {
            IsTrashBusy = false;
        }
    }

    /// <summary>
    /// 恢复后定位：在文件页打开原目录并选中该条目（回收站覆盖层保持打开，用户关掉它就看到文件）。
    /// 后端遇到同名会改名为 <c>xxx (1).ext</c>；原目录已被删时文件会落回根目录，
    /// 因此定位不到时给出准确提示（不假装成功）。
    /// </summary>
    private async Task RevealRestoredRowAsync(TrashRow row)
    {
        // 原路径形如 /设计稿/旧项目/report.pdf；目录 = 去掉最后一段。
        var original = row.Item.OriginalPath?.Replace('\\', '/') ?? string.Empty;
        var directory = "/";
        if (original.Length > 0)
        {
            var trimmed = original.TrimEnd('/');
            var slash = trimmed.LastIndexOf('/');
            if (slash > 0) directory = trimmed[..slash];
            else if (slash < 0) directory = "/";
        }

        var found = await RevealCloudFileAsync(directory, row.FileName).ConfigureAwait(true);
        if (found)
        {
            _notify.Notify($"已恢复到 {directory}（关掉回收站即可看到并选中它）", NoticeKind.Success);
            return;
        }

        // 同名冲突自动改名：后端格式固定为「原名 (1).ext」，只认这个前缀，避免把无关文件当成本次恢复
        var stem = row.FileName;
        var dot = stem.LastIndexOf('.');
        if (dot > 0) stem = stem[..dot];
        var renamedPrefix = $"{stem} (";
        var renamed = View.FirstOrDefault(n =>
            n.Name.StartsWith(renamedPrefix, StringComparison.OrdinalIgnoreCase));
        if (renamed is not null)
        {
            Selected = renamed;
            RevealRequested?.Invoke(renamed);
            _notify.Notify($"已恢复（同名冲突已自动改名为「{renamed.Name}」）", NoticeKind.Success);
            return;
        }

        // 笔记（文档）与文件共用一个回收站：恢复的笔记回到「笔记」页，不在文件列表里，
        // 这种情况如实说明，而不是让用户以为恢复失败。
        _notify.Notify(
            $"已恢复「{row.FileName}」；文件列表里没有它时请到「笔记」页查看（原路径：{original}）",
            NoticeKind.Success);
    }

    private async Task RunPurgeAsync(IReadOnlyList<string> ids)
    {
        IsTrashBusy = true;
        try
        {
            var result = await _trash.PurgeAsync(ids).ConfigureAwait(true);
            TrashResult = FormatBatchResult("已彻底删除", result);
            _notify.Notify(
                result.Failed.Count == 0 ? $"已彻底删除 {result.Succeeded.Count} 项" : "部分文件删除失败，详见回收站提示",
                result.Failed.Count == 0 ? NoticeKind.Success : NoticeKind.Warning);

            await LoadTrashAsync().ConfigureAwait(true);
            await _files.ReloadListAsync().ConfigureAwait(true);
        }
        finally
        {
            IsTrashBusy = false;
        }
    }

    /// <summary>
    /// 成功/部分失败统一文案：成功项提示“已恢复”，207 的失败项逐条带原因。
    /// 恢复时服务端遇同名会自动改名 <c>xxx (1).ext</c>，这里一并说明。
    /// </summary>
    private string FormatBatchResult(string action, TrashOpResult result)
    {
        var text = $"{action} {result.Succeeded.Count} 项";
        if (action == "已恢复")
            text += "（同名冲突时已自动改名为 xxx (1).ext）";

        if (result.Failed.Count > 0)
        {
            var details = string.Join("；", result.Failed
                .Take(3)
                .Select(f => $"{ResolveTrashName(f.Id)}（{f.Reason}）"));
            text += $"。失败 {result.Failed.Count} 项：{details}";
            if (result.Failed.Count > 3) text += "…";
        }

        return text;
    }

    private string ResolveTrashName(string id)
        => TrashItems.FirstOrDefault(t => t.Id == id)?.FileName ?? id;

    private void OnTrashRowPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName != nameof(TrashRow.IsSelected)) return;
        NotifyTrashComputed();
    }

    private void NotifyTrashComputed()
    {
        OnPropertyChanged(nameof(ShowTrashError));
        OnPropertyChanged(nameof(ShowTrashEmpty));
        OnPropertyChanged(nameof(HasTrashSelection));
        OnPropertyChanged(nameof(TrashSelectionCount));
        OnPropertyChanged(nameof(TrashSelectionText));
        OnPropertyChanged(nameof(TrashAllSelected));
        OnPropertyChanged(nameof(PurgeTrashConfirmDetail));
        OnPropertyChanged(nameof(TrashSpaceCaption));
        OnPropertyChanged(nameof(TrashSpaceHint));
    }
}
