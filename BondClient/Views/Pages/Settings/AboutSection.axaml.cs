using Avalonia.Controls;
using Avalonia.Interactivity;

namespace Bond.Views.Pages.Settings;

/// <summary>task-142：设置页「关于与更新」分组视图。</summary>
public partial class AboutSection : UserControl
{
    public AboutSection() => InitializeComponent();

    /// <summary>task-133：设置页的「查看键盘快捷键」入口，复用主窗口的全局浮层（与 ? 等价）。</summary>
    private void OnShortcutsClick(object? sender, RoutedEventArgs e)
    {
        if (TopLevel.GetTopLevel(this) is Bond.Views.MainWindow main) main.OpenShortcuts();
    }
}