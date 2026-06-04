using System;
using System.IO;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Avalonia.Platform;
using BondClient.Views;

namespace BondClient;

public class App : Application
{
    private TrayIcon? _trayIcon;
    private FloatingBallWindow? _floatingBall;

    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
    }

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            _floatingBall = new FloatingBallWindow();
            desktop.MainWindow = _floatingBall;

            var ico = AssetLoader.Open(new Uri("avares://BondClient/Assets/Icon.ico"));
            _floatingBall.Icon = new WindowIcon(ico);

            SetupTrayIcon(ico, desktop);
            _floatingBall.Show();
        }

        base.OnFrameworkInitializationCompleted();
    }

    private void SetupTrayIcon(Stream iconStream, IClassicDesktopStyleApplicationLifetime desktop)
    {
        var showItem = new NativeMenuItem("显示悬浮球");
        showItem.Click += (_, _) =>
        {
            _floatingBall?.Show();
            _floatingBall?.Activate();
        };

        var hideItem = new NativeMenuItem("隐藏悬浮球");
        hideItem.Click += (_, _) => _floatingBall?.Hide();

        var exitItem = new NativeMenuItem("退出");
        exitItem.Click += (_, _) => desktop.Shutdown();

        var trayMenu = new NativeMenu();
        trayMenu.Items.Add(showItem);
        trayMenu.Items.Add(hideItem);
        trayMenu.Items.Add(new NativeMenuItemSeparator());
        trayMenu.Items.Add(exitItem);

        _trayIcon = new TrayIcon
        {
            Icon = new WindowIcon(iconStream),
            ToolTipText = "Bond",
            Menu = trayMenu,
            IsVisible = true
        };

        _trayIcon.Clicked += (_, _) =>
        {
            _floatingBall?.ToggleVisibility();
        };

        desktop.ShutdownRequested += (_, _) =>
        {
            _trayIcon?.Dispose();
        };
    }
}
