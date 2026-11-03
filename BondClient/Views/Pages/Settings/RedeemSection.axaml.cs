using System;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;

namespace Bond.Views.Pages.Settings;

/// <summary>
/// 设置页「会员与兑换」分组视图（纯视图，绑定沿用 <see cref="Bond.ViewModels.SettingsPageViewModel"/>）。
/// <para>兑换码是 21 位长串，手打容易错：这里补上「回车直接兑换」与「从剪贴板粘贴」两个便利入口，
/// 校验与网络请求全部留在 ViewModel。</para>
/// </summary>
public partial class RedeemSection : UserControl
{
    public RedeemSection() => InitializeComponent();

    private Bond.ViewModels.SettingsPageViewModel? ViewModel
        => DataContext as Bond.ViewModels.SettingsPageViewModel;

    /// <summary>输入框里按回车 = 点「立即兑换」，省一次鼠标（输入框还能继续用，不吞普通按键）。</summary>
    private void OnRedeemCodeKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;

        var vm = ViewModel;
        if (vm is null) return;

        e.Handled = true;
        if (vm.RedeemCommand.CanExecute(null)) vm.RedeemCommand.Execute(null);
    }

    /// <summary>
    /// 从剪贴板粘贴兑换码：用户从管理端页面/聊天里复制后直接粘，避免肉眼抄写出错。
    /// 读不到时给出可读原因（平台不支持或剪贴板被占用），不静默失败。
    /// </summary>
    private async void OnPasteCodeClick(object? sender, RoutedEventArgs e)
    {
        var vm = ViewModel;
        if (vm is null) return;

        var clipboard = TopLevel.GetTopLevel(this)?.Clipboard;
        if (clipboard is null)
        {
            vm.ReportRedeemPasteFailed("当前平台不支持剪贴板");
            return;
        }

        try
        {
            // Avalonia 12 的剪贴板要先把数据整包取出来，再从数据对象里按格式读文本。
            var data = await clipboard.TryGetDataAsync();
            var text = data is null ? null : await data.TryGetTextAsync();
            if (string.IsNullOrWhiteSpace(text))
            {
                vm.ReportRedeemPasteFailed("剪贴板里没有文本");
                return;
            }

            vm.RedeemCode = text.Trim();
            RedeemCodeBox?.Focus();
        }
        catch (Exception ex)
        {
            vm.ReportRedeemPasteFailed(ex.Message);
        }
    }
}
