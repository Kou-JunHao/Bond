using System;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;

namespace Bond.Views.Controls;

/// <summary>
/// task-133：键盘快捷键帮助浮层。纯展示控件，开关由 MainWindow 控制（Esc 由窗口级隧道处理器统一收口）。
/// 关闭时触发 <see cref="Closed"/>，由宿主管焦点回列表。
/// </summary>
public partial class ShortcutsOverlay : UserControl
{
    public ShortcutsOverlay()
    {
        InitializeComponent();
    }

    /// <summary>关闭后由宿主把焦点还给文件列表 / 内容区。</summary>
    public event EventHandler? Closed;

    public bool IsOpen => IsVisible;

    public void Open()
    {
        IsVisible = true;
        // 让遮罩层自己能拿焦点，避免 Esc 被背景控件先处理
        Focus();
    }

    public void Close()
    {
        if (!IsVisible) return;
        IsVisible = false;
        Closed?.Invoke(this, EventArgs.Empty);
    }

    private void OnCloseClick(object? sender, RoutedEventArgs e) => Close();

    private void OnScrimPressed(object? sender, PointerPressedEventArgs e)
    {
        // 只有点在遮罩本身（不是面板内容）才关闭
        if (ReferenceEquals(e.Source, Scrim)) Close();
    }
}
