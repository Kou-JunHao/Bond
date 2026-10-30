using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Input.Platform;
using Bond.Models;
using Bond.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.Extensions.DependencyInjection;

namespace Bond.ViewModels;

/// <summary>
/// 设置页「挂载网络硬盘」分区（WebDAV）。放在这里而不是侧栏：侧栏保持 7 项。
/// 明文密码只保存在 <see cref="DavNewPlaintext"/> 内存里，关闭面板/刷新即清空，不落盘、不写日志。
/// </summary>
public partial class SettingsPageViewModel
{
    private IWebDavService DavService => App.Services.GetRequiredService<IWebDavService>();

    /// <summary>挂载地址（个人空间 + 每个工作区）。</summary>
    [ObservableProperty]
    private IReadOnlyList<WebDavAddressItem> _davAddressRows = [];

    /// <summary>未吊销的专用密码。</summary>
    [ObservableProperty]
    private IReadOnlyList<WebDavPasswordItem> _davPasswords = [];

    [ObservableProperty]
    private bool _isDavLoading;

    [ObservableProperty]
    private string? _davError;

    /// <summary>null=探测中；true=服务启用；false=管理员未开启。</summary>
    [ObservableProperty]
    private bool? _isDavEnabled;

    [ObservableProperty]
    private string _davNewName = "我的设备";

    /// <summary>刚生成的明文密码（只显示一次）；null 表示没有待展示的密码。</summary>
    [ObservableProperty]
    private string? _davNewPlaintext;

    [ObservableProperty]
    private bool _isDavCreateOpen;

    /// <summary>待确认吊销的专用密码（二次确认：吊销会让已挂载的盘掉线）。</summary>
    [ObservableProperty]
    private WebDavPasswordItem? _davRevokePending;

    [ObservableProperty]
    private bool _isDavRevokeConfirmOpen;

    public string DavRevokeConfirmText => DavRevokePending is null
        ? "确认吊销这个专用密码？"
        : $"确认吊销「{DavRevokePending.Name}」？吊销后已挂载的盘最长 60 秒内掉线，需要重新生成密码再挂载。";

    public bool HasDavPasswords => DavPasswords.Count > 0;

    public bool HasNoDavPasswords => !HasDavPasswords && !IsDavLoading;

    public bool HasDavError => !string.IsNullOrWhiteSpace(DavError);

    public bool IsDavDisabled => IsDavEnabled == false;

    public bool HasDavPlaintext => !string.IsNullOrEmpty(DavNewPlaintext);

    public string DavWindowsGuideText => DavWindowsGuide();

    public string DavMacGuideText => DavMacGuide();

    public string DavRcloneGuideText => DavRcloneGuide();

    public string DavHost
    {
        get
        {
            try { return new Uri(_api.BaseUrl).GetLeftPart(UriPartial.Authority); }
            catch { return _api.BaseUrl; }
        }
    }

    partial void OnIsDavEnabledChanged(bool? value)
        => OnPropertyChanged(nameof(IsDavDisabled));

    partial void OnDavRevokePendingChanged(WebDavPasswordItem? value)
        => OnPropertyChanged(nameof(DavRevokeConfirmText));

    partial void OnDavNewPlaintextChanged(string? value)
        => OnPropertyChanged(nameof(HasDavPlaintext));

    partial void OnDavErrorChanged(string? value)
        => OnPropertyChanged(nameof(HasDavError));

    partial void OnIsDavLoadingChanged(bool value)
        => OnPropertyChanged(nameof(HasNoDavPasswords));

    /// <summary>
    /// 打开设置页时调用：拉地址、探测启用状态、拉密码列表。
    /// 关键：先把数据全部取到本地，再同步重建集合——页面可能被重复触发（attach + 手动刷新），
    /// 边拉边 Add 会出现重复行（这是修过的真实 bug）。
    /// </summary>
    [RelayCommand]
    public async Task LoadDavSectionAsync()
    {
        if (IsDavLoading) return;
        IsDavLoading = true;
        DavError = null;
        try
        {
            var host = DavHost;
            var addresses = new List<WebDavAddressItem>
            {
                new()
                {
                    Title = "个人空间",
                    Url = host + "/dav/",
                    IsPersonal = true,
                    CopyCommand = CopyDavAddressCommand
                }
            };

            try
            {
                var workspaces = App.Services.GetRequiredService<IWorkspaceService>();
                await workspaces.RefreshAsync().ConfigureAwait(true);
                foreach (var w in workspaces.Workspaces)
                {
                    addresses.Add(new WebDavAddressItem
                    {
                        Title = w.Name,
                        Url = $"{host}/dav/workspaces/{w.Id}/",
                        CopyCommand = CopyDavAddressCommand
                    });
                }
            }
            catch
            {
                // 工作区拉不到不影响个人空间挂载地址
            }

            var enabled = await DavService.ProbeWebDavAsync().ConfigureAwait(true);
            var (items, error) = await DavService.GetWebDavPasswordsAsync().ConfigureAwait(true);

            var passwords = new List<WebDavPasswordItem>();
            if (items is not null)
            {
                foreach (var item in items)
                {
                    item.RevokeCommand = RevokeDavPasswordCommand;
                    passwords.Add(item);
                }
            }

            DavAddressRows = addresses;
            DavPasswords = passwords;

            IsDavEnabled = enabled;
            DavError = error;
            OnPropertyChanged(nameof(HasDavPasswords));
            OnPropertyChanged(nameof(HasNoDavPasswords));
        }
        finally
        {
            IsDavLoading = false;
        }
    }

    [RelayCommand]
    private void OpenDavCreate()
    {
        IsDavCreateOpen = true;
        DavNewName = "我的设备";
    }

    [RelayCommand]
    private void CancelDavCreate()
    {
        IsDavCreateOpen = false;
    }

    /// <summary>生成专用密码：明文只在这里出现一次，随后只能复制。</summary>
    [RelayCommand]
    private async Task CreateDavPasswordAsync()
    {
        var name = (DavNewName ?? string.Empty).Trim();
        if (string.IsNullOrEmpty(name))
        {
            _notify.Notify("请先填写密码名称", NoticeKind.Warning);
            return;
        }

        var (password, item, error) = await DavService.CreateWebDavPasswordAsync(name).ConfigureAwait(true);
        if (password is null || item is null)
        {
            _notify.Notify(error ?? "生成专用密码失败", NoticeKind.Error);
            return;
        }

        DavNewPlaintext = password;
        IsDavCreateOpen = false;
        item.RevokeCommand = RevokeDavPasswordCommand;
        var next = new List<WebDavPasswordItem> { item };
        next.AddRange(DavPasswords);
        DavPasswords = next;
        OnPropertyChanged(nameof(HasDavPasswords));
        OnPropertyChanged(nameof(HasNoDavPasswords));
    }

    [RelayCommand]
    private void CloseDavPlaintext()
    {
        // 关闭即丢弃明文，界面不可能再显示
        DavNewPlaintext = null;
    }

    /// <summary>点「吊销」先弹二次确认，避免误操作让正在用的盘掉线。</summary>
    [RelayCommand]
    private void RevokeDavPassword(WebDavPasswordItem? item)
    {
        if (item is null) return;
        DavRevokePending = item;
        IsDavRevokeConfirmOpen = true;
    }

    [RelayCommand]
    private void CancelDavRevoke()
    {
        IsDavRevokeConfirmOpen = false;
        DavRevokePending = null;
    }

    [RelayCommand]
    private async Task ConfirmDavRevokeAsync()
    {
        var item = DavRevokePending;
        IsDavRevokeConfirmOpen = false;
        DavRevokePending = null;
        if (item is null) return;

        var error = await DavService.RevokeWebDavPasswordAsync(item.Id).ConfigureAwait(true);
        if (error is not null)
        {
            _notify.Notify(error, NoticeKind.Error);
            return;
        }

        // 已吊销的行保留并置灰（后端列表也返回历史吊销记录），按钮变禁用，避免误点。
        item.IsRevoked = true;
        OnPropertyChanged(nameof(HasDavPasswords));
        OnPropertyChanged(nameof(HasNoDavPasswords));
        _notify.Notify($"已吊销「{item.Name}」，挂载会在 60 秒内掉线", NoticeKind.Success);
    }

    [RelayCommand]
    private async Task CopyDavAddressAsync(WebDavAddressItem? row)
    {
        if (row is null) return;
        await SetClipboardAsync(row.Url).ConfigureAwait(true);
        _notify.Notify("挂载地址已复制", NoticeKind.Success);
    }

    [RelayCommand]
    private async Task CopyDavPasswordAsync()
    {
        if (DavNewPlaintext is null) return;
        await SetClipboardAsync(DavNewPlaintext).ConfigureAwait(true);
        _notify.Notify("专用密码已复制", NoticeKind.Success);
    }

    /// <summary>复制「地址 + 用户名 + 密码」，方便一次粘进凭据管理器。</summary>
    [RelayCommand]
    private async Task CopyDavAllAsync()
    {
        if (DavNewPlaintext is null) return;
        var text = $"地址：{DavHost}/dav/{Environment.NewLine}"
                 + $"用户名：{_session.AccountName}{Environment.NewLine}"
                 + $"密码：{DavNewPlaintext}";
        await SetClipboardAsync(text).ConfigureAwait(true);
        _notify.Notify("地址、用户名、密码已复制（关闭面板后密码不可再查看）", NoticeKind.Success);
    }

    [RelayCommand]
    private Task CopyDavWindowsAsync() => CopySnippetAsync(DavWindowsGuide());

    [RelayCommand]
    private Task CopyDavMacAsync() => CopySnippetAsync(DavMacGuide());

    [RelayCommand]
    private Task CopyDavRcloneAsync() => CopySnippetAsync(DavRcloneGuide());

    private async Task CopySnippetAsync(string text)
    {
        await SetClipboardAsync(text).ConfigureAwait(true);
        _notify.Notify("命令已复制", NoticeKind.Success);
    }

    private static async Task SetClipboardAsync(string text)
    {
        var clipboard = App.ShellTopLevel?.Clipboard;
        if (clipboard is null) return;
        await clipboard.SetTextAsync(text).ConfigureAwait(true);
    }

    /// <summary>Windows 映射步骤（文案来源：BondServer/docs/webdav-design.md §11 + scripts/mount-webdav.ps1）。</summary>
    public string DavWindowsGuide() => $"""
        # 1) 启动 WebClient 服务（管理员 PowerShell，只需一次）
        Set-Service WebClient -StartupType Automatic; Start-Service WebClient

        # 2) 映射网络驱动器（Z: 可换；@SSL 是 HTTPS 的固定写法）
        net use Z: \\{new Uri(DavHost).Host}\@SSL\dav /user:{_session.AccountName} /persistent:no

        # 3) 需要传 >47.6 MB 的文件时，改注册表放宽上限（管理员）
        reg add HKLM\SYSTEM\CurrentControlSet\Services\WebClient\Parameters /v FileSizeLimitInBytes /t REG_DWORD /d 4294967295 /f
        net stop webclient; net start webclient
        """;

    public string DavMacGuide() => $"""
        # macOS Finder
        1. 按 Cmd+K，输入：{DavHost}/dav/
        2. 选择「注册用户」，用户名填 {_session.AccountName}
        3. 密码填上面生成的专用密码
        """;

    public string DavRcloneGuide() => $"""
        # Linux / rclone 示例（大文件首选）
        [bond]
        type = webdav
        url = {DavHost}/dav/
        vendor = other
        user = {_session.AccountName}
        pass = <专用密码>
        """;
}
