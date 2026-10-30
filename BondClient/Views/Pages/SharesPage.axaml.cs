using System;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Threading;
using Bond.ViewModels;

namespace Bond.Views.Pages;

/// <summary>
/// 「分享」独立管理页（2026-10-29）：侧栏一级入口，集中管理已创建的匿名下载链接。
/// 数据全部来自 <see cref="FilesPageViewModel"/>（与文件页的快捷分享面板共用），
/// 这里只负责「页面显示时补一次加载」，避免用户切进来看到空白。
/// </summary>
public partial class SharesPage : UserControl
{
    public SharesPage()
    {
        InitializeComponent();
        AttachedToVisualTree += OnAttachedToVisualTree;
        DataContextChanged += OnDataContextChanged;
    }

    private void OnAttachedToVisualTree(object? sender, VisualTreeAttachmentEventArgs e)
        // 等一帧让 DataContext 绑定与布局就绪再拉数据（PageContent 的 DataContext 是绑定赋值的，
        // 在 Attached 同步阶段可能还是 null——之前就是在这里直接 return 导致页面永远空列表）。
        => Dispatcher.UIThread.Post(TryLoad, DispatcherPriority.Background);

    private void OnDataContextChanged(object? sender, EventArgs e)
        => Dispatcher.UIThread.Post(TryLoad, DispatcherPriority.Background);

    private void TryLoad()
    {
        if (DataContext is FilesPageViewModel vm)
            _ = vm.EnsureSharesLoadedAsync();
    }
}
