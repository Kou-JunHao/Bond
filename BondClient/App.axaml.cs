using System;
using System.IO;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using BondClient.Views;
using SkiaSharp;
using Svg.Skia;

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

            var svgStream = RenderSvgToPngStream(32);
            if (svgStream != null)
            {
                _floatingBall.Icon = new WindowIcon(svgStream);
                svgStream.Position = 0;
                SetupTrayIcon(svgStream, desktop);
            }
            else
            {
                var ico = AssetLoader.Open(new Uri("avares://BondClient/Assets/Icon.ico"));
                _floatingBall.Icon = new WindowIcon(ico);
                SetupTrayIcon(ico, desktop);
            }

            _floatingBall.Show();
        }

        base.OnFrameworkInitializationCompleted();
    }

    private static MemoryStream? RenderSvgToPngStream(int size)
    {
        try
        {
            var uri = new Uri("avares://BondClient/Assets/logo.svg");
            using var stream = AssetLoader.Open(uri);
            var svg = new SKSvg();
            svg.Load(stream);

            if (svg.Picture == null) return null;

            using var bitmap = new SKBitmap(size, size, SKColorType.Rgba8888, SKAlphaType.Premul);
            using var canvas = new SKCanvas(bitmap);
            canvas.Clear(SKColors.Transparent);

            var scaleX = size / svg.Picture.CullRect.Width;
            var scaleY = size / svg.Picture.CullRect.Height;
            var scale = Math.Min(scaleX, scaleY);
            canvas.Translate(
                (size - svg.Picture.CullRect.Width * scale) / 2,
                (size - svg.Picture.CullRect.Height * scale) / 2);
            canvas.Scale(scale);
            canvas.DrawPicture(svg.Picture);
            canvas.Flush();

            using var image = SKImage.FromBitmap(bitmap);
            using var data = image.Encode(SKEncodedImageFormat.Png, 100);
            var ms = new MemoryStream();
            data.SaveTo(ms);
            ms.Position = 0;
            return ms;
        }
        catch
        {
            return null;
        }
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
            if (_floatingBall == null) return;
            if (_floatingBall.IsVisible)
                _floatingBall.Hide();
            else
            {
                _floatingBall.Show();
                _floatingBall.Activate();
            }
        };

        desktop.ShutdownRequested += (_, _) =>
        {
            _trayIcon?.Dispose();
        };
    }
}
