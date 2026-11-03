using System;
using System.Threading.Tasks;
using Bond.Models;
using Bond.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Bond.ViewModels;

/// <summary>
/// 收件箱（好友申请 + 工作区邀请）：主窗口右上角「收件箱」按钮 + 非阻塞面板 + 同意/忽略。
///
/// <para>红点口径 = <see cref="IInboxService.PendingCount"/>，只统计**能在面板里处理掉**的事项。
/// 以前这个计数挂在悬浮球「收件箱」格子上，但点击只会跳到传输页，好友申请在界面上根本没有入口，
/// 于是出现「有红点、点进去什么都没有、重启前永远消不掉」。现在：</para>
/// <list type="number">
///   <item>按钮/面板能看到到底是哪一条（好友申请 / 工作区邀请）；</item>
///   <item>面板里直接同意或忽略，动作成功后再拉一次收件箱，红点立即归零；</item>
///   <item>面板打开、主窗口激活、工作区页处理邀请后都会刷新，别的地方处理掉的红点会自动消失。</item>
/// </list>
/// </summary>
public partial class MainWindowViewModel
{
    private bool _inboxRefreshStarted;
    private bool _inboxRefreshing;
    private bool _inboxBusy;
    private DateTime _inboxAutoRefreshAt = DateTime.MinValue;

    /// <summary>面板是否展开（点遮罩 / Esc / 再点按钮关闭）。</summary>
    [ObservableProperty]
    private bool _isInboxOpen;

    [ObservableProperty]
    private bool _isInboxLoading;

    /// <summary>最近一次刷新/操作失败的原因；成功时清空。</summary>
    [ObservableProperty]
    private string? _inboxError;

    partial void OnIsInboxOpenChanged(bool value)
    {
        if (!value) return;

        // 两个面板都在右上角，同时开会互相压住
        IsAnnouncementsOpen = false;
        // 展开时拉一次：别的端/别的页面可能已经把这个事项处理掉了
        _ = RefreshInboxAsync(showLoading: false);
    }

    partial void OnIsInboxLoadingChanged(bool value) => RaiseInboxState();

    partial void OnInboxErrorChanged(string? value)
    {
        OnPropertyChanged(nameof(HasInboxError));
        RaiseInboxState();
    }

    public bool HasInboxError => !string.IsNullOrWhiteSpace(InboxError);

    /// <summary>红点数字（= 待处理条数）。</summary>
    public int InboxPendingCount => Inbox.PendingCount;

    public bool HasInboxPending => InboxPendingCount > 0;

    public string InboxPendingText => InboxPendingCount > 9 ? "9+" : InboxPendingCount.ToString();

    public bool HasInboxItems => Inbox.Items.Count > 0;

    /// <summary>面板空着时的加载态（已有数据时的刷新只在头部显示进度，不盖住列表）。</summary>
    public bool ShowInboxLoading => IsInboxLoading && !HasInboxItems;

    public bool IsInboxEmpty => !IsInboxLoading && !HasInboxError && !HasInboxItems;

    public string InboxSummaryText
    {
        get
        {
            if (IsInboxLoading) return "正在刷新…";
            if (HasInboxError) return "刷新失败";
            return HasInboxItems ? $"{InboxPendingCount} 项待处理" : "没有待处理事项";
        }
    }

    /// <summary>主窗口构造时调用一次（幂等）：订阅收件箱变化并先算一次徽标。</summary>
    public void StartInboxRefresh()
    {
        if (_inboxRefreshStarted) return;
        _inboxRefreshStarted = true;

        Inbox.Changed += (_, _) => RaiseInboxState();
        RaiseInboxState();
    }

    /// <summary>悬浮球「收件箱」入口：打开面板并同步一次（面板自身也会在展开时刷新）。</summary>
    public async Task OpenInboxPanelAsync()
    {
        IsInboxOpen = true;
        await RefreshInboxAsync(showLoading: false).ConfigureAwait(true);
    }

    /// <summary>
    /// 主窗口重新激活时的静默刷新（20s 内不重复发请求，避免每次 alt-tab 都打两次接口）。
    /// 这条路径是「别处处理完了、本机红点还挂着」的自愈手段。
    /// </summary>
    public void OnShellActivatedInbox()
    {
        if (!_inboxRefreshStarted) return;
        if (DateTime.UtcNow - _inboxAutoRefreshAt < TimeSpan.FromSeconds(20)) return;

        _inboxAutoRefreshAt = DateTime.UtcNow;
        _ = Inbox.RefreshAsync();
    }

    [RelayCommand]
    private void ToggleInbox()
    {
        if (IsInboxOpen)
        {
            CloseInbox();
            return;
        }

        IsInboxOpen = true;
    }

    [RelayCommand]
    private void CloseInbox() => IsInboxOpen = false;

    [RelayCommand]
    private Task RefreshInbox() => RefreshInboxAsync(showLoading: true);

    [RelayCommand]
    private async Task AcceptInboxItemAsync(InboxItem? item)
        => await ResolveInboxItemAsync(item, accept: true).ConfigureAwait(true);

    [RelayCommand]
    private async Task RejectInboxItemAsync(InboxItem? item)
        => await ResolveInboxItemAsync(item, accept: false).ConfigureAwait(true);

    /// <summary>刷新收件箱；<paramref name="showLoading"/> 为 true 时显示加载态。</summary>
    public async Task RefreshInboxAsync(bool showLoading)
    {
        if (_inboxRefreshing) return;

        _inboxRefreshing = true;
        if (showLoading) IsInboxLoading = true;
        try
        {
            await Inbox.RefreshAsync().ConfigureAwait(true);
            InboxError = null;
        }
        catch (Exception ex)
        {
            InboxError = "刷新收件箱失败：" + ex.Message;
        }
        finally
        {
            _inboxRefreshing = false;
            IsInboxLoading = false;
            RaiseInboxState();
        }
    }

    private async Task ResolveInboxItemAsync(InboxItem? item, bool accept)
    {
        if (item is null || _inboxBusy) return;

        _inboxBusy = true;
        InboxError = null;
        try
        {
            var error = accept
                ? await Inbox.AcceptAsync(item).ConfigureAwait(true)
                : await Inbox.RejectAsync(item).ConfigureAwait(true);

            if (error is not null)
            {
                // 失败可能只是「这条已经被别处处理过了」：先与后端对齐一次，
                // 再把失败原因留给用户看，避免面板一直挂着一条服务端早已没有的事项。
                await RefreshInboxAsync(showLoading: false).ConfigureAwait(true);
                InboxError = error;
                return;
            }

            // 接受工作区邀请后工作区页要立刻看到新身份（成员角色、配额、文件都依赖它）。
            if (accept && item.Kind == InboxKind.WorkspaceInvite)
            {
                try
                {
                    await Workspaces.LoadAsync().ConfigureAwait(true);
                }
                catch (Exception ex)
                {
                    // 工作区页自己会显示错误；这里不覆盖收件箱的成功结果
                    Console.Error.WriteLine("[Bond] 接受邀请后刷新工作区失败：" + ex.Message);
                }
            }
        }
        catch (Exception ex)
        {
            InboxError = "操作失败：" + ex.Message;
        }
        finally
        {
            _inboxBusy = false;
            RaiseInboxState();
        }
    }

    /// <summary>计数 / 空态 / 摘要统一通知（列表变化、加载态、错误态都会走到这里）。</summary>
    private void RaiseInboxState()
    {
        OnPropertyChanged(nameof(InboxPendingCount));
        OnPropertyChanged(nameof(HasInboxPending));
        OnPropertyChanged(nameof(InboxPendingText));
        OnPropertyChanged(nameof(HasInboxItems));
        OnPropertyChanged(nameof(ShowInboxLoading));
        OnPropertyChanged(nameof(IsInboxEmpty));
        OnPropertyChanged(nameof(InboxSummaryText));

        // 主构造函数里的旧成员（悬浮球/侧栏用）口径与收件箱一致
        OnPropertyChanged(nameof(PendingCount));
        OnPropertyChanged(nameof(HasPending));
        OnPropertyChanged(nameof(PendingText));
    }
}
