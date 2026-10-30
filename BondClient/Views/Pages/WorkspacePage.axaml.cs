using System;
using Avalonia.Controls;

namespace Bond.Views.Pages;

/// <summary>
/// 工作区页面：卡片网格按可用宽度自适应（窄窗口不再溢出/被裁）。
/// 右侧管理抽屉仍由 ViewModel 驱动，这里只负责列宽计算。
/// </summary>
public partial class WorkspacePage : UserControl
{
    private const double CardSpacing = 14;
    private const double CardMinWidth = 232;
    private const double CardMaxWidth = 318;
    private const int MaxColumns = 4;

    private void OnWorkspaceItemsSizeChanged(object? sender, SizeChangedEventArgs e) => UpdateWrapWidth();

    private void UpdateWrapWidth()
    {
        if (WorkspaceItems is null || WorkspaceWrap is null) return;

        var available = WorkspaceItems.Bounds.Width;
        if (available <= 1) return;

        var columns = Math.Max(1, (int)Math.Floor((available + CardSpacing) / (CardMinWidth + CardSpacing)));
        columns = Math.Min(columns, MaxColumns);

        var width = (available - (columns - 1) * CardSpacing) / columns;
        width = Math.Clamp(width, CardMinWidth, CardMaxWidth);

        if (Math.Abs(WorkspaceWrap.ItemWidth - width) > 0.5)
            WorkspaceWrap.ItemWidth = width;
    }

    private bool _syncingRoleCombo;

    /// <summary>成员角色下拉：选中即弹二次确认并把下拉回退到原值，确认成功后再由 Role 通知刷新显示。</summary>
    private void OnMemberRoleChanged(object? sender, SelectionChangedEventArgs e)
    {
        if (_syncingRoleCombo) return;
        if (sender is not ComboBox combo) return;
        if (combo.DataContext is not Bond.Models.WorkspaceMemberItem member) return;
        if (DataContext is not Bond.ViewModels.WorkspacePageViewModel vm) return;

        var role = combo.SelectedIndex == 0 ? 1 : 2;
        if (member.Role == role) return;

        vm.RequestMemberRole(member, role);

        _syncingRoleCombo = true;
        combo.SelectedIndex = member.Role == 1 ? 0 : 1;
        _syncingRoleCombo = false;
    }
}