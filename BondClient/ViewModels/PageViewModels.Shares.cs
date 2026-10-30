using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Threading;
using Bond.Core;
using Bond.Models;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Bond.ViewModels;

/// <summary>分享列表的状态筛选。</summary>
public enum ShareStatusFilter
{
    All,
    Active,
    Expired,
    Revoked
}

/// <summary>分享管理页顶部的状态 chip（选中态由 VM 驱动，不用手写四个按钮）。</summary>
public sealed partial class ShareFilterChip : ObservableObject
{
    public ShareFilterChip(string label, ShareStatusFilter value)
    {
        Label = label;
        Value = value;
    }

    public string Label { get; }

    public ShareStatusFilter Value { get; }

    /// <summary>XAML 里把 Value 当命令参数传，避免再引入转换器。</summary>
    public string ValueText => Value.ToString();

    [ObservableProperty] private bool _isActive;
}

/// <summary>
/// task-164：「我的分享」管理（列表列、状态筛选、文件名搜索、编辑对话框壳）。
/// <para>
/// 编辑保存依赖后端 <c>PATCH /api/shares/{id}</c>（task-163）。契约到位前
/// <see cref="IsShareEditAvailable"/> 恒为 false：保存按钮显式禁用并给出说明，
/// 绝不本地假写入，也不把 PATCH 静默降级成 DELETE+POST（会换 token、链接失效）。
/// </para>
/// </summary>
public partial class FilesPageViewModel
{
    private const int ShareSearchDebounceMs = 300;

    private DispatcherTimer? _shareSearchTimer;
    private ShareStatusFilter _shareFilter = ShareStatusFilter.All;
    private IReadOnlyList<ShareLink> _filteredShares = [];

    /// <summary>状态筛选 chips（顺序 = 全部 / 正常 / 已过期 / 已撤销）。</summary>
    public IReadOnlyList<ShareFilterChip> ShareFilters { get; } =
    [
        new("全部", ShareStatusFilter.All),
        new("正常", ShareStatusFilter.Active),
        new("已过期", ShareStatusFilter.Expired),
        new("已撤销", ShareStatusFilter.Revoked)
    ];

    /// <summary>当前筛选 + 搜索后的可见分享（整体替换，避免 ObservableCollection 的 Reset 崩溃路径，见 task-93）。</summary>
    public IReadOnlyList<ShareLink> FilteredShares
    {
        get => _filteredShares;
        private set
        {
            _filteredShares = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(ShowSharesFilterEmpty));
            OnPropertyChanged(nameof(ShowSharesList));
            OnPropertyChanged(nameof(SharesCountText));
        }
    }

    [ObservableProperty] private string _shareSearch = string.Empty;

    /// <summary>列表上方右侧的计数文案。</summary>
    public string SharesCountText => FilteredShares.Count == Shares.Count
        ? $"共 {Shares.Count} 条"
        : $"{FilteredShares.Count} / {Shares.Count} 条";

    /// <summary>列表非空但当前筛选/搜索没有结果。</summary>
    public bool ShowSharesFilterEmpty =>
        !IsSharesLoading && string.IsNullOrEmpty(SharesError) && Shares.Count > 0 && FilteredShares.Count == 0;

    /// <summary>独立「分享」页用：只有真正有可见行时才显示列表容器（加载/失败/空态各有自己的块）。</summary>
    public bool ShowSharesList =>
        !IsSharesLoading && string.IsNullOrEmpty(SharesError) && FilteredShares.Count > 0;

    // ── 编辑对话框壳（task-164，PATCH 未上线前只做校验与展示） ──

    [ObservableProperty] private bool _isEditShareOpen;
    [ObservableProperty] private ShareLink? _editShare;
    [ObservableProperty] private ShareExpiryOption? _editShareExpiry;
    [ObservableProperty] private string _editShareMaxDownloads = string.Empty;

    [ObservableProperty] private bool _editCodeKeep = true;
    [ObservableProperty] private bool _editCodeRemove;
    [ObservableProperty] private bool _editCodeReplace;
    [ObservableProperty] private string _editShareCode = string.Empty;

    [ObservableProperty] private string? _editShareError;
    [ObservableProperty] private bool _isSavingShareEdit;

    /// <summary>PATCH 契约（task-163）已到，保存按钮可用；打开状态由 CanSaveShareEdit 决定。</summary>
    public bool IsShareEditAvailable => true;

    public string ShareEditUnavailableHint => "保存会把改动提交到服务器";

    public bool ShowShareEditUnavailable => false;

    public bool CanSaveShareEdit =>
        IsShareEditAvailable
        && !IsSavingShareEdit
        && EditShare is { IsActive: true }
        && HasShareEditChanges
        && EditShareCodeError is null
        && EditShareMaxDownloadsError is null;

    public bool ShowEditCodeInput => EditCodeReplace;

    /// <summary>是否真的有改动（决定保存按钮可用性；避免无改动 PATCH 触发后端 400）。</summary>
    public bool HasShareEditChanges
    {
        get
        {
            if (EditShare is null) return false;
            if (!EditCodeKeep) return true;

            if (EditShareExpiry is not null && EditShareExpiry.Days != _editShareOriginalExpiryDays)
                return true;

            return !string.Equals(EditShareMaxDownloads.Trim(), OriginalMaxDownloadsText,
                StringComparison.Ordinal);
        }
    }

    /// <summary>次数上限与已下载次数冲突时的行内红字（不提交）。</summary>
    public string? EditShareMaxDownloadsError
    {
        get
        {
            var raw = EditShareMaxDownloads.Trim();
            if (raw.Length == 0) return null;
            if (!int.TryParse(raw, out var value) || value < 0)
                return "请填写不小于 0 的整数（留空 = 不限制）";

            var downloaded = EditShare?.DownloadCount ?? 0;
            return value > 0 && value <= downloaded
                ? $"下载上限必须大于已下载次数 {downloaded} 次"
                : null;
        }
    }

    public string? EditShareCodeError
    {
        get
        {
            if (!EditCodeReplace) return null;
            var code = EditShareCode.Trim();
            if (code.Length == 0) return null; // 空串 = 取消提取码（契约三态之一）
            return code.Length is < 4 or > 8 || !code.All(char.IsLetterOrDigit)
                ? "提取码需为 4–8 位字母或数字（留空表示取消提取码）"
                : null;
        }
    }

    /// <summary>打开编辑框时记录的原始有效期档位与次数上限文本，用于判断是否有改动。</summary>
    private int _editShareOriginalExpiryDays;
    private string _editShareOriginalMaxDownloadsText = string.Empty;

    private string OriginalMaxDownloadsText => EditShare is { MaxDownloads: > 0 } share
        ? share.MaxDownloads.ToString()
        : string.Empty;

    /// <summary>构造函数末尾调用：初始化搜索防抖与筛选 chips 的选中态。</summary>
    private void InitializeSharesManagement()
    {
        _shareSearchTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromMilliseconds(ShareSearchDebounceMs)
        };
        _shareSearchTimer.Tick += (_, _) =>
        {
            _shareSearchTimer!.Stop();
            RefreshFilteredShares();
        };

        SyncShareFilterChips();
        RefreshFilteredShares();
    }

    partial void OnShareSearchChanged(string value)
    {
        // 300ms 防抖：每次按键都重建列表会让长列表抖动。
        _shareSearchTimer?.Stop();
        _shareSearchTimer?.Start();
    }

    private void RaiseShareEditAvailability()
    {
        OnPropertyChanged(nameof(HasShareEditChanges));
        OnPropertyChanged(nameof(CanSaveShareEdit));
    }

    partial void OnEditShareMaxDownloadsChanged(string value)
    {
        OnPropertyChanged(nameof(EditShareMaxDownloadsError));
        RaiseShareEditAvailability();
    }

    partial void OnEditShareChanged(ShareLink? value)
    {
        OnPropertyChanged(nameof(EditShareMaxDownloadsError));
        OnPropertyChanged(nameof(EditShareCodeError));
        RaiseShareEditAvailability();
    }

    partial void OnEditShareExpiryChanged(ShareExpiryOption? value) => RaiseShareEditAvailability();

    partial void OnIsSavingShareEditChanged(bool value) => OnPropertyChanged(nameof(CanSaveShareEdit));

    partial void OnEditShareCodeChanged(string value)
    {
        OnPropertyChanged(nameof(EditShareCodeError));
        RaiseShareEditAvailability();
    }

    partial void OnEditCodeReplaceChanged(bool value)
    {
        // 三个提取码模式互斥 + 界面提示刷新（原代码有两份同名实现，CS0757；已合并为一份）。
        if (value) { EditCodeKeep = false; EditCodeRemove = false; }
        OnPropertyChanged(nameof(ShowEditCodeInput));
        OnPropertyChanged(nameof(EditShareCodeError));
        RaiseShareEditAvailability();
    }

    // 三个提取码模式互斥：交给 VM 保证，XAML 只绑 IsChecked。
    partial void OnEditCodeKeepChanged(bool value)
    {
        if (value) { EditCodeRemove = false; EditCodeReplace = false; }
        RaiseShareEditAvailability();
    }

    partial void OnEditCodeRemoveChanged(bool value)
    {
        if (value) { EditCodeKeep = false; EditCodeReplace = false; }
        RaiseShareEditAvailability();
    }

    [RelayCommand]
    private void SetShareFilter(string? value)
    {
        _shareFilter = Enum.TryParse<ShareStatusFilter>(value, ignoreCase: true, out var parsed)
            ? parsed
            : ShareStatusFilter.All;

        SyncShareFilterChips();
        RefreshFilteredShares();
    }

    [RelayCommand]
    private void OpenEditShare(ShareLink? share)
    {
        if (share is null || !share.IsActive) return;

        EditShare = share;
        EditShareCode = string.Empty;
        EditShareMaxDownloads = share.MaxDownloads > 0 ? share.MaxDownloads.ToString() : string.Empty;
        EditShareExpiry = ResolveExpiryOption(share.ExpiresAt);
        _editShareOriginalExpiryDays = EditShareExpiry?.Days ?? 0;
        _editShareOriginalMaxDownloadsText = EditShareMaxDownloads.Trim();
        EditCodeKeep = true;
        EditCodeRemove = false;
        EditCodeReplace = false;
        EditShareError = null;
        IsEditShareOpen = true;
    }

    [RelayCommand]
    private void CloseEditShare()
    {
        IsEditShareOpen = false;
        EditShare = null;
        EditShareError = null;
    }

    /// <summary>
    /// 保存编辑（task-163 PATCH）。三态映射：单选「保持现状」= 不传该字段；
    /// 「取消提取码」= 显式 null；「设置新码」= 传值。有效期按档位换算成绝对毫秒（永久=null）。
    /// </summary>
    [RelayCommand]
    private async Task SaveShareEditAsync()
    {
        if (EditShare is not { IsActive: true } share) return;

        var error = EditShareCodeError ?? EditShareMaxDownloadsError;
        if (!string.IsNullOrEmpty(error))
        {
            EditShareError = error;
            return;
        }

        if (!HasShareEditChanges)
        {
            EditShareError = "没有需要保存的改动";
            return;
        }

        // 提取码三态：Keep=不传；Remove=显式 null；Replace=新码（留空等同取消）。
        var changeCode = !EditCodeKeep;
        var newCode = EditCodeReplace ? EditShareCode.Trim() : null;

        // 有效期：与原档位一致则不传；永久档发送 null；其余发送 now+days 的绝对毫秒。
        var changeExpiry = EditShareExpiry is not null && EditShareExpiry.Days != _editShareOriginalExpiryDays;
        long? expiresAtMillis = null;
        if (changeExpiry && EditShareExpiry!.Days is int expiryDays && expiryDays > 0)
            expiresAtMillis = DateTimeOffset.UtcNow.AddDays(expiryDays).ToUnixTimeMilliseconds();

        // 次数上限：空=null（不限），0 不特殊处理；与原值相同则不传。
        var maxRaw = EditShareMaxDownloads.Trim();
        int? maxDownloads = null;
        if (maxRaw.Length > 0 && int.TryParse(maxRaw, out var parsedMax) && parsedMax > 0)
            maxDownloads = parsedMax;
        var changeMax = !string.Equals(maxRaw, _editShareOriginalMaxDownloadsText, StringComparison.Ordinal);

        IsSavingShareEdit = true;
        EditShareError = null;
        try
        {
            var (updated, updateError) = await _files
                .UpdateShareAsync(share.Id, changeCode, newCode, changeExpiry, expiresAtMillis,
                    changeMax, maxDownloads)
                .ConfigureAwait(true);

            if (updated is null)
            {
                EditShareError = updateError ?? "保存失败，请稍后重试";
                return;
            }

            ApplyShareUpdate(updated);
            IsEditShareOpen = false;
            EditShare = null;
        }
        catch (Exception ex)
        {
            EditShareError = ex.Message;
        }
        finally
        {
            IsSavingShareEdit = false;
        }
    }

    /// <summary>保存成功后只替换该行数据（不整表重拉）。</summary>
    private void ApplyShareUpdate(ShareLink updated)
    {
        for (var i = 0; i < Shares.Count; i++)
        {
            if (!string.Equals(Shares[i].Id, updated.Id, StringComparison.Ordinal)) continue;
            Shares[i] = updated;
            break;
        }

        RefreshFilteredShares();
    }

    private ShareExpiryOption? ResolveExpiryOption(DateTimeOffset? expiresAt)
    {
        var permanent = ShareExpiryOptions.FirstOrDefault(o => o.Days == 0);
        if (expiresAt is null) return permanent; // null = 永久

        var days = (int)Math.Ceiling((expiresAt.Value - DateTimeOffset.UtcNow).TotalDays);
        return ShareExpiryOptions.FirstOrDefault(o => o.Days > 0 && days <= o.Days) ?? permanent;
    }

    // ── task-164：创建对话框与编辑对话框共用同一套提取码校验 ──

    /// <summary>创建分享时的提取码校验（与编辑对话框同一条规则：4–8 位字母或数字；勾选后必填，服务端也拒绝空码）。</summary>
    public string? CreateShareCodeError
    {
        get
        {
            if (!ShareRequiresCode) return null;
            var code = ShareCode.Trim();
            if (code.Length == 0) return "已勾选「需要提取码」，请填写 4–8 位字母或数字";
            return code.Length is < 4 or > 8 || !code.All(char.IsLetterOrDigit)
                ? "提取码需为 4–8 位字母或数字"
                : null;
        }
    }

    /// <summary>创建按钮可用性：校验通过且当前没有正在创建。</summary>
    public bool CanSubmitCreateShare => !IsCreatingShare && CreateShareCodeError is null;

    partial void OnShareCodeChanged(string value)
    {
        OnPropertyChanged(nameof(CreateShareCodeError));
        OnPropertyChanged(nameof(CanSubmitCreateShare));
    }

    partial void OnShareRequiresCodeChanged(bool value)
    {
        OnPropertyChanged(nameof(CreateShareCodeError));
        OnPropertyChanged(nameof(CanSubmitCreateShare));
    }

    partial void OnIsCreatingShareChanged(bool value) => OnPropertyChanged(nameof(CanSubmitCreateShare));

    /// <summary>创建成功卡片上的「继续管理」：关掉结果卡、打开分享面板并把刚创建的文件名填进搜索。</summary>
    [RelayCommand]
    private void ManageCreatedShare()
    {
        var created = CreatedShare;
        CreatedShare = null;
        IsCreateShareOpen = false;

        _ = OpenSharesAsync();
        ShareSearch = created?.FileName ?? string.Empty;
        SetShareFilter(ShareStatusFilter.All.ToString());
    }

    private void SyncShareFilterChips()
    {
        foreach (var chip in ShareFilters)
            chip.IsActive = chip.Value == _shareFilter;
    }

    /// <summary>重建可见列表（筛选 + 搜索），整体替换以免 ObservableCollection 的 Reset 路径。</summary>
    private void RefreshFilteredShares()
    {
        var query = Shares.AsEnumerable();

        query = _shareFilter switch
        {
            ShareStatusFilter.Active => query.Where(s => s.IsActive),
            ShareStatusFilter.Expired => query.Where(s => s.IsExpired && !s.IsRevoked),
            ShareStatusFilter.Revoked => query.Where(s => s.IsRevoked),
            _ => query
        };

        var keyword = ShareSearch.Trim();
        if (keyword.Length > 0)
        {
            query = query.Where(s => s.FileName.Contains(keyword, StringComparison.OrdinalIgnoreCase));
        }

        FilteredShares = query.ToList();
    }
}
