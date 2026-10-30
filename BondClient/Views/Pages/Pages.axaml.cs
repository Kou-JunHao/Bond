using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;
using Avalonia.Media.Imaging;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using Avalonia.VisualTree;
using Bond.Models;
using Bond.Services;
using Bond.ViewModels;
using SukiUI.Controls;

namespace Bond.Views.Pages;

// 页面 code-behind：只把 UI 事件翻译成 ViewModel 调用，不含业务逻辑。

public partial class FilesPage : UserControl
{
    public FilesPage()
    {
        InitializeComponent();

        DataContextChanged += (_, _) =>
        {
            if (DataContext is FilesPageViewModel vm)
            {
                vm.RevealRequested -= RevealNode;
                vm.RevealRequested += RevealNode;
            }
        };

        // 行/卡片内部的 PointerPressed 属性绑定在 ClickCount==2 时会被双击手势识别器吃掉，
        // 所以统一在页面级用 Tunnel 阶段捕获：一定能拿到 cc=1 选中、cc=2 打开预览。
        AddHandler(PointerPressedEvent, OnFilesPointerPressed,
            Avalonia.Interactivity.RoutingStrategies.Tunnel, handledEventsToo: true);

        AttachedToVisualTree += (_, _) => HookShareCopy();
        DataContextChanged += (_, _) => HookShareCopy();
        DetachedFromVisualTree += (_, _) =>
        {
            if (_hookedVm is not null) _hookedVm.CopyRequested -= OnCopyRequested;
            _hookedVm = null;
        };
    }

    private FilesPageViewModel? _hookedVm;

    /// <summary>复制订阅挂在 DataContext 上（页面 AttachedToVisualTree 时 DataContext 可能还没绑定）。</summary>
    private void HookShareCopy()
    {
        if (_hookedVm is not null) _hookedVm.CopyRequested -= OnCopyRequested;
        _hookedVm = DataContext as FilesPageViewModel;
        if (_hookedVm is not null) _hookedVm.CopyRequested += OnCopyRequested;
    }

    /// <summary>复制分享链接：ViewModel 不直接碰剪贴板，这里写剪贴板并给轻提示。</summary>
    private async void OnCopyRequested(object? sender, string url)
    {
        try
        {
            var clipboard = TopLevel.GetTopLevel(this)?.Clipboard;
            if (clipboard is null) return;
            await clipboard.SetTextAsync(url);
            if (DataContext is FilesPageViewModel vm) vm.NotifyCopied();
        }
        catch
        {
            // 剪贴板占用等异常时静默，不打断交互
        }
    }

    private void OnRowMenuShare(object? sender, RoutedEventArgs e)
    {
        if (DataContext is FilesPageViewModel vm) vm.BeginCreateShare();
    }

    // ── task-105：文件转存（服务端复制） ──

    private void OnRowMenuCopy(object? sender, RoutedEventArgs e)
    {
        if (DataContext is FilesPageViewModel vm) vm.BeginCopy();
    }

    /// <summary>task-166：右键「移动到…」入口（仅单选，当前空间内选目录）。</summary>
    private void OnRowMenuMove(object? sender, RoutedEventArgs e)
    {
        if (DataContext is FilesPageViewModel vm) vm.BeginMove();
    }

    /// <summary>转存目标选择器：切换目标空间后重新加载根目录文件夹。</summary>
    private async void OnCopySpaceChanged(object? sender, SelectionChangedEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm) return;
        if (sender is ComboBox combo)
            await vm.SetCopySpaceAsync(combo.SelectedItem as FileSpace).ConfigureAwait(true);
    }

    /// <summary>点目标目录里的文件夹 → 进入该目录。</summary>
    private async void OnCopyFolderClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm) return;
        if ((sender as Control)?.DataContext is NodeItem folder)
            await vm.EnterCopyFolderAsync(folder).ConfigureAwait(true);
    }

    private DateTime _lastOpenAt = DateTime.MinValue;

    // ── 平铺视图：固定 160 宽卡片，按可用宽度算列数（卡片不拉伸，右侧宁可留白）──

    private void OnTileListLoaded(object? sender, RoutedEventArgs e)
        => ApplyTileColumns(sender as ItemsControl);

    private void OnTileListSizeChanged(object? sender, SizeChangedEventArgs e)
        => ApplyTileColumns(sender as ItemsControl, e.NewSize.Width);

    private void ApplyTileColumns(ItemsControl? list, double? width = null, int retry = 0)
    {
        if (list is null) return;

        var available = width ?? list.Bounds.Width;
        if (available <= 0) return;

        if (list.ItemsPanelRoot is not WrapPanel wrap)
        {
            // 面板尚未生成：下一拍再试（最多 3 次）
            if (retry >= 3) return;
            var timer = new Avalonia.Threading.DispatcherTimer
            {
                Interval = TimeSpan.FromMilliseconds(120)
            };
            timer.Tick += (_, _) =>
            {
                timer.Stop();
                ApplyTileColumns(list, width, retry + 1);
            };
            timer.Start();
            return;
        }

        // 单元格固定 104×136、间距 6：列数按可用宽度反算（floor((w+gap)/(cell+gap))），
        // 单元格本身不随容器拉伸，整体左对齐——资源管理器「中图标」那种密度。
        const double spacing = 6;
        const double itemWidth = 104;

        if (Math.Abs(wrap.ItemWidth - itemWidth) < 0.5 &&
            Math.Abs(wrap.ItemSpacing - spacing) < 0.5)
            return;

        wrap.ItemWidth = itemWidth;
        wrap.ItemSpacing = spacing;
        wrap.LineSpacing = spacing;
    }

    /// <summary>
    /// 页面级指针入口（Tunnel 阶段，早于行内控件与手势识别器）：
    /// 单击选中行；双击文件打开预览覆盖层、双击文件夹进入下一级。
    /// 行内不再挂 PointerPressed/DoubleTapped，避免 cc=2 被手势识别器吞掉。
    /// </summary>
    private void OnFilesPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm) return;

        var node = FindNode(e.Source as Control);

        // 点空白/工具栏/搜索框时 node 为 null：直接忽略，不清空选中
        if (node is null) return;

        // Ctrl + 单击：在选中集合里加选 / 减选（多选后操作条会出现在内容区底部）
        if ((e.KeyModifiers & KeyModifiers.Control) == KeyModifiers.Control)
        {
            vm.ToggleSelection(node);
            e.Handled = true;
            return;
        }

        vm.Selected = node;

        if (e.ClickCount < 2) return;
        if (!e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) return;
        if ((DateTime.UtcNow - _lastOpenAt).TotalMilliseconds < 350) return;

        _lastOpenAt = DateTime.UtcNow;
        e.Handled = true;
        _ = OpenNodeAsync(vm, node);
    }

    private static async Task OpenNodeAsync(FilesPageViewModel vm, NodeItem node)
    {
        // task-123：搜索态下打开结果——目录切空间并进入该目录（同时退出搜索），文件照常预览。
        if (vm.IsSearchActive)
        {
            await vm.OpenSearchResultAsync(node);
            return;
        }

        if (node.Kind == NodeKind.Folder) vm.Activate(node);
        else await vm.OpenPreviewAsync(node);
    }

    /// <summary>从事件源向上找承载 NodeItem 的行/卡片；越过 ItemsControl 就停（避免误命中页面级 DataContext）。</summary>
    private static NodeItem? FindNode(Control? source)
    {
        Avalonia.Visual? v = source;
        for (var i = 0; i < 12 && v is not null; i++)
        {
            if (v is Control c)
            {
                if (c.DataContext is NodeItem node) return node;
                if (c is ItemsControl) return null;
            }

            v = v.GetVisualParent();
        }

        return null;
    }

    private void OnItemDoubleTapped(object? sender, TappedEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm || vm.Selected is null) return;

        // 文件夹：进入；文件：打开预览覆盖层
        _ = vm.OpenPreviewAsync(vm.Selected);
    }

    /// <summary>面包屑：点任意一段回到那一级目录。</summary>
    private void OnCrumbClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm) return;
        if (sender is Button { DataContext: Breadcrumb crumb }) vm.NavigateCrumbCommand.Execute(crumb);
    }

    /// <summary>右键先把被点的行设为选中，菜单动作都基于 Selected。</summary>
    private void OnRowPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm) return;
        if (sender is not Control { DataContext: NodeItem node }) return;

        vm.Selected = node;
    }

    /// <summary>上传中虚拟行的「取消上传」（列表/平铺/右键菜单共用）。</summary>
    private void OnVirtualUploadCancelClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm) return;
        if (sender is Control { DataContext: UploadTaskItem task })
            vm.CancelUploadRowCommand.Execute(task);
    }

    /// <summary>上传失败行的「继续」（断点续传）。</summary>
    private void OnVirtualUploadResumeClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm) return;
        if (sender is Control { DataContext: UploadTaskItem task })
            vm.ResumeUploadRowCommand.Execute(task);
    }

    // ── task-166：移动文件 / 文件夹的目标目录选择器 ──────────────

    private void OnMoveFolderClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm) return;
        if (sender is Control { DataContext: NodeItem folder })
            _ = vm.OpenMoveFolderAsync(folder);
    }

    // ── task-4：上传到…的目标目录选择器 ─────────────────────────

    private void OnUploadTargetFolderClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm) return;
        if (sender is Control { DataContext: NodeItem folder })
            _ = vm.OpenUploadTargetFolderAsync(folder);
    }

    // ── task-4：回收站平铺卡单击勾选（批量恢复入口） ─────────────

    private void OnTrashTilePressed(object? sender, PointerPressedEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm) return;
        if (sender is not Control { DataContext: TrashRow row } tile) return;

        var point = e.GetCurrentPoint(tile);
        if (!point.Properties.IsLeftButtonPressed) return;

        vm.ToggleTrashRowSelection(row);
        e.Handled = true;
    }

    // ── 行右键菜单 ────────────────────────────────────────────

    /// <summary>
    /// 右键时先同步选中项、再准备"发送到设备"子菜单。
    /// <para>注意：过去这段逻辑挂在 <c>ContextMenu.Opening</c> 上，会在弹层已进入
    /// 打开流程时清空并重建 Items，导致弹层状态算错、点外部也关不掉。
    /// 现在改在 <c>ContextRequested</c>（弹出之前）执行，弹层本身不再被改动。</para>
    /// </summary>
    private void OnRowContextRequested(object? sender, ContextRequestedEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm) return;

        // 从被点中的控件往上找 NodeItem（点空白处则为 null，保持原有选中不变）
        var node = (e.Source as Control)?.DataContext as NodeItem;
        if (node is null && e.Source is Avalonia.Visual v)
        {
            node = v.GetVisualAncestors()
                    .OfType<Control>()
                    .Select(c => c.DataContext)
                    .OfType<NodeItem>()
                    .FirstOrDefault();
        }

        if (node is not null) vm.Selected = node;

        // task-183：分享入口对单个文件或文件夹可用（文件夹匿名下载为 ZIP）；多选时置灰。
        var shareItem = FindShareMenuItem(sender);
        if (shareItem is not null && node is not null)
            shareItem.IsEnabled = vm.CanCreateShareFor(node);

        // 转存同口径：仍仅单个文件（文件夹/多选禁用，ToolTip 已在 XAML 说明）。
        // 注意不能复用 CanCreateShareFor：它 task-183 起已允许文件夹，会把「转存」误放开。
        var copyItem = FindMenuItem(sender, "转存到…");
        if (copyItem is not null && node is not null)
            copyItem.IsEnabled = node.IsFile && vm.SelectionCount <= 1;

        // task-166：移动支持单个文件或文件夹（多选时置灰）。
        var moveItem = FindMenuItem(sender, "移动到…");
        if (moveItem is not null && node is not null)
            moveItem.IsEnabled = vm.CanMoveNode(node);

        RebuildSendSubmenu(vm);
    }

    private static MenuItem? FindShareMenuItem(object? sender) => FindMenuItem(sender, "创建分享链接");

    /// <summary>在右键菜单里按 Header 文案找菜单项（分享/转存共用）。</summary>
    private static MenuItem? FindMenuItem(object? sender, string headerWant)
    {
        if (sender is not ItemsControl { ContextMenu: { Items: var items } }) return null;
        foreach (var item in items)
        {
            if (item is MenuItem { Header: string header } mi && header == headerWant) return mi;
        }
        return null;
    }

    /// <summary>填充「发送到设备」子菜单（弹层出现之前调用）。</summary>
    private void RebuildSendSubmenu(FilesPageViewModel vm)
    {
        var send = FindSendMenu();
        if (send is null) return;
        if (send.Items is not System.Collections.IList list) return;

        list.Clear();

        var peers = vm.OnlinePeers;
        if (peers.Count == 0)
        {
            send.IsEnabled = false;
            send.Header = "发送到设备（附近无在线设备）";
            return;
        }

        send.IsEnabled = true;
        send.Header = "发送到设备";
        foreach (var peer in peers)
        {
            var node = vm.Selected;
            var captured = peer;
            var item = new MenuItem { Header = peer.Name, Tag = peer };
            item.Click += (_, _) => _ = vm.SendToDeviceAsync(node, captured);
            list.Add(item);
        }
    }

    /// <summary>在页面里找到带 Tag="send" 的菜单项（列表与平铺各有一个 ContextMenu）。</summary>
    private MenuItem? FindSendMenu()
        => this.GetVisualDescendants()
               .OfType<ItemsControl>()
               .Select(c => c.ContextMenu)
               .OfType<ContextMenu>()
               .SelectMany(m => m.Items.OfType<MenuItem>())
               .FirstOrDefault(m => (m.Tag as string) == "send");

    private void OnRowMenuOpen(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm || vm.Selected is null) return;
        if (vm.Selected.IsFolder) vm.Activate(vm.Selected);
        else _ = vm.DownloadAsync(vm.Selected);
    }

    private void OnRowMenuDownload(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm) return;
        _ = vm.DownloadAsync(vm.Selected);
    }

    private void OnRowMenuRename(object? sender, RoutedEventArgs e)
    {
        if (DataContext is FilesPageViewModel vm) vm.BeginRename(vm.Selected);
    }

    private void OnRowMenuDelete(object? sender, RoutedEventArgs e)
    {
        if (DataContext is FilesPageViewModel vm) vm.RequestDelete(vm.Selected);
    }

    // ── 键盘 ──────────────────────────────────────────────────

    /// <summary>Ctrl+F 聚焦搜索，Esc 清空搜索 / 关闭内联编辑。</summary>
    // ===== task-116：从资源管理器拖文件到文件页 / 工作区文件页 =====

    private static bool IsFileDrag(DragEventArgs e) => e.DataTransfer.Contains(DataFormat.File);

    private void OnFilesDragEnter(object? sender, DragEventArgs e) => SetExternalDragVisual(e);

    private void OnFilesDragOver(object? sender, DragEventArgs e) => SetExternalDragVisual(e);

    private void OnFilesDragLeave(object? sender, DragEventArgs e)
    {
        if (DataContext is FilesPageViewModel vm)
        {
            vm.SetExternalDrag(false);
        }
    }

    private void SetExternalDragVisual(DragEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm)
        {
            return;
        }

        if (!IsFileDrag(e))
        {
            e.DragEffects = DragDropEffects.None;
            return;
        }

        e.DragEffects = DragDropEffects.Copy;
        e.Handled = true;
        vm.SetExternalDrag(true);
    }

    private async void OnFilesDrop(object? sender, DragEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm)
        {
            return;
        }

        e.Handled = true;
        if (!IsFileDrag(e))
        {
            vm.SetExternalDrag(false);
            return;
        }

        e.DragEffects = DragDropEffects.Copy;
        var paths = e.DataTransfer.TryGetFiles()?
            .Select(item => item.TryGetLocalPath())
            .Where(path => !string.IsNullOrEmpty(path))
            .Select(path => path!)
            .ToList() ?? new List<string>();

        if (paths.Count == 0)
        {
            vm.SetExternalDrag(false);
            return;
        }

        await vm.DropFilesAsync(paths);
    }

    // ===== task-120：新建文件夹 =====

    private void RevealNode(NodeItem node)
    {
        Dispatcher.UIThread.Post(() =>
        {
            foreach (var control in this.GetVisualDescendants().OfType<Control>())
            {
                if (!ReferenceEquals(control.DataContext, node))
                {
                    continue;
                }

                control.BringIntoView();
                break;
            }
        });
    }

    private void OnNewFolderClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is FilesPageViewModel vm)
        {
            vm.BeginCreateFolder();
        }
    }

    private void OnRowMenuNewFolder(object? sender, RoutedEventArgs e)
    {
        if (DataContext is FilesPageViewModel vm)
        {
            vm.BeginCreateFolder();
        }
    }

    private void OnNewFolderCancelClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is FilesPageViewModel vm)
        {
            vm.CancelCreateFolderCommand.Execute(null);
        }
    }

    private void OnNewFolderBoxAttached(object? sender, VisualTreeAttachmentEventArgs e)
    {
        if (sender is TextBox box)
        {
            Dispatcher.UIThread.Post(() =>
            {
                box.Focus();
                box.SelectAll();
            }, DispatcherPriority.Background);
        }
    }

    private void OnNewFolderKeyDown(object? sender, KeyEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm)
        {
            return;
        }

        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            vm.CreateFolderCommand.Execute(null);
        }
        else if (e.Key == Key.Escape)
        {
            e.Handled = true;
            vm.CancelCreateFolderCommand.Execute(null);
        }
    }

    private void OnPageKeyDown(object? sender, KeyEventArgs e)
    {
        if (DataContext is not FilesPageViewModel vm) return;

        var ctrl = e.KeyModifiers.HasFlag(KeyModifiers.Control);
        var shift = e.KeyModifiers.HasFlag(KeyModifiers.Shift);

        if (e.Key == Key.F && ctrl)
        {
            SearchBox.Focus();
            SearchBox.SelectAll();
            e.Handled = true;
            return;
        }

        // 输入控件（搜索框/重命名框）获得焦点时，除 Esc 外的快捷键一律交给控件自己处理，
        // 否则会出现「在搜索框里按 Delete 把文件删了」这类误操作。
        if (e.Source is TextBox && e.Key != Key.Escape) return;

        if (e.Key == Key.A && ctrl)
        {
            vm.SelectAllCommand.Execute(null);
            e.Handled = true;
            return;
        }

        // task-133：资源管理器口径的键盘操作（列表态与平铺态共用这一份处理）
        if (e.Key == Key.F2)
        {
            vm.RequestRenameSelection();
            e.Handled = true;
            return;
        }

        if (e.Key == Key.Delete)
        {
            if (vm.HasSelection || vm.Selected is not null)
            {
                vm.RequestDeleteFromKeyboard();
                e.Handled = true;
            }
            return;
        }

        if (e.Key == Key.F5)
        {
            vm.RefreshCommand.Execute(null);
            e.Handled = true;
            return;
        }

        if (e.Key == Key.N && ctrl && shift)
        {
            vm.BeginCreateFolder();
            e.Handled = true;
            return;
        }

        if (e.Key == Key.D && ctrl)
        {
            if (vm.HasSelection || vm.Selected is not null)
            {
                vm.RequestDownloadFromKeyboard();
                e.Handled = true;
            }
            return;
        }

        if (e.Key == Key.Enter && vm.EditingNode is null && vm.Selected is { } selected)
        {
            _ = OpenNodeAsync(vm, selected);
            e.Handled = true;
            return;
        }

        if (e.Key != Key.Escape) return;

        // 新建文件夹弹窗是页面内的模态层：Esc 必须先关它，否则会去清选择/退搜索，弹窗反而留着
        if (vm.IsNewFolderOpen)
        {
            vm.CancelCreateFolderCommand.Execute(null);
            e.Handled = true;
            return;
        }

        // Esc 一次退一级：行内编辑 → 删除确认 → 搜索 → 多选 → 错误重试
        if (vm.EditingNode is { } editing) vm.CancelRename(editing);
        else if (vm.IsConfirmingDelete) vm.CancelDeleteCommand.Execute(null);
        else if (!string.IsNullOrEmpty(vm.Keyword))
        {
            vm.Keyword = string.Empty;
            Focus(); // 焦点从搜索框回到列表，避免继续往搜索框打字
        }
        else if (vm.HasSelection) vm.ClearSelectionCommand.Execute(null);
        else if (vm.HasError) vm.RefreshCommand.Execute(null);
        e.Handled = true;
    }

    // ── task-123：搜索入口 / 滚动加载 ─────────────────────────

    /// <summary>工具栏放大镜：聚焦搜索框（与 Ctrl+F 等价）。</summary>
    private void OnSearchButtonClick(object? sender, RoutedEventArgs e)
    {
        SearchBox.Focus();
        SearchBox.SelectAll();
    }

    /// <summary>列表/平铺滚动到底部附近时自动续页（只读判定，真正去重与节流在 ViewModel）。</summary>
    private void OnFileListScrollChanged(object? sender, ScrollChangedEventArgs e)
    {
        if (sender is not ScrollViewer sv) return;
        if (DataContext is not FilesPageViewModel vm || !vm.IsSearchActive) return;

        var remaining = sv.Extent.Height - sv.Offset.Y - sv.Viewport.Height;
        if (remaining > 120) return;
        vm.MaybeLoadMoreSearch();
    }

    // ── 行内重命名 ────────────────────────────────────────────

    /// <summary>进入编辑后自动聚焦，并只预选文件名主体（不含扩展名）。</summary>
    private void OnRenameBoxAttached(object? sender, Avalonia.VisualTreeAttachmentEventArgs e)
    {
        if (sender is not TextBox box) return;
        if (DataContext is not FilesPageViewModel) return;
        if (box.DataContext is not NodeItem node || !node.IsEditing) return;

        Avalonia.Threading.Dispatcher.UIThread.Post(() =>
        {
            if (!node.IsEditing) return;

            box.Focus();

            var body = node.NameBodyLength;
            if (body <= 0 || body >= (box.Text?.Length ?? 0))
            {
                box.SelectAll();
            }
            else
            {
                box.SelectionStart = 0;
                box.SelectionEnd = body;
            }
        }, Avalonia.Threading.DispatcherPriority.Input);
    }

    /// <summary>Enter 提交、Esc 取消。</summary>
    private void OnRenameBoxKeyDown(object? sender, KeyEventArgs e)
    {
        if (sender is not TextBox box) return;
        if (DataContext is not FilesPageViewModel vm) return;
        if (box.DataContext is not NodeItem node) return;

        if (e.Key == Key.Enter)
        {
            _ = vm.CommitRenameAsync(node);
            e.Handled = true;
        }
        else if (e.Key == Key.Escape)
        {
            vm.CancelRename(node);
            e.Handled = true;
        }
    }

    /// <summary>失焦提交（点别处即保存；Esc/Enter 已把 IsEditing 置 false 时不会重复提交）。</summary>
    private void OnRenameBoxLostFocus(object? sender, RoutedEventArgs e)
    {
        if (sender is not TextBox box) return;
        if (DataContext is not FilesPageViewModel vm) return;
        if (box.DataContext is not NodeItem node || !node.IsEditing) return;

        _ = vm.CommitRenameAsync(node);
    }

    /// <summary>输入时实时校验空名 / 非法字符，行内标红。</summary>
    private void OnRenameTextChanged(object? sender, TextChangedEventArgs e)
    {
        if (sender is not TextBox box) return;
        if (DataContext is not FilesPageViewModel vm) return;
        if (box.DataContext is not NodeItem node) return;

        vm.ValidateRename(node);
    }

    private void OnOpenDataFolderClick(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
    {
        var dir = System.IO.Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Bond");
        System.IO.Directory.CreateDirectory(dir);

        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = dir,
                UseShellExecute = true
            });
        }
        catch
        {
            // 打不开资源管理器时不打断用户操作
        }
    }
}

public partial class WorkspacePage : UserControl
{
    public WorkspacePage()
    {
        InitializeComponent();
        Loaded += (_, _) =>
        {
            if (DataContext is WorkspacePageViewModel vm) _ = vm.LoadAsync();
        };
    }

    /// <summary>点击工作区卡片 = 进入该工作区（切到文件页并载入）。</summary>
    private void OnWorkspaceClick(object? sender, PointerPressedEventArgs e)
    {
        if (DataContext is not WorkspacePageViewModel vm) return;
        if (sender is Control { DataContext: WorkspaceItem workspace }) vm.Open(workspace);
    }

    /// <summary>复制邀请链接（管理抽屉）。</summary>
    private async void OnCopyInviteLinkClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not WorkspacePageViewModel vm) return;
        if (string.IsNullOrWhiteSpace(vm.InviteLink)) return;

        try
        {
            var clipboard = TopLevel.GetTopLevel(this)?.Clipboard;
            if (clipboard is null) return;
            await clipboard.SetTextAsync(vm.InviteLink).ConfigureAwait(true);
            if (App.Services.GetService(typeof(INotificationCenter)) is INotificationCenter notify)
                notify.Notify("邀请链接已复制", NoticeKind.Success);
        }
        catch
        {
            // 剪贴板失败不打断操作
        }
    }
}

public partial class TransferPage : UserControl
{
    private TransferPageViewModel? _vm;

    public TransferPage()
    {
        InitializeComponent();
        DataContextChanged += (_, _) => HookViewModel();
    }

    private void HookViewModel()
    {
        if (_vm is not null) _vm.RevealInFilesRequested -= OnRevealInFilesRequested;

        _vm = DataContext as TransferPageViewModel;
        if (_vm is not null) _vm.RevealInFilesRequested += OnRevealInFilesRequested;
    }

    /// <summary>
    /// task-4：传输中心的「定位」——网盘上传/下载会话切到文件页并选中云端文件。
    /// 先切页再定位：否则导航发生在看不见的页面上，用户会以为按钮没反应。
    /// </summary>
    private async void OnRevealInFilesRequested(TransferCloudTarget target)
    {
        SwitchToFilesPage();

        if (App.Services?.GetService(typeof(FilesPageViewModel)) is not FilesPageViewModel files)
            return;

        var found = await files
            .RevealCloudLocationAsync(target.SpaceId, target.ParentPath, target.FileName)
            .ConfigureAwait(true);

        if (found) return;

        // 目录打开了但没找到该条目（被移动/删除/同名自动改名）：给出明确解释，不静默。
        var notify = App.Services?.GetService(typeof(INotificationCenter)) as INotificationCenter;
        notify?.Notify(
            $"已打开 {(target.ParentPath is null or "/" ? "根目录" : target.ParentPath)}，没找到「{target.FileName}」（可能已被移动或删除）",
            NoticeKind.Warning);
    }

    /// <summary>把侧栏选中项切到「文件」（与 MainWindow 的 SelectNav 同一做法）。</summary>
    private void SwitchToFilesPage()
    {
        var menu = this.FindAncestorOfType<SukiSideMenu>()
                   ?? TopLevel.GetTopLevel(this)?.GetVisualDescendants()
                       .OfType<SukiSideMenu>().FirstOrDefault();
        if (menu is null || menu.Items.Count == 0) return;

        menu.SelectedItem = menu.Items[0];
    }
}

public partial class DevicesPage : UserControl
{
    public DevicesPage() => InitializeComponent();

    private async void OnSendClick(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
    {
        if (DataContext is not DevicesPageViewModel vm) return;
        if (sender is Button { DataContext: DevicePeer peer })
            await vm.SendToAsync(peer).ConfigureAwait(true);
    }

    /// <summary>task-35：离线设备行上的「移除」——本机行提示不可移除，其它行弹二次确认。</summary>
    private void OnRemoveClick(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
    {
        if (DataContext is not DevicesPageViewModel vm) return;
        if (sender is Button { DataContext: DevicePeer peer })
            vm.RequestRemove(peer);
    }
}

public partial class ChatPage : UserControl
{
    private ChatPageViewModel? _vm;
    private Window? _top;
    private bool _loadingHistory;
    private bool _stickToBottom = true;
    private bool _healingScrollEnd;
    private int _lastBarredMessageCount;
    private double _lastExtentHeight;
    private bool _autoScrollPending;
    private int _autoScrollFrames;
    private readonly DispatcherTimer _autoScrollTimer;

    public ChatPage()
    {
        InitializeComponent();

        // Enter 发送 / Shift+Enter 换行：TextBox 自己会先处理 Enter（插入换行），
        // 所以必须在隧道阶段拦截，XAML 上的 KeyDown 属性拿到的是已处理事件。
        DraftBox.AddHandler(KeyDownEvent, OnDraftKeyDown, RoutingStrategies.Tunnel);

        // task-178：图片/缩略图是异步解码的，贴底滚动要跟着后续布局继续校正，
        // 否则最后一条消息会停在输入区上沿被裁一半（用户反馈的「输入区遮挡最新消息」）。
        MessagesScroll.LayoutUpdated += OnMessagesLayoutUpdated;
        _autoScrollTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(120) };
        _autoScrollTimer.Tick += OnAutoScrollTimerTick;

        // Ctrl+F 打开会话内搜索（task-139），页面级隧道拦截，避免焦点在输入框时失效。
        AddHandler(KeyDownEvent, OnPageKeyDown, RoutingStrategies.Tunnel);

        // 拖文件到会话页 → 上传并发送（图片按扩展名判定为 image）。
        DragDrop.SetAllowDrop(this, true);
        AddHandler(DragDrop.DragOverEvent, OnChatDragOver);
        AddHandler(DragDrop.DragLeaveEvent, OnChatDragLeave);
        AddHandler(DragDrop.DropEvent, OnChatDrop);

        DataContextChanged += (_, _) => AttachViewModel(DataContext as ChatPageViewModel);
        SizeChanged += OnChatSizeChanged;
        PropertyChanged += OnSelfPropertyChanged;
        AttachedToVisualTree += (_, _) =>
        {
            _top = TopLevel.GetTopLevel(this) as Window;
            Bond.Services.HttpBondStore.ChatDiag($"chat page attach top={_top is not null} active={_top?.IsActive} visible={IsVisible}");
            if (_top is not null)
            {
                _top.Activated += OnTopLevelActivated;
                _top.Deactivated += OnTopLevelDeactivated;
                if (_top.IsActive) AttachViewModel(DataContext as ChatPageViewModel, windowActive: true);
            }

            AttachViewModel(DataContext as ChatPageViewModel);

            // 页面在 VM 已加载完会话后才挂上视觉树（或从别的页切回来）：补一次贴底，
            // 不能只依赖 ScrollToEndRequested（那次请求可能发生在页面可测量之前）。
            if (_vm is { Messages.Count: > 0 })
            {
                Dispatcher.UIThread.Post(StartAutoScroll, DispatcherPriority.Loaded);
            }
        };
        DetachedFromVisualTree += (_, _) =>
        {
            if (_top is not null)
            {
                _top.Activated -= OnTopLevelActivated;
                _top.Deactivated -= OnTopLevelDeactivated;
                _top = null;
            }

            // 离开会话页 = 用户不再看这个会话：不要再把新消息当已读吞掉，未读角标照常累计。
            _vm?.SetWindowActive(false);
            AttachViewModel(null);
        };
    }

    private void AttachViewModel(ChatPageViewModel? vm, bool windowActive = false)
    {
        Bond.Services.HttpBondStore.ChatDiag($"attach vm null={(vm is null)} windowActive={windowActive} same={ReferenceEquals(_vm, vm)}");
        if (ReferenceEquals(_vm, vm))
        {
            if (vm is null) return;
            if (windowActive) vm.SetWindowActive(true);
            vm.SetPageVisible(IsVisible);
            return;
        }

        if (_vm is not null)
        {
            _vm.ScrollToEndRequested -= OnScrollToEndRequested;
            _vm.ScrollToMessageRequested -= OnScrollToMessageRequested;
        }
        _vm = vm;
        if (_vm is not null)
        {
            _vm.ScrollToEndRequested += OnScrollToEndRequested;
            _vm.ScrollToMessageRequested += OnScrollToMessageRequested;
            // 侧栏切换页面时页面可能仍挂在可视树上，只靠 Attach/Detach 判断不了可见性。
            _vm.SetPageVisible(IsVisible);
        }
    }

    /// <summary>侧栏切换页面 → IsVisible 变化，同步「用户是否真的在看会话页」（未读/已读语义依赖它）。</summary>
    private void OnSelfPropertyChanged(object? sender, AvaloniaPropertyChangedEventArgs e)
    {
        if (e.Property == IsVisibleProperty)
        {
            Bond.Services.HttpBondStore.ChatDiag($"chat page IsVisible -> {IsVisible}");
            _vm?.SetPageVisible(IsVisible);

            // task-178：从别的侧栏页切回来时补一次贴底（消息可能早已加载完、页面此前无 Extent）。
            if (IsVisible && _vm is { Messages.Count: > 0 })
            {
                Dispatcher.UIThread.Post(StartAutoScroll, DispatcherPriority.Loaded);
            }
        }
    }

    private void OnTopLevelActivated(object? sender, EventArgs e)
    {
        Bond.Services.HttpBondStore.ChatDiag("window activated");
        _vm?.SetWindowActive(true);
    }

    private void OnTopLevelDeactivated(object? sender, EventArgs e)
    {
        Bond.Services.HttpBondStore.ChatDiag("window deactivated");
        _vm?.SetWindowActive(false);
    }

    private void OnDraftKeyDown(object? sender, KeyEventArgs e)
    {
        if (DataContext is not ChatPageViewModel vm) return;

        // task-165：Ctrl+V 粘贴图片。纯文本仍交给 TextBox 自己处理（不抢用户粘贴文本）。
        if (e.Key == Key.V && e.KeyModifiers.HasFlag(KeyModifiers.Control))
        {
            e.Handled = true;
            _ = HandlePasteAsync(vm);
            return;
        }

        if (e.Key != Key.Enter) return;

        // Shift+Enter 交给 TextBox 换行；Enter 发送。
        if (e.KeyModifiers.HasFlag(KeyModifiers.Shift)) return;
        if (!vm.CanSend) return;
        if (vm.SendCommand.CanExecute(null)) vm.SendCommand.Execute(null);
        e.Handled = true;
    }

    /// <summary>
    /// task-165：剪贴板里有位图且没有文本 → 作为图片消息发送；有文本则手工插入到 Caret 处，
    /// 保持「粘贴文本」与系统输入框一致的体验。
    /// </summary>
    private async Task HandlePasteAsync(ChatPageViewModel vm)
    {
        var clipboard = TopLevel.GetTopLevel(this)?.Clipboard;
        if (clipboard is null) return;

        try
        {
            var data = await clipboard.TryGetDataAsync();
            if (data is null) return;

            var hasFiles = data.Contains(DataFormat.File);
            var hasBitmap = data.Contains(DataFormat.Bitmap);
            var hasText = data.Contains(DataFormat.Text);
            Bond.Services.HttpBondStore.ChatDiag($"chat paste: files={hasFiles} bitmap={hasBitmap} text={hasText}");

            // 从资源管理器复制图片/文件后粘贴：按扩展名分别走图片或文件消息。
            if (hasFiles)
            {
                var files = await data.TryGetFilesAsync();
                var paths = (files ?? [])
                    .Select(f => f.TryGetLocalPath())
                    .Where(p => !string.IsNullOrWhiteSpace(p) && System.IO.File.Exists(p!))
                    .Select(p => p!)
                    .ToList();
                if (paths.Count == 0) return;

                var images = paths.Where(p => _imageExtensions.Contains(System.IO.Path.GetExtension(p))).ToList();
                var others = paths.Where(p => !_imageExtensions.Contains(System.IO.Path.GetExtension(p))).ToList();
                if (images.Count > 0) await vm.AttachFilesAsync(images, asImage: true);
                if (others.Count > 0) await vm.AttachFilesAsync(others, asImage: false);
                return;
            }

            if (hasBitmap && !hasText)
            {
                var bitmap = await data.TryGetBitmapAsync();
                Bond.Services.HttpBondStore.ChatDiag($"chat paste bitmap obj={bitmap is not null}");
                if (bitmap is not null)
                {
                    using var stream = new MemoryStream();
                    bitmap.Save(stream);
                    await vm.PasteImageAsync(stream.ToArray());
                    return;
                }
            }

            var text = await data.TryGetTextAsync();
            if (string.IsNullOrEmpty(text)) return;

            var current = DraftBox.Text ?? string.Empty;
            var caret = Math.Clamp(DraftBox.CaretIndex, 0, current.Length);
            DraftBox.Text = current[..caret] + text + current[caret..];
            DraftBox.CaretIndex = caret + text.Length;
        }
        catch (Exception ex)
        {
            Bond.Services.HttpBondStore.ChatDiag($"chat paste failed: {ex.GetType().Name}: {ex.Message}");
        }
    }

    private void OnScrollToMessageRequested(object? sender, ChatMessage message)
    {
        // 搜索结果跳转：优先让容器自己 BringIntoView；虚拟化下容器可能还没生成，退回按索引估算。
        Dispatcher.UIThread.Post(() =>
        {
            if (MessagesList is null || MessagesScroll is null) return;

            var container = MessagesList.GetVisualDescendants()
                .OfType<Control>()
                .FirstOrDefault(control => ReferenceEquals(control.DataContext, message));
            if (container is not null)
            {
                container.BringIntoView();
                return;
            }

            var index = _vm?.Messages.IndexOf(message) ?? -1;
            if (index >= 0)
            {
                MessagesScroll.Offset = new Vector(0, Math.Max(0, index * 68 - 120));
            }
        }, DispatcherPriority.Background);
    }

    private void OnPageKeyDown(object? sender, KeyEventArgs e)
    {
        if (DataContext is not ChatPageViewModel vm) return;
        if (e.Key == Key.F && e.KeyModifiers.HasFlag(KeyModifiers.Control))
        {
            if (vm.ToggleSearchCommand.CanExecute(null)) vm.ToggleSearchCommand.Execute(null);
            e.Handled = true;
        }
    }

    private void OnChatDragOver(object? sender, DragEventArgs e)
    {
        var hasFile = e.DataTransfer.Contains(DataFormat.File);
        e.DragEffects = hasFile ? DragDropEffects.Copy : DragDropEffects.None;
        if (hasFile) _vm?.BeginDragOver();
        else _vm?.EndDragOver();
    }

    private void OnChatDragLeave(object? sender, DragEventArgs e) => _vm?.EndDragOver();

    private void OnChatDrop(object? sender, DragEventArgs e)
    {
        _vm?.EndDragOver();
        if (_vm is null || !e.DataTransfer.Contains(DataFormat.File)) return;

        var files = e.DataTransfer.TryGetFiles();
        if (files is null) return;

        // task-165：多文件按顺序逐条发送（每个文件一条独立气泡、独立进度与取消）。
        var paths = files
            .Select(f => f.Path.LocalPath)
            .Where(p => !string.IsNullOrWhiteSpace(p) && System.IO.File.Exists(p))
            .ToList();
        if (paths.Count == 0) return;

        _ = _vm.DropFilesAsync(paths);
    }

    private static readonly HashSet<string> _imageExtensions =
        new(StringComparer.OrdinalIgnoreCase) { ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp" };

    private void OnAttachImageClick(object? sender, RoutedEventArgs e) => _ = PickAndAttachAsync(asImage: true);

    private void OnAttachFileClick(object? sender, RoutedEventArgs e) => _ = PickAndAttachAsync(asImage: false);

    private async Task PickAndAttachAsync(bool asImage)
    {
        if (_vm is null) return;
        var top = TopLevel.GetTopLevel(this);
        if (top?.StorageProvider is null) return;

        var filter = new FilePickerFileType(asImage ? "图片" : "文件")
        {
            Patterns = asImage
                ? ["*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.bmp"]
                : ["*.*"]
        };
        var files = await top.StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = asImage ? "选择要发送的图片" : "选择要发送的文件",
            AllowMultiple = true,
            FileTypeFilter = [filter],
        });
        if (files.Count == 0) return;

        var paths = files
            .Select(f => f.Path.LocalPath)
            .Where(p => !string.IsNullOrWhiteSpace(p))
            .ToList();
        await _vm.AttachFilesAsync(paths, asImage);
    }

    private void OnScrollToEndRequested(object? sender, ChatScrollRequestedEventArgs e)
    {
        // 只有「已经在底部」时才自动跟随新消息，避免用户往上翻历史时被拽走。
        if (!e.Force && !_stickToBottom) return;
        Dispatcher.UIThread.Post(() => ScrollToEnd(), DispatcherPriority.Background);
    }

    private void ScrollToEnd()
    {
        if (MessagesScroll is null) return;
        _stickToBottom = true;
        HideNewMessagesBar();
        StartAutoScroll();
    }

    /// <summary>
    /// task-178：贴底滚动是「持续动作」而不是一次赋值。
    /// <para>
    /// ScrollViewer 的 Extent 要等布局完成（以及图片/缩略图异步解码）后才准确：
    /// 之前只 Post 一次，等于按旧高度算 offset，看起来就是最新几条被底部输入区挡住。
    /// 这里在布局帧里持续校正，直到真正贴底、或检测到用户主动向上滚动。
    /// </para>
    /// </summary>
    private void StartAutoScroll()
    {
        _autoScrollPending = true;
        _autoScrollFrames = 0;
        ApplyScrollToEnd();
        _autoScrollTimer.Start();
        Dispatcher.UIThread.Post(OnAutoScrollTick, DispatcherPriority.Loaded);
    }

    /// <summary>计时器每 120ms 校准一次：图片/缩略图异步解码会分多次撑大 Extent（实测 2s 内仍可能在变）。</summary>
    private void OnAutoScrollTimerTick(object? sender, EventArgs e)
    {
        if (!_autoScrollPending || MessagesScroll is null)
        {
            StopAutoScroll();
            return;
        }

        // 用户主动向上翻（位置远离底部且 Extent 没在增长）→ 停止校准，别抢滚动条。
        var distance = MessagesScroll.Extent.Height - MessagesScroll.Viewport.Height - MessagesScroll.Offset.Y;
        if (distance > 48 && !_stickToBottom)
        {
            StopAutoScroll();
            return;
        }

        ApplyScrollToEnd();
        if (++_autoScrollFrames >= 20) StopAutoScroll();
    }

    private void OnAutoScrollTick()
    {
        if (!_autoScrollPending) return;
        ApplyScrollToEnd();
    }

    private void OnMessagesLayoutUpdated(object? sender, EventArgs e)
    {
        if (!_autoScrollPending) return;
        ApplyScrollToEnd();
    }

    private void StopAutoScroll()
    {
        _autoScrollPending = false;
        _autoScrollFrames = 0;
        _autoScrollTimer?.Stop();
    }

    private void ApplyScrollToEnd()
    {
        if (MessagesScroll is null) return;
        MessagesScroll.Offset = new Vector(
            MessagesScroll.Offset.X,
            Math.Max(0, MessagesScroll.Extent.Height - MessagesScroll.Viewport.Height));
    }

    /// <summary>
    /// task-166：气泡最大宽 = 会话区宽度的 60%（写进页面资源供样式引用，避免硬编码像素）；
    /// 窄窗时把右侧「会话详情」收起，而不是挤压消息区。
    /// </summary>
    /// <summary>
    /// task-166 审阅修复：文件卡不再用 Button 包裹（Button 模板会覆盖文字前景色），
    /// 改为 Border + PointerPressed，点击后走同一个 OpenAttachmentCommand。
    /// </summary>
    private void OnAttachmentPressed(object? sender, PointerPressedEventArgs e)
    {
        if (sender is not Control control || control.DataContext is not ChatMessage message)
        {
            return;
        }

        if (_vm?.OpenAttachmentCommand.CanExecute(message) == true)
        {
            _vm.OpenAttachmentCommand.Execute(message);
            e.Handled = true;
        }
    }
    private void OnChatSizeChanged(object? sender, SizeChangedEventArgs e)
    {
        var messageArea = Math.Max(320, e.NewSize.Width - 280 - 48);
        var bubbleMax = Math.Round(messageArea * 0.60);

        if (DataContext is ChatPageViewModel vm)
        {
            // task-166：气泡最大宽度 = 消息区宽度 × 60%（不含左右 24 内边距），走 VM 绑定（资源字典在模板里刷新不可靠）。
            vm.BubbleMaxWidth = bubbleMax;
            if (e.NewSize.Width < 1180 && vm.ShowDetails) vm.ShowDetails = false;
        }

        Resources["ChatBubbleMaxWidth"] = bubbleMax;

        // task-178：页面第一次拿到真实尺寸（从隐藏切回/首次布局）时补一次贴底。
        if (e.PreviousSize.Width < 10 && e.NewSize.Width > 200 && _vm is { Messages.Count: > 0 })
        {
            Dispatcher.UIThread.Post(StartAutoScroll, DispatcherPriority.Loaded);
        }
    }

    /// <summary>滚动离开底部且消息有更新时，底部出现「有新消息 ↓」浮条。</summary>
    private void UpdateNewMessagesBar(int messageCount)
    {
        if (NewMessagesBar is null || _vm is null) return;

        if (messageCount == 0 || _stickToBottom)
        {
            HideNewMessagesBar();
            return;
        }

        NewMessagesBar.IsVisible = messageCount != _lastBarredMessageCount;
        _lastBarredMessageCount = messageCount;
    }

    private void HideNewMessagesBar()
    {
        if (NewMessagesBar is not null) NewMessagesBar.IsVisible = false;
        _lastBarredMessageCount = _vm?.Messages.Count ?? 0;
    }

    private void OnNewMessagesClick(object? sender, RoutedEventArgs e) => ScrollToEnd();

    private async void OnMessagesScrollChanged(object? sender, ScrollChangedEventArgs e)
    {
        if (MessagesScroll is not { } scroll || _vm is null) return;

        var extentHeight = scroll.Extent.Height;
        var extentGrew = extentHeight > _lastExtentHeight + 1;
        _lastExtentHeight = extentHeight;

        var wasAtBottom = _stickToBottom;
        var distanceToBottom = extentHeight - scroll.Viewport.Height - scroll.Offset.Y;
        _stickToBottom = distanceToBottom <= 48;
        UpdateNewMessagesBar(_vm.Messages.Count);

        // 自动贴底期间：Extent 没变而位置远离底部 = 用户主动向上翻，立刻停手，别抢滚动条。
        if (_autoScrollPending && !extentGrew && distanceToBottom > 48)
        {
            StopAutoScroll();
        }

        // task-177：气泡是可变高内容，Extent 在布局后才更新；若用户本来就在底部，
        // 这里自愈一次，避免新消息把最后一条顶出可视区（截图里表现为最后一条被裁掉一半）。
        // task-178：图片/缩略图是异步解码的，晚到会把 Extent 再撑大而 offset 不动 —— 用
        // 「Extent 变大 + 变化前贴底」判定内容增长，而不是用变化后的距离（大增量会被误判成用户上翻）。
        // 这样新消息/图片加载后始终能看到完整最新消息，也不会在用户主动向上滚动时抢滚动条。
        if (extentGrew && wasAtBottom && distanceToBottom > 1 && !_loadingHistory && !_healingScrollEnd)
        {
            _healingScrollEnd = true;
            Dispatcher.UIThread.Post(() =>
            {
                _healingScrollEnd = false;
                ApplyScrollToEnd();
            }, DispatcherPriority.Background);
        }

        if (_loadingHistory || scroll.Offset.Y > 8 || !_vm.HasMoreHistory) return;

        _loadingHistory = true;
        var extentBefore = scroll.Extent.Height;
        var offsetBefore = scroll.Offset.Y;
        try
        {
            var added = await _vm.LoadHistoryAsync();
            if (added <= 0) return;

            // 新内容插在顶部：把滚动偏移补上增量，视觉位置保持不变。
            Dispatcher.UIThread.Post(() =>
            {
                var delta = scroll.Extent.Height - extentBefore;
                if (delta > 0)
                {
                    scroll.Offset = new Vector(scroll.Offset.X, offsetBefore + delta);
                }
            }, DispatcherPriority.Background);
        }
        finally
        {
            _loadingHistory = false;
        }
    }
}

public partial class SettingsPage : UserControl
{
    // task-142 rev2：设置页 = 单页长滚动 + 锚点导航；窄窗时导航折叠为顶部横向 chip。
    // task-154：内容区宽度断点沿用 1020（主窗 MinWidth=1040，窄布局在最小窗口下才可达）。
    private const double NarrowBreakpoint = 1020;
    private const double AnchorPadding = 24;

    private readonly Control[] _groups;
    private readonly Button[] _navs;
    private int _activeGroup;
    private bool? _narrowLayout;
    private double _savedOffset;
    private DispatcherTimer? _scrollTimer;
    private bool _scrollingToAnchor;

    // task-154：scroll-spy 节流（100ms 单次）。每个 ScrollChanged 都算一次 TranslatePoint 会在
    // 平滑滚动/拖滚动条时产生成百次无意义换算，节流后只按最终位置高亮。
    private readonly DispatcherTimer _spyTimer;
    // task-154：点击导航时的目标分组。动画期间不跑 spy，动画结束把高亮锁在目标上，
    // 避免「点 A 却先高亮 B 再跳回 A」的闪一下观感。
    private int _scrollTargetGroup = -1;

    public SettingsPage()
    {
        InitializeComponent();

        _groups = new Control[] { GroupProfile, Group0, Group1, Group2, Group3, Group4 };
        _navs = new[] { NavProfile, NavAccount, NavAppearance, NavTransfer, NavNetwork, NavAbout };

        _spyTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) };
        _spyTimer.Tick += (_, _) =>
        {
            _spyTimer.Stop();
            if (!_scrollingToAnchor) UpdateScrollSpy();
        };

        // 进入设置页时从 /api/auth/me 读取当前邮箱与昵称（不缓存、不落盘）
        AttachedToVisualTree += (_, _) =>
        {
            if (DataContext is Bond.ViewModels.SettingsPageViewModel vm)
            {
                _ = vm.RefreshEmailAsync();
                vm.InitProfileUi();
                _ = vm.RefreshProfileAsync();
            }

            ApplyNavLayout(Bounds.Width);
            SelectNav(_activeGroup);
            // 等这一轮布局算完再恢复上次滚动位置，避免被 0 高度覆盖
            Dispatcher.UIThread.Post(RestoreScrollOffset, DispatcherPriority.Background);
        };
        DetachedFromVisualTree += (_, _) =>
        {
            _spyTimer.Stop();
            SaveScrollOffset();
        };

        // task-142：宽度变化只切换导航形态（纯布局，不重建视图、不触发布局循环）
        SizeChanged += (_, _) => ApplyNavLayout(Bounds.Width);
    }

    /// <summary>task-142 rev2：点击锚点导航，滚到对应分组（200ms 缓动，顶部留 24px）。</summary>
    private void OnNavClick(object? sender, RoutedEventArgs e)
    {
        if (sender is Button button && button.Tag is string tag && int.TryParse(tag, out var index))
        {
            _scrollTargetGroup = index;
            _activeGroup = index;
            SelectNav(index);
            // 窄屏 chip：先把被点的项横向滚进视野，再滚动内容，避免 chip 被截一半。
            if (_narrowLayout == true) BringNavIntoView(index);
            if (GroupTop(index) is { } groupTop)
            {
                var max = Math.Max(0, ContentScroll.Extent.Height - ContentScroll.Viewport.Height);
                AnimateScrollTo(Math.Clamp(groupTop - AnchorPadding, 0, max));
            }
        }
    }

    /// <summary>task-154：导航键盘操作（↑↓/Home/End 移动并滚动，Enter/Space 激活）。</summary>
    private void OnNavKeyDown(object? sender, KeyEventArgs e)
    {
        var focused = TopLevel.GetTopLevel(this)?.FocusManager?.GetFocusedElement() as Button;
        var current = focused is null ? -1 : Array.IndexOf(_navs, focused);
        if (current < 0) current = _activeGroup;

        switch (e.Key)
        {
            case Key.Up:
                e.Handled = true;
                SelectAndFocusNav(Math.Max(0, current - 1));
                break;
            case Key.Down:
                e.Handled = true;
                SelectAndFocusNav(Math.Min(_navs.Length - 1, current + 1));
                break;
            case Key.Home:
                e.Handled = true;
                SelectAndFocusNav(0);
                break;
            case Key.End:
                e.Handled = true;
                SelectAndFocusNav(_navs.Length - 1);
                break;
            case Key.Enter:
            case Key.Space:
                e.Handled = true;
                ActivateNav(current);
                break;
        }
    }

    private void SelectAndFocusNav(int index)
    {
        if (index < 0 || index >= _navs.Length) return;
        _navs[index].Focus(NavigationMethod.Directional);
        ActivateNav(index);
    }

    private void ActivateNav(int index)
    {
        if (index < 0 || index >= _navs.Length) return;
        _scrollTargetGroup = index;
        _activeGroup = index;
        SelectNav(index);
        if (_narrowLayout == true) BringNavIntoView(index);
        if (GroupTop(index) is { } groupTop)
        {
            var max = Math.Max(0, ContentScroll.Extent.Height - ContentScroll.Viewport.Height);
            AnimateScrollTo(Math.Clamp(groupTop - AnchorPadding, 0, max));
        }
    }

    /// <summary>窄屏 chip：把当前导航项滚进横向视野（不抢键盘焦点）。</summary>
    private void BringNavIntoView(int index)
    {
        if (index < 0 || index >= _navs.Length) return;
        Dispatcher.UIThread.Post(() => _navs[index].BringIntoView(), DispatcherPriority.Background);
    }

    /// <summary>滚动条变化：只重启 100ms 节流计时器，真实判定交给 UpdateScrollSpy。</summary>
    private void OnContentScrollChanged(object? sender, ScrollChangedEventArgs e)
    {
        if (_scrollingToAnchor) return;
        _spyTimer.Stop();
        _spyTimer.Start();
    }

    /// <summary>分组在滚动内容坐标系里的顶部位置。</summary>
    private double? GroupTop(int index)
    {
        if (index < 0 || index >= _groups.Length) return null;
        return _groups[index].TranslatePoint(new Point(0, 0), SectionsStack)?.Y;
    }

    /// <summary>scroll-spy：以「视口顶部 + 24px」判定当前分组；滚到底时强制最后一组高亮。</summary>
    private void UpdateScrollSpy()
    {
        var offset = ContentScroll.Offset.Y;
        var viewportTop = offset + AnchorPadding;
        var active = 0;
        for (var i = 0; i < _groups.Length; i++)
        {
            if (GroupTop(i) is { } top && top <= viewportTop) active = i;
        }

        var max = Math.Max(0, ContentScroll.Extent.Height - ContentScroll.Viewport.Height);
        if (max > 0 && offset >= max - 2) active = _groups.Length - 1;

        if (active == _activeGroup) return;
        _activeGroup = active;
        SelectNav(active);
    }

    private void SelectNav(int index)
    {
        for (var i = 0; i < _navs.Length; i++) _navs[i].Classes.Set("active", i == index);
    }

    /// <summary>200ms 缓动滚动；不用 Avalonia 动画系统，避免与 Suki 主题的过渡叠加。</summary>
    private void AnimateScrollTo(double target)
    {
        _scrollTimer?.Stop();
        var start = ContentScroll.Offset.Y;
        if (Math.Abs(target - start) < 0.5)
        {
            ContentScroll.Offset = new Vector(ContentScroll.Offset.X, target);
            LockHighlightToTarget();
            return;
        }

        _scrollingToAnchor = true;
        var stopwatch = System.Diagnostics.Stopwatch.StartNew();
        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(16) };
        _scrollTimer = timer;
        timer.Tick += (_, _) =>
        {
            var progress = Math.Min(1, stopwatch.Elapsed.TotalMilliseconds / 200);
            var eased = 1 - Math.Pow(1 - progress, 3);
            ContentScroll.Offset = new Vector(ContentScroll.Offset.X, start + (target - start) * eased);
            if (progress < 1) return;

            timer.Stop();
            _scrollTimer = null;
            _scrollingToAnchor = false;
            LockHighlightToTarget();
        };
        timer.Start();
    }

    /// <summary>task-154：点击导航后的滚动结束时，把高亮钉在目标分组上（不再用 spy 反推，避免闪一下）。</summary>
    private void LockHighlightToTarget()
    {
        if (_scrollTargetGroup >= 0 && _scrollTargetGroup < _groups.Length)
        {
            _activeGroup = _scrollTargetGroup;
            SelectNav(_activeGroup);
            _scrollTargetGroup = -1;
            return;
        }

        UpdateScrollSpy();
    }

    private void SaveScrollOffset()
    {
        if (ContentScroll is not null) _savedOffset = ContentScroll.Offset.Y;
    }

    private void RestoreScrollOffset()
    {
        if (_savedOffset > 0.5)
        {
            var max = Math.Max(0, ContentScroll.Extent.Height - ContentScroll.Viewport.Height);
            ContentScroll.Offset = new Vector(ContentScroll.Offset.X, Math.Min(_savedOffset, max));
        }

        UpdateScrollSpy();
    }

    /// <summary>task-142：宽屏左栏竖排吸顶 / 窄屏顶部横排 chip；仅改布局属性，避免视图重建与选中态闪烁。</summary>
    private void ApplyNavLayout(double width)
    {
        var narrow = width > 0 && width < NarrowBreakpoint;
        if (_narrowLayout == narrow) return;
        _narrowLayout = narrow;

        // task-154：chip 态用更紧凑的导航尺寸（44 行高在横向 chip 里太重），类名切在按钮上而不是改尺寸表。
        foreach (var nav in _navs) nav.Classes.Set("chip", narrow);

        if (narrow)
        {
            BodyGrid.ColumnDefinitions = new ColumnDefinitions("*");
            BodyGrid.RowDefinitions = new RowDefinitions("Auto,*");
            Grid.SetRow(NavHost, 0);
            Grid.SetColumn(NavHost, 0);
            Grid.SetRow(ContentHost, 1);
            Grid.SetColumn(ContentHost, 0);
            NavPanel.Orientation = Orientation.Horizontal;
            NavPanel.Spacing = 6;
            NavHost.Margin = new Thickness(0, 0, 0, 10);
            NavHost.VerticalScrollBarVisibility = ScrollBarVisibility.Disabled;
            NavHost.HorizontalScrollBarVisibility = ScrollBarVisibility.Auto;
        }
        else
        {
            BodyGrid.ColumnDefinitions = new ColumnDefinitions("220,*");
            BodyGrid.RowDefinitions = new RowDefinitions("*");
            Grid.SetRow(NavHost, 0);
            Grid.SetColumn(NavHost, 0);
            Grid.SetRow(ContentHost, 0);
            Grid.SetColumn(ContentHost, 1);
            NavPanel.Orientation = Orientation.Vertical;
            NavPanel.Spacing = 2;
            NavHost.Margin = new Thickness(0, 0, 14, 0);
            NavHost.VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
            NavHost.HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled;
        }

        // 切换形态后保持当前分组高亮；窄屏把选中项滚进 chip 视野。
        SelectNav(_activeGroup);
        if (narrow) BringNavIntoView(_activeGroup);
    }
}
