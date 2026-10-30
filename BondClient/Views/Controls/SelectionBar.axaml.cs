using System;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Styling;
using Avalonia.Threading;
using Bond.ViewModels;

namespace Bond.Views.Controls;

/// <summary>
/// 选中操作条：HasSelection 变化时做 150ms 淡入 + 上移 8px 的过渡；
/// 「更多」弹出在线设备菜单（发送到设备）。所有动作落到 FilesPageViewModel 的命令上。
/// </summary>
public partial class SelectionBar : UserControl
{
    private readonly TranslateTransform _offset = new();
    private DispatcherTimer? _timer;
    private FilesPageViewModel? _vm;
    private bool _showing;
    private double _progress;
    private double _fromOpacity;
    private double _fromOffset;

    public SelectionBar()
    {
        InitializeComponent();

        Root.RenderTransform = _offset;
        _offset.Y = 8;

        AttachedToVisualTree += (_, _) =>
        {
            Dispatcher.UIThread.Post(Hook, DispatcherPriority.Loaded);
            ApplyThemeShadow();
            if (Application.Current is { } app) app.ActualThemeVariantChanged += OnAppThemeChanged;
        };
        DetachedFromVisualTree += (_, _) =>
        {
            Unhook();
            if (Application.Current is { } app) app.ActualThemeVariantChanged -= OnAppThemeChanged;
        };
    }

    /// <summary>暗色用 1px 边框承担层次，不再叠加投影（亮色才给柔和投影）。</summary>
    private void OnAppThemeChanged(object? sender, EventArgs e) => ApplyThemeShadow();

    private void ApplyThemeShadow()
    {
        var dark = Application.Current?.ActualThemeVariant == ThemeVariant.Dark;
        Root.BoxShadow = dark ? default : BoxShadows.Parse("0 10 28 0 #2E1B1A17");
    }

    private void Hook()
    {
        Unhook();
        _vm = DataContext as FilesPageViewModel;
        if (_vm is null) return;

        _vm.PropertyChanged += OnViewModelPropertyChanged;

        // 初次挂载时直接呈现当前状态（不做动画，避免开屏闪一下）
        if (_vm.HasSelection)
        {
            _showing = true;
            Root.Opacity = 1;
            Root.IsHitTestVisible = true;
            _offset.Y = 0;
        }
    }

    private void Unhook()
    {
        if (_vm is not null) _vm.PropertyChanged -= OnViewModelPropertyChanged;
        _vm = null;
    }

    private void OnViewModelPropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (e.PropertyName is not (nameof(FilesPageViewModel.HasSelection)
            or nameof(FilesPageViewModel.HasSingleSelection)
            or nameof(FilesPageViewModel.SelectionBarText)))
            return;

        Animate(_vm?.HasSelection == true);
    }

    private void Animate(bool show)
    {
        // 已经在目标状态：不做任何事（也避免开屏时把默认隐藏状态再"动画"一遍）
        if (show == _showing) return;

        _showing = show;
        _progress = 0;
        _fromOpacity = Root.Opacity;
        _fromOffset = _offset.Y;

        if (show) Root.IsHitTestVisible = true;

        _timer ??= new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(16) };
        _timer.Tick -= OnTick;
        _timer.Tick += OnTick;
        _timer.Start();
    }

    private void OnTick(object? sender, EventArgs e)
    {
        _progress = Math.Min(1, _progress + 16.0 / 150.0);
        var eased = 1 - Math.Pow(1 - _progress, 3); // ease-out cubic

        var targetOpacity = _showing ? 1 : 0;
        var targetOffset = _showing ? 0 : 8;

        Root.Opacity = _fromOpacity + (targetOpacity - _fromOpacity) * eased;
        _offset.Y = _fromOffset + (targetOffset - _fromOffset) * eased;

        if (_progress < 1)
            return;

        _timer?.Stop();
        if (!_showing) Root.IsHitTestVisible = false;
    }
}
