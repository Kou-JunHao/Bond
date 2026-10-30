using System;
using System.ComponentModel;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Threading;
using Bond.Core.Morph;
using Material.Icons;
using SukiUI.Controls;
using Bond.Services;
using Bond.ViewModels;

namespace Bond.Views;

/// <summary>
/// 主窗口：SukiUI 标准外壳（SukiWindow + SukiSideMenu），页面内容由 XAML 里的
/// SukiSideMenuItem.PageContent 直接承载。
/// <para>额外负责：全局 Toast、侧边栏折叠状态持久化、侧栏「传输」活动徽标的弹性动效。</para>
/// <para>徽标由侧栏项自己的 Header 布局定位（折叠时 Header 隐藏，只保留图标上的小圆点），
/// 不再用浮层手算坐标，避免收起时跑到左上角。</para>
/// </summary>
public partial class MainWindow : SukiWindow, IMainHost
{
    private INotificationCenter? _notify;
    private DispatcherTimer? _toastTimer;

    // 侧栏传输徽标：只在数量变化时动，禁止循环脉冲
    private enum BadgeAnim { None, Appear, Bounce, Disappear }

    private static readonly System.Diagnostics.Stopwatch BadgeClock =
        System.Diagnostics.Stopwatch.StartNew();

    private DispatcherTimer? _badgeTimer;
    private BadgeAnim _badgeAnim;
    private double _badgeStartMs;
    private int _lastActiveCount = -1;
    private bool _badgeActive;
    private DispatcherTimer? _layoutDebounce;

    // 徽标定位缓存：只在坐标/可见性真的变化时才写，避免「写坐标→重排→再写」的死循环
    private double _badgeCachedLeft = double.NaN;
    private double _badgeCachedTop = double.NaN;
    private bool _badgeCachedVisible;
    private bool _dotCachedVisible = true;
    private double _badgeTextWidth = 12;

    // task-142：上次处理过的 SideMenu 尺寸。hover 可能让 Bounds 的位置量变化（模板内 hover 位移），
    // 但只有尺寸变化才需要重新摆徽标 → 按尺寸去重，避免悬停期间反复安排定位、看起来像侧栏在闪。
    private double _lastSideMenuWidth = double.NaN;
    private double _lastSideMenuHeight = double.NaN;

    // 预览：图片缩放 / 平移
    private ScaleTransform? _previewScale;
    private TranslateTransform? _previewTrans;
    private bool _previewDragging;
    private Avalonia.Point _previewDragStart;
    private double _previewDragOffsetX;
    private double _previewDragOffsetY;

    // 预览全屏：进入前的窗口状态
    private WindowState _stateBeforeFullscreen = WindowState.Normal;

    private const double BadgeAppearMs = 220;
    private const double BadgeFadeMs = 180;

    public MainWindow()
    {
        InitializeComponent();
        HookShell();
        InitSidebarState();
    }

    public MainWindow(MainWindowViewModel vm)
    {
        InitializeComponent();
        DataContext = vm;
        HookShell();
        InitSidebarState();

        vm.ShowFilesRequested += (_, _) => SelectNav(0);
        vm.ShowTransfersRequested += (_, _) => SelectNav(2);
        vm.ShowChatsRequested += (_, _) => SelectNav(4);
        vm.PropertyChanged += OnViewModelPropertyChanged;
        vm.Preview.PropertyChanged += OnPreviewPropertyChanged;
        vm.Preview.VideoFrameUpdated += OnVideoFrameUpdated;

        // task-7：公告未读数——启动加载一次，窗口重新激活时静默刷新；
        // 面板打开时另拉列表（MainWindowViewModel.Announcements.cs）。
        vm.StartAnnouncementRefresh();
        Activated += (_, _) => vm.OnShellActivated();

        // 文件页快捷键（Ctrl+A 全选 / Esc 取消选择）：挂在窗口级 Tunnel，
        // 这样焦点在 Tab/按钮上时也能用；输入框内不拦截（保留文本框自己的 Ctrl+A / Esc）。
        AddHandler(KeyDownEvent, OnShellKeyDown, RoutingStrategies.Tunnel);

        // task-133：快捷键浮层关闭后把焦点还给文件列表，避免焦点停在已隐藏的控件上
        ShortcutsLayer.Closed += (_, _) => FilesPageView?.Focus();

        // task-153：界面缩放（默认跟随系统=100%）。悬浮球窗口不缩放。
        App.UiScaleChanged += OnUiScaleChanged;
        ApplyZoom(App.ResolveUiScale(Bond.ViewModels.LocalPrefs.Current.UiScale));

        _lastActiveCount = vm.ActiveTransferCount;
        _badgeActive = vm.HasActiveTransfer;
        SyncBadgeText(vm);
        TransferDot.IsVisible = _badgeActive && !SideMenu.IsMenuExpanded;
        TransferBadge.Opacity = 0;
        TransferBadge.IsVisible = false;
    }

    /// <summary>task-153：界面缩放变化（任意线程触发，切回 UI 线程应用）。</summary>
    private void OnUiScaleChanged(object? sender, double scale)
    {
        if (Dispatcher.UIThread.CheckAccess()) ApplyZoom(scale);
        else Dispatcher.UIThread.Post(() => ApplyZoom(scale));
    }

    /// <summary>task-153：LayoutTransformControl 按倍率重新布局（不会裁切），只作用于主窗口内容。</summary>
    private void ApplyZoom(double scale)
    {
        if (double.IsNaN(scale) || scale <= 0) scale = 1.0;
        ZoomHost.LayoutTransform = new ScaleTransform(scale, scale);
    }

    private void HookShell()
    {
        // 2026-10-29 用户反馈「app 不要总是置于顶部」：主窗口不应该是置顶窗口
        // （产品里只有悬浮球需要置顶）。若被外部程序/自动化工具用 SetWindowPos(HWND_TOPMOST)
        // 钉在最前，用户会看到应用永远盖在其它窗口上——显示与每次激活时各纠正一次。
        Opened += (_, _) => EnsureMainWindowNotTopmost();
        Activated += (_, _) => EnsureMainWindowNotTopmost();

        if (App.Services?.GetService(typeof(INotificationCenter)) is not INotificationCenter notify) return;

        _notify = notify;
        notify.Notified += OnNotified;

        InitPreviewInteractions();
    }
    // ── 主窗口置顶防御（Win32） ────────────────────────────────
    private const uint SwpNoSize = 0x0001;
    private const uint SwpNoMove = 0x0002;
    private const uint SwpNoActivate = 0x0010;
    private static readonly IntPtr HwndNotTopmost = new(-2);

    [System.Runtime.InteropServices.DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter,
        int x, int y, int cx, int cy, uint uFlags);

    private void EnsureMainWindowNotTopmost()
    {
        if (!OperatingSystem.IsWindows()) return;

        var handle = TryGetPlatformHandle()?.Handle ?? IntPtr.Zero;
        if (handle == IntPtr.Zero) return;

        _ = SetWindowPos(handle, HwndNotTopmost, 0, 0, 0, 0,
            SwpNoMove | SwpNoSize | SwpNoActivate);
    }

    private void InitPreviewInteractions()
    {
        _previewScale = new ScaleTransform(1, 1);
        _previewTrans = new TranslateTransform(0, 0);
        PreviewImage.RenderTransform = new TransformGroup
        {
            Children = { _previewScale, _previewTrans }
        };
    }

    // ── 预览覆盖层：滚轮缩放 / 拖动平移 / 双击复位 / Esc 关闭 / ←→ 切换 ──

    private PreviewViewModel? Preview => (DataContext as MainWindowViewModel)?.Preview;

    private void OnPreviewPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PreviewViewModel.IsOpen)
            && Preview is not { IsOpen: true }
            && WindowState == WindowState.FullScreen)
        {
            WindowState = _stateBeforeFullscreen;
        }

        if (e.PropertyName is not (nameof(PreviewViewModel.Zoom)
            or nameof(PreviewViewModel.OffsetX)
            or nameof(PreviewViewModel.OffsetY)))
            return;

        ApplyPreviewTransform();
    }

    // ── 视频：帧刷新到 UI 线程 + 全屏 ─────────────────────────

    private void OnVideoFrameUpdated()
        => Dispatcher.UIThread.Post(() => PreviewVideoImage?.InvalidateVisual());

    private void OnVideoFullscreen(object? sender, RoutedEventArgs e) => TogglePreviewFullscreen();

    private void TogglePreviewFullscreen()
    {
        if (WindowState == WindowState.FullScreen)
        {
            WindowState = _stateBeforeFullscreen;
            return;
        }

        _stateBeforeFullscreen = WindowState;
        WindowState = WindowState.FullScreen;
    }

    private void ApplyPreviewTransform()
    {
        var preview = Preview;
        if (preview is null || _previewScale is null || _previewTrans is null) return;

        _previewScale.ScaleX = preview.Zoom;
        _previewScale.ScaleY = preview.Zoom;
        _previewTrans.X = preview.OffsetX;
        _previewTrans.Y = preview.OffsetY;
    }

    private void OnPreviewWheel(object? sender, PointerWheelEventArgs e)
    {
        if (Preview is not { IsOpen: true } preview) return;

        var factor = e.Delta.Y > 0 ? 1.15 : 1 / 1.15;
        preview.Zoom = Math.Clamp(preview.Zoom * factor, 1, 8);
        if (preview.Zoom <= 1.001) preview.ResetView();
        e.Handled = true;
    }

    private void OnPreviewDoubleTapped(object? sender, TappedEventArgs e)
    {
        Preview?.ResetView();
        e.Handled = true;
    }

    private void OnPreviewImagePressed(object? sender, PointerPressedEventArgs e)
    {
        if (Preview is not { IsOpen: true, Zoom: > 1 } preview) return;

        _previewDragging = true;
        _previewDragStart = e.GetPosition(PreviewOverlay);
        _previewDragOffsetX = preview.OffsetX;
        _previewDragOffsetY = preview.OffsetY;
        e.Pointer.Capture(PreviewImage);
        e.Handled = true;
    }

    private void OnPreviewImageMoved(object? sender, PointerEventArgs e)
    {
        if (!_previewDragging || Preview is not { } preview) return;

        var point = e.GetPosition(PreviewOverlay);
        preview.OffsetX = _previewDragOffsetX + (point.X - _previewDragStart.X);
        preview.OffsetY = _previewDragOffsetY + (point.Y - _previewDragStart.Y);
        e.Handled = true;
    }

    private void OnPreviewImageReleased(object? sender, PointerReleasedEventArgs e)
    {
        _previewDragging = false;
        e.Pointer.Capture(null);
    }

    private void OnPreviewBackdropPressed(object? sender, PointerPressedEventArgs e)
    {
        // 只有点在遮罩本身（不是面板 / 图片）才关闭
        if (e.Source == PreviewOverlay) Preview?.CloseCommand.Execute(null);
    }

    /// <summary>
    /// 窗口级快捷键：Ctrl+A 全选文件、Esc 取消选择（输入框/重命名框内不拦截）。
    /// task-133：另加 Ctrl+1..6 切侧栏、? / Ctrl+/ 打开快捷键浮层、Esc 逐级退出（先退搜索再退选择）。
    /// </summary>
    private void OnShellKeyDown(object? sender, KeyEventArgs e)
    {
        if (DataContext is not MainWindowViewModel vm) return;

        // 帮助浮层打开时只接受 Esc，其它键一律不穿透到背景页面
        if (ShortcutsLayer.IsOpen)
        {
            if (e.Key == Key.Escape)
            {
                ShortcutsLayer.Close();
                e.Handled = true;
            }
            return;
        }

        if (Preview is { IsOpen: true }) return;

        // task-7：公告面板打开时 Esc 关闭；只拦 Esc，其余输入照常穿透（面板不是模态）。
        if (vm.IsAnnouncementsOpen && e.Key == Key.Escape)
        {
            vm.IsAnnouncementsOpen = false;
            e.Handled = true;
            return;
        }

        // Ctrl+1..6：切到侧栏六个主页面（笔记页没有快捷键，保持鼠标可达）
        if (e.KeyModifiers.HasFlag(KeyModifiers.Control) && e.Key is >= Key.D1 and <= Key.D6)
        {
            var slot = (int)e.Key - (int)Key.D1;
            if (slot >= 0 && slot < ShortcutNavIndex.Length) SelectNav(ShortcutNavIndex[slot]);
            e.Handled = true;
            return;
        }

        // ? / Ctrl+/：打开快捷键帮助；焦点在输入框时让用户正常输入，不拦截。
        // Oem2 与 OemQuestion 都接：不同键盘布局（以及 Shift+/ 的映射）上报的虚拟键不一致。
        if ((e.Key == Key.OemQuestion || e.Key == Key.Oem2)
            && (e.KeyModifiers.HasFlag(KeyModifiers.Shift) || e.KeyModifiers.HasFlag(KeyModifiers.Control))
            && e.Source is not TextBox)
        {
            OpenShortcuts();
            e.Handled = true;
            return;
        }

        if (e.Source is TextBox) return;

        if (e.Key == Key.A && e.KeyModifiers.HasFlag(KeyModifiers.Control))
        {
            if (vm.Files.View.Count == 0) return;
            vm.Files.SelectAllCommand.Execute(null);
            e.Handled = true;
            return;
        }

        // Esc 一次退一级：搜索态优先（由文件页处理器退出搜索），否则取消选择
        if (e.Key == Key.Escape
            && string.IsNullOrEmpty(vm.Files.Keyword)
            && vm.Files.HasSelection)
        {
            vm.Files.ClearSelectionCommand.Execute(null);
            e.Handled = true;
        }
    }

    /// <summary>task-133：Ctrl+1..6 对应的侧栏序号（侧栏顺序：文件/工作区/传输/设备/会话/笔记/设置）。</summary>
    private static readonly int[] ShortcutNavIndex = { 0, 1, 2, 3, 4, 6 };

    /// <summary>task-133：打开快捷键浮层（? / Ctrl+/ / 设置页入口共用）。</summary>
    public void OpenShortcuts() => ShortcutsLayer.Open();

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (Preview is { IsOpen: true } preview)
        {
            switch (e.Key)
            {
                case Key.Escape:
                    if (WindowState == WindowState.FullScreen)
                    {
                        WindowState = _stateBeforeFullscreen;
                        e.Handled = true;
                        return;
                    }
                    preview.CloseCommand.Execute(null);
                    e.Handled = true;
                    return;
                case Key.F when preview.IsVideoPreview:
                    TogglePreviewFullscreen();
                    e.Handled = true;
                    return;
                case Key.Left when preview.CanGoPrev:
                    preview.PrevCommand.Execute(null);
                    e.Handled = true;
                    return;
                case Key.Right when preview.CanGoNext:
                    preview.NextCommand.Execute(null);
                    e.Handled = true;
                    return;
            }
        }

        base.OnKeyDown(e);
    }

    // ── 侧边栏：整行可点 + 折叠状态持久化 ─────────────────────

    private void InitSidebarState()
    {
        var prefs = LocalPrefs.Current;

        SideMenu.PropertyChanged += (_, e) =>
        {
            var name = e.Property?.Name;
            if (name == "IsMenuExpanded")
            {
                prefs.SidebarExpanded = SideMenu.IsMenuExpanded;
                prefs.Save();
                SyncSidebarToolTips();
                ScheduleBadgePosition();
            }
            else if (name == "Bounds")
            {
                // 折叠/展开/窗口缩放都体现为 Bounds 变化。
                // 这里**不能**订阅 LayoutUpdated：定位过程一旦触发 measure/布局，
                // 就会再次触发 LayoutUpdated，形成 10Hz 的重排循环（侧栏闪烁的根因）。
                // task-142：hover 也可能让 Bounds 的位置量变化，但只有尺寸变化才影响徽标坐标，
                // 所以按尺寸去重；否则悬停时反复安排定位，会和模板 hover 重绘叠加成闪烁。
                var size = SideMenu.Bounds.Size;
                if (Math.Abs(size.Width - _lastSideMenuWidth) < 0.5 &&
                    Math.Abs(size.Height - _lastSideMenuHeight) < 0.5)
                {
                    return;
                }

                _lastSideMenuWidth = size.Width;
                _lastSideMenuHeight = size.Height;
                ScheduleBadgePosition();
            }
        };

        // 兜底：即使模板的命中区只有内容宽度，按住行内任意位置也能切页
        SideMenu.AddHandler(
            Avalonia.Input.InputElement.PointerPressedEvent,
            OnSidebarPointerPressed,
            Avalonia.Interactivity.RoutingStrategies.Tunnel);

        // 首次布局后再摆一次徽标（SideMenu 尚未完成测量时拿不到合法坐标，所以延迟到 Loaded）
        Loaded += (_, _) =>
        {
            SyncSidebarToolTips();
            ScheduleBadgePosition();
        };
    }

    /// <summary>
    /// 展开态**不给**侧栏项挂 ToolTip：悬浮气泡会出现在光标附近并与 hover 状态互相抢占，
    /// 在文字区域来回触发「弹出 → 消失 → 再弹出」，看起来就是鼠标悬停时闪烁。
    /// 折叠态只剩图标，才需要文字标签做提示。
    /// </summary>
    private void SyncSidebarToolTips()
    {
        var expanded = SideMenu.IsMenuExpanded;
        foreach (var item in SideMenu.Items)
        {
            if (item is not SukiUI.Controls.SukiSideMenuItem menuItem) continue;
            Avalonia.Controls.ToolTip.SetTip(menuItem, expanded ? null : menuItem.Header);
        }
    }

    private void ScheduleBadgePosition()
    {
        _layoutDebounce ??= new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) };
        _layoutDebounce.Stop();
        _layoutDebounce.Tick -= OnLayoutDebounceTick;
        _layoutDebounce.Tick += OnLayoutDebounceTick;
        _layoutDebounce.Start();
    }

    private void OnLayoutDebounceTick(object? sender, EventArgs e)
    {
        _layoutDebounce?.Stop();
        UpdateBadgeOverlay();
    }

    /// <summary>
    /// 展开态把数字徽标摆到「传输」行右侧；折叠态数字徽标隐藏、只留图标上的小圆点（布局自动定位）。
    /// 任何一步拿不到合法坐标就不显示，绝不把徽标留在 (0,0)。
    /// </summary>
    private void UpdateBadgeOverlay()
    {
        // 折叠态：数字徽标隐藏，图标右上角小圆点（布局自动定位）
        var dotVisible = _badgeActive && !SideMenu.IsMenuExpanded;
        if (dotVisible != _dotCachedVisible)
        {
            TransferDot.IsVisible = dotVisible;
            _dotCachedVisible = dotVisible;
        }

        if (!_badgeActive || !SideMenu.IsMenuExpanded)
        {
            HideBadge();
            return;
        }

        if (SideMenu.Items.Count < 3 || SideMenu.Items[2] is not Control item)
        {
            HideBadge();
            return;
        }

        if (item.Bounds.Width <= 80 || item.Bounds.Height <= 8)
        {
            HideBadge();
            return;
        }

        var point = Avalonia.VisualExtensions.TranslatePoint(
            item,
            new Avalonia.Point(item.Bounds.Width - 6, item.Bounds.Height / 2),
            BadgeLayer);

        if (point is null)
        {
            HideBadge();
            return;
        }

        var width = Math.Max(17, _badgeTextWidth + 12);
        var left = Math.Max(0, point.Value.X - width - 8);
        var top = Math.Max(0, point.Value.Y - 8.5);

        // 坐标没变就不写：Canvas.Left/Top 的每次写入都会触发一次布局，
        // 重复写就等于持续重排（侧栏闪烁）。绝不在这里调用 Measure。
        var moved = !_badgeCachedVisible
                    || double.IsNaN(_badgeCachedLeft)
                    || Math.Abs(left - _badgeCachedLeft) > 0.5
                    || Math.Abs(top - _badgeCachedTop) > 0.5;

        if (moved)
        {
            Avalonia.Controls.Canvas.SetLeft(TransferBadge, left);
            Avalonia.Controls.Canvas.SetTop(TransferBadge, top);
            _badgeCachedLeft = left;
            _badgeCachedTop = top;
        }

        if (!_badgeCachedVisible)
        {
            TransferBadge.IsVisible = true;
            _badgeCachedVisible = true;
        }
    }

    /// <summary>隐藏数字徽标（幂等：已经隐藏时不做任何事，避免无谓的布局失效）。</summary>
    private void HideBadge()
    {
        if (!_badgeCachedVisible) return;
        TransferBadge.IsVisible = false;
        _badgeCachedVisible = false;
    }

    private void OnSidebarPointerPressed(object? sender, Avalonia.Input.PointerPressedEventArgs e)
    {
        // task-153：非选中态只有图标/文字能点中、右侧空白点不中——SukiUI 模板里
        // SukiSideMenuItem 的可视子树只按内容宽度铺开，空白处命中的是 SideMenu 自身。
        // 这里先按「命中原项」处理；没命中再按纵向位置兜底成整行命中。
        if (e.Source is Control src)
        {
            var item = Avalonia.VisualTree.VisualExtensions
                .FindAncestorOfType<SukiSideMenuItem>(src, includeSelf: true);
            if (item is not null)
            {
                if (!ReferenceEquals(SideMenu.SelectedItem, item))
                    SideMenu.SelectedItem = item;
                return;
            }
        }

        if (!e.GetCurrentPoint(SideMenu).Properties.IsLeftButtonPressed) return;

        var position = e.GetPosition(SideMenu);

        // 只处理侧栏那一列：SukiSideMenu 的可视范围横跨整个窗口（页面内容是它的子级），
        // 少了这道横向判定，点在内容区任何「与某个导航行同高」的位置都会被误判成切页。
        var columnRight = 0.0;
        foreach (var obj in SideMenu.Items)
        {
            if (obj is not SukiSideMenuItem probe) continue;
            var probeOrigin = Avalonia.VisualExtensions.TranslatePoint(probe, default, SideMenu);
            if (probeOrigin is null) continue;
            columnRight = Math.Max(columnRight, probeOrigin.Value.X + probe.Bounds.Width);
        }
        if (columnRight > 0 && position.X > columnRight + 2) return;

        foreach (var obj in SideMenu.Items)
        {
            if (obj is not SukiSideMenuItem candidate) continue;

            var origin = Avalonia.VisualExtensions.TranslatePoint(candidate, default, SideMenu);
            if (origin is null) continue;

            var top = origin.Value.Y;
            var height = candidate.Bounds.Height;
            if (height <= 0) continue;
            if (position.Y < top - 2 || position.Y > top + height + 2) continue;

            if (!ReferenceEquals(SideMenu.SelectedItem, candidate))
                SideMenu.SelectedItem = candidate;
            return;
        }
    }

    /// <summary>整行可点：行内图标/文字/右侧空白按下都切页（徽标已设 IsHitTestVisible=false）。</summary>
    private void OnSidebarItemPressed(object? sender, Avalonia.Input.PointerPressedEventArgs e)
    {
        if (sender is SukiSideMenuItem item && !ReferenceEquals(SideMenu.SelectedItem, item))
            SideMenu.SelectedItem = item;
    }

    protected override void OnOpened(EventArgs e)
    {
        base.OnOpened(e);

        // 默认停在第一项（文件）
        if (SideMenu.SelectedItem is null && SideMenu.Items.Count > 0) SideMenu.SelectedItem = SideMenu.Items[0];

        // 布局完成后再套用"上次是否展开"，避免模板初始化把它改回默认
        Dispatcher.UIThread.Post(() =>
        {
            SideMenu.IsMenuExpanded = LocalPrefs.Current.SidebarExpanded;
            UpdateBadgeOverlay();
        }, DispatcherPriority.Loaded);
    }

    private void SelectNav(int index)
    {
        if (index >= 0 && index < SideMenu.Items.Count)
        {
            // task-138：离开文档页前先把编辑器里未保存的内容落盘，避免切页丢稿。
            var current = SideMenu.Items is System.Collections.IList list && SideMenu.SelectedItem is { } selected
                ? list.IndexOf(selected)
                : -1;
            if (current == DocsNavIndex && index != DocsNavIndex)
            {
                FlushDocs();
            }

            SideMenu.SelectedItem = SideMenu.Items[index];
        }
    }

    /// <summary>文档页（侧栏「笔记」）在菜单里的位置：文件/工作区/传输/设备/会话/笔记/设置。</summary>
    private const int DocsNavIndex = 5;

    private async void FlushDocs()
    {
        try
        {
            if (DataContext is MainWindowViewModel vm && vm.Docs.IsEditorOpen)
            {
                await vm.Docs.FlushAsync();
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine("[Bond] 保存文档失败：" + ex.Message);
        }
    }

    // ── 侧栏「传输」徽标动效（SpringCurve，仅在有变化时动）─────

    private void OnViewModelPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (sender is not MainWindowViewModel vm) return;
        if (e.PropertyName != nameof(MainWindowViewModel.ActiveTransferCount)) return;

        var count = vm.ActiveTransferCount;
        SyncBadgeText(vm);

        _badgeActive = count > 0;
        UpdateBadgeOverlay();

        if (count > 0)
            StartBadgeAnim(_lastActiveCount <= 0 ? BadgeAnim.Appear : BadgeAnim.Bounce);
        else if (_lastActiveCount > 0)
            StartBadgeAnim(BadgeAnim.Disappear);

        _lastActiveCount = count;
    }

    private void SyncBadgeText(MainWindowViewModel vm)
    {
        if (!Dispatcher.UIThread.CheckAccess())
        {
            Dispatcher.UIThread.Post(() => SyncBadgeText(vm));
            return;
        }

        TransferBadgeText.Text = vm.TransferBadgeText;

        // 宽度只在文本变化时量一次（不放进定位循环里），否则每次定位都让布局失效
        TransferBadgeText.Measure(Avalonia.Size.Infinity);
        _badgeTextWidth = TransferBadgeText.DesiredSize.Width;
        _badgeCachedLeft = double.NaN;
        UpdateBadgeOverlay();
    }

    /// <summary>徽标入场 / 数字跳一下 / 淡出，全部复用 Morph 内核的弹簧曲线。</summary>
    private void StartBadgeAnim(BadgeAnim anim)
    {
        if (!Dispatcher.UIThread.CheckAccess())
        {
            Dispatcher.UIThread.Post(() => StartBadgeAnim(anim));
            return;
        }

        // 淡出动画需要元素仍然可见（定位函数会按展开态决定是否显示）
        if (anim != BadgeAnim.Disappear) UpdateBadgeOverlay();

        _badgeAnim = anim;
        _badgeStartMs = BadgeClock.Elapsed.TotalMilliseconds;

        if (_badgeTimer is null)
        {
            _badgeTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(16) };
            _badgeTimer.Tick += OnBadgeTick;
        }

        _badgeTimer.Start();
    }

    private void OnBadgeTick(object? sender, EventArgs e)
    {
        var elapsed = BadgeClock.Elapsed.TotalMilliseconds - _badgeStartMs;
        var spring = MorphSpec.Content;

        switch (_badgeAnim)
        {
            case BadgeAnim.Appear:
            {
                var t = SpringCurve.Clamp01(elapsed / BadgeAppearMs);
                var ease = SpringCurve.Normalized(t, spring.Zeta, spring.Omega);
                SetBadgeScale(0.62 + 0.38 * ease);
                TransferBadge.Opacity = SpringCurve.Clamp01(elapsed / 110.0);
                if (t >= 1.0) FinishBadgeAnim(1.0, 1.0);
                break;
            }
            case BadgeAnim.Bounce:
            {
                var t = SpringCurve.Clamp01(elapsed / BadgeAppearMs);
                var ease = SpringCurve.Normalized(t, spring.Zeta, spring.Omega);
                SetBadgeScale(1.0 + 0.12 * (1.0 - ease));
                TransferBadge.Opacity = 1.0;
                if (t >= 1.0) FinishBadgeAnim(1.0, 1.0);
                break;
            }
            case BadgeAnim.Disappear:
            {
                var t = SpringCurve.Clamp01(elapsed / BadgeFadeMs);
                TransferBadge.Opacity = 1.0 - t;
                if (t >= 1.0) FinishBadgeAnim(1.0, 0.0);
                break;
            }
        }
    }

    private void FinishBadgeAnim(double scale, double opacity)
    {
        _badgeTimer?.Stop();
        _badgeAnim = BadgeAnim.None;
        SetBadgeScale(scale);
        TransferBadge.Opacity = opacity;
    }

    private void SetBadgeScale(double scale)
    {
        if (TransferBadge.RenderTransform is ScaleTransform st)
        {
            st.ScaleX = scale;
            st.ScaleY = scale;
        }
    }

    // ── 全局 Toast ────────────────────────────────────────────

    private void OnNotified(object? sender, NoticeEventArgs e)
    {
        if (!Dispatcher.UIThread.CheckAccess())
        {
            Dispatcher.UIThread.Post(() => OnNotified(sender, e));
            return;
        }

        ToastText.Text = e.Message;
        ToastIcon.Kind = e.Kind switch
        {
            NoticeKind.Success => MaterialIconKind.CheckCircle,
            NoticeKind.Warning => MaterialIconKind.AlertOutline,
            NoticeKind.Error => MaterialIconKind.AlertCircleOutline,
            _ => MaterialIconKind.InformationOutline
        };

        ToastIcon.Foreground = Brush(e.Kind switch
        {
            NoticeKind.Success => "BondSuccess",
            NoticeKind.Warning => "BondWarning",
            NoticeKind.Error => "BondError",
            _ => "BondAccent"
        });

        ToastBar.IsVisible = true;

        _toastTimer ??= new DispatcherTimer { Interval = TimeSpan.FromSeconds(2.8) };
        _toastTimer.Stop();
        _toastTimer.Tick -= OnToastTick;
        _toastTimer.Tick += OnToastTick;
        _toastTimer.Start();
    }

    private void OnToastTick(object? sender, EventArgs e)
    {
        _toastTimer?.Stop();
        ToastBar.IsVisible = false;
    }

    private IBrush Brush(string resourceKey)
        => this.TryFindResource(resourceKey, out var value) && value is IBrush brush
            ? brush
            : Brushes.Gray;

    // ── IMainHost ─────────────────────────────────────────────
    public void RequestShow()
    {
        Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
    }

    public void RequestHide() => Hide();

    public void RequestActivate() => Activate();

    // ── 标题栏账户菜单 ────────────────────────────────────────
    private void OnAccountSettingsClick(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
    {
        if (SideMenu.Items.Count > 0) SideMenu.SelectedItem = SideMenu.Items[^1];
    }

    private void OnSignOutClick(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
    {
        // 账号隔离（task-67）：退出前把当前账号的上传任务落库并清空内存队列，避免下个账号看到。
        if (App.Services.GetService(typeof(Bond.Services.HttpBondStore))
            is Bond.Services.HttpBondStore store)
        {
            store.FlushUploadsOnSignOut();
        }

        if (App.Services.GetService(typeof(Bond.Services.Api.BondApiClient))
            is Bond.Services.Api.BondApiClient api)
        {
            api.ClearTokens();
        }

        (Avalonia.Application.Current as App)?.ReturnToLogin();
    }

    private void OnAppExitClick(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
        => (Avalonia.Application.Current as App)?.ExitApplication();

    /// <summary>关闭 = 收到托盘，保留悬浮球，不退出进程。</summary>
    protected override void OnClosing(WindowClosingEventArgs e)
    {
        e.Cancel = true;
        // task-138：收进托盘前先把文档编辑器里未保存的内容落盘。
        FlushDocs();
        Hide();
    }

    // ── task-7：公告面板交互（非阻塞覆盖层）────────────────────

    /// <summary>点遮罩关闭；只有点在遮罩本身（不是面板内控件）才关。</summary>
    private void OnAnnouncementsBackdropPressed(object? sender, PointerPressedEventArgs e)
    {
        if (DataContext is MainWindowViewModel vm && ReferenceEquals(e.Source, AnnouncementOverlay))
            vm.CloseAnnouncementsCommand.Execute(null);
    }

    /// <summary>点某条公告 → 后端标记已读（本地立即取消加粗/未读点，未读数递减）。</summary>
    private void OnAnnouncementItemPressed(object? sender, PointerPressedEventArgs e)
    {
        if (DataContext is not MainWindowViewModel vm) return;
        if (sender is not Control control) return;
        if (!e.GetCurrentPoint(control).Properties.IsLeftButtonPressed) return;
        if (control.DataContext is not Bond.Models.AnnouncementItem item) return;

        vm.MarkAnnouncementReadCommand.Execute(item);
        e.Handled = true;
    }
}
