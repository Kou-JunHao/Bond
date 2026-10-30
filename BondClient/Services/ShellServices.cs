using System;
using System.Diagnostics;
using Bond.Services;

namespace Bond.Services;

/// <summary>由悬浮球窗口实现。</summary>
public interface IBallHost
{
    void RequestExpand();

    void RequestCollapse();
}

/// <summary>由主窗口实现。</summary>
public interface IMainHost
{
    void RequestShow();

    void RequestHide();

    void RequestActivate();

    bool IsVisible { get; }
}

/// <summary>
/// 窗口协调器：把"谁该显示、谁该收起"集中到一处。
/// <para>
/// 旧版把这段逻辑散落在 <c>FloatingBallWindow</c> 的 2048 行代码里（直接 new 窗口、直接改可见性），
/// 现在两个窗口互不知道对方的存在，只通过本接口通信。
/// </para>
/// </summary>
public sealed class WindowCoordinator : IWindowCoordinator
{
    private IBallHost? _ball;
    private IMainHost? _main;

    public bool IsMainVisible => _main?.IsVisible ?? false;

    public event EventHandler? MainVisibilityChanged;

    public void Attach(IBallHost ball) => _ball = ball;

    public void Attach(IMainHost main) => _main = main;

    public void ShowMain()
    {
        _main?.RequestShow();
        _main?.RequestActivate();
        MainVisibilityChanged?.Invoke(this, EventArgs.Empty);
    }

    public void HideMain()
    {
        _main?.RequestHide();
        MainVisibilityChanged?.Invoke(this, EventArgs.Empty);
    }

    public void ToggleMain()
    {
        if (IsMainVisible) HideMain();
        else ShowMain();
    }

    public void ExpandBall() => _ball?.RequestExpand();

    public void CollapseBall() => _ball?.RequestCollapse();

    public void MinimizeToBall()
    {
        HideMain();
        _ball?.RequestCollapse();
    }

    public void Quit()
    {
        // 依次关闭全部窗口后结束进程，避免托盘图标残留在通知区。
        _main?.RequestHide();
        if (Avalonia.Application.Current?.ApplicationLifetime
            is Avalonia.Controls.ApplicationLifetimes.IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.Shutdown();
        }
        else
        {
            Environment.Exit(0);
        }
    }
}

/// <summary>跨窗口通知中心。</summary>
public sealed class NotificationCenter : INotificationCenter
{
    public event EventHandler<NoticeEventArgs>? Notified;

    public void Notify(string message, NoticeKind kind = NoticeKind.Info)
        => Notified?.Invoke(this, new NoticeEventArgs(message, kind));
}
