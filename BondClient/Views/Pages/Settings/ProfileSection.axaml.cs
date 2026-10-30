using System;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;

namespace Bond.Views.Pages.Settings;

/// <summary>task-153：设置页「个人资料」分组（昵称 / 账号 / 设备码只读）；task-155：头像上传入口。</summary>
public partial class ProfileSection : Avalonia.Controls.UserControl
{
    public ProfileSection() => InitializeComponent();

    private async void OnCopyDeviceCodeClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not Bond.ViewModels.SettingsPageViewModel vm) return;

        if (TopLevel.GetTopLevel(this)?.Clipboard is not { } clipboard)
        {
            vm.ReportDeviceCodeCopied(false);
            return;
        }

        await clipboard.SetTextAsync(vm.DeviceCode ?? string.Empty);
        vm.ReportDeviceCodeCopied(true);
    }

    /// <summary>task-155：选图 → 交给 ViewModel 上传（前端先挡扩展名/2MB，失败展示后端原文）。</summary>
    private async void OnChangeAvatarClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not Bond.ViewModels.SettingsPageViewModel vm) return;
        if (!vm.CanEditAvatar) return;

        var top = TopLevel.GetTopLevel(this);
        if (top?.StorageProvider is null) return;

        try
        {
            var files = await top.StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
            {
                Title = "选择头像图片",
                AllowMultiple = false,
                FileTypeFilter =
                [
                    new FilePickerFileType("图片（PNG / JPEG / WebP）")
                    {
                        Patterns = ["*.png", "*.jpg", "*.jpeg", "*.webp"],
                        MimeTypes = ["image/png", "image/jpeg", "image/webp"],
                    },
                ],
            });

            if (files.Count == 0) return;

            var path = files[0].TryGetLocalPath();
            if (string.IsNullOrEmpty(path))
            {
                // 非本地文件（如云盘虚拟项）：当前实现只接受本地路径。
                return;
            }

            await vm.UploadAvatarAsync(path!);
        }
        catch (Exception ex)
        {
            // 选择器本身失败（权限/平台不支持）：给出可读提示，不静默。
            vm.ReportAvatarPickerFailed(ex.Message);
        }
    }
}
