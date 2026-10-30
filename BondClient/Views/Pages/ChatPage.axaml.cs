using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using Bond.Models;
using Bond.Services;
using Bond.ViewModels;
using Material.Icons;
using Material.Icons.Avalonia;

namespace Bond.Views.Pages;

/// <summary>
/// task-178：聊天页头像的“真实头像 + 首字母占位”附件。
/// <para>
/// 数据来源（不伪造任何用户/在线状态）：
/// · 会话列表 / 会话头 / 私聊对方消息：<c>ChatThread.AvatarUrl</c>（服务端 <c>peerAvatarUrl</c>，
///   形如 <c>/api/transfer/avatar/{userId}</c>，经带鉴权的 BondApiClient 取字节）。
/// · 自己消息：<c>ISessionService.AvatarSource</c>（登录时就已解码好的位图），直接由 ViewModel 暴露。
/// · 工作区频道里其他成员的消息：服务端 ChatMessage DTO 目前没有 senderAvatarUrl，
///   这里不回填、显示昵称首字母；需要后端补字段后才能显示成员真实头像。
/// </para>
/// <para>
/// 用法：<c>&lt;Image pages:ChatAvatar.Url="{Binding AvatarUrl}" /&gt;</c>，
/// <c>&lt;TextBlock pages:ChatAvatar.Name="{Binding Title}" /&gt;</c>（文字自动取首个字素，兼容 emoji）。
/// </para>
/// </summary>
public sealed class ChatAvatar
{
    private ChatAvatar()
    {
    }
    private static readonly Dictionary<string, Bitmap?> Cache = new(StringComparer.Ordinal);
    private static readonly Dictionary<string, Task<Bitmap?>> Loading = new(StringComparer.Ordinal);
    private static readonly object Gate = new();

    public static readonly AttachedProperty<string?> UrlProperty =
        AvaloniaProperty.RegisterAttached<ChatAvatar, Image, string?>("Url");

    public static readonly AttachedProperty<string?> NameProperty =
        AvaloniaProperty.RegisterAttached<ChatAvatar, TextBlock, string?>("Name");

    static ChatAvatar()
    {
        UrlProperty.Changed.AddClassHandler<Image>((image, args) => OnUrlChanged(image, args));
        NameProperty.Changed.AddClassHandler<TextBlock>((text, args) => OnNameChanged(text, args));
    }

    public static string? GetUrl(Image image) => image.GetValue(UrlProperty);

    public static void SetUrl(Image image, string? value) => image.SetValue(UrlProperty, value);

    public static string? GetName(TextBlock text) => text.GetValue(NameProperty);

    public static void SetName(TextBlock text, string? value) => text.SetValue(NameProperty, value);

    private static void OnNameChanged(TextBlock text, AvaloniaPropertyChangedEventArgs args)
        => text.Text = FirstGlyph(args.NewValue as string);

    /// <summary>首字素（不是首字节）：中文取一个字，emoji 不会被拆成半个。</summary>
    private static string FirstGlyph(string? name)
    {
        if (string.IsNullOrWhiteSpace(name)) return "?";
        var trimmed = name.Trim();
        try
        {
            var info = new System.Globalization.StringInfo(trimmed);
            return info.SubstringByTextElements(0, 1).ToUpperInvariant();
        }
        catch
        {
            return trimmed[..1].ToUpperInvariant();
        }
    }

    private static async void OnUrlChanged(Image image, AvaloniaPropertyChangedEventArgs args)
    {
        if (args.NewValue is not string url || string.IsNullOrWhiteSpace(url))
        {
            image.Source = null;
            return;
        }

        var bitmap = await GetBitmapAsync(url).ConfigureAwait(true);
        if (bitmap is null) return;

        // 虚拟化滚动会复用容器：URL 已变时不要把旧头像回填到新行（否则头像串行）。
        Dispatcher.UIThread.Post(() =>
        {
            if (string.Equals(GetUrl(image), url, StringComparison.Ordinal))
            {
                image.Source = bitmap;
            }
        });
    }

    private static Task<Bitmap?> GetBitmapAsync(string url)
    {
        lock (Gate)
        {
            if (Cache.TryGetValue(url, out var cached)) return Task.FromResult(cached);
            if (Loading.TryGetValue(url, out var inflight)) return inflight;
        }

        var task = LoadAsync(url);
        lock (Gate)
        {
            Loading[url] = task;
        }

        return task;
    }

    private static async Task<Bitmap?> LoadAsync(string url)
    {
        try
        {
            var api = Bond.App.Services?.GetService(typeof(Bond.Services.Api.BondApiClient))
                as Bond.Services.Api.BondApiClient;
            if (api is null || !api.IsLoggedIn) return null;

            var bytes = await api.GetBytesAsync(url).ConfigureAwait(false);
            if (bytes is not { Length: > 0 }) return null;

            using var stream = new MemoryStream(bytes);
            var bitmap = new Bitmap(stream);
            lock (Gate)
            {
                Cache[url] = bitmap;
                Loading.Remove(url);
            }

            return bitmap;
        }
        catch
        {
            lock (Gate)
            {
                Loading.Remove(url);
            }

            return null;
        }
    }
}

/// <summary>
/// task-3：QQ 式聊天图片气泡 —— 按图片真实宽高比渲染缩略图（等比，不裁切、不拉伸），
/// 最大 220×260 DIP、圆角 12，外层按钮统一走「点击预览」。
/// <para>
/// 数据来源与 ThumbnailView 完全一致（<see cref="IThumbnailService"/>：本地占位 / 聊天文件令牌 /
/// 工作区文件），失败时显示中性占位图标而不是破图；卸载/节点变化时取消加载，避免虚拟化串图。
/// 用自定义控件而不是固定 220×220 的 ThumbnailView，是因为固定方框 + UniformToFill 会把
/// 横图/竖图裁成方块，正是用户说的「图片消息样式不像 QQ」。
/// </para>
/// </summary>
public sealed class ChatImageBubble : UserControl
{
    /// <summary>图片最长边上限 ≤ 220 DIP；最高 260 DIP（竖图不至于把整屏消息顶飞）。</summary>
    private const double MaxBoxWidth = 220;
    private const double MaxBoxHeight = 260;
    private const double MinBoxSide = 48;

    public static readonly StyledProperty<NodeItem?> NodeProperty =
        AvaloniaProperty.Register<ChatImageBubble, NodeItem?>(nameof(Node));

    /// <summary>请求的缩略图宽度（后端白名单 64/128/320/640；480 由服务端就近到 640 更清晰）。</summary>
    public static readonly StyledProperty<int> RequestWidthProperty =
        AvaloniaProperty.Register<ChatImageBubble, int>(nameof(RequestWidth), 480);

    private readonly Border _frame;
    private readonly Image _image;
    private readonly Panel _fallback;
    private readonly MaterialIcon _icon;
    private CancellationTokenSource? _cts;
    private bool _attached;

    public ChatImageBubble()
    {
        _image = new Image { Stretch = Stretch.Uniform, Opacity = 0 };
        _image.Transitions = new Transitions
        {
            new DoubleTransition { Property = OpacityProperty, Duration = TimeSpan.FromMilliseconds(160) }
        };

        _icon = new MaterialIcon
        {
            Kind = MaterialIconKind.ImageOutline,
            IconSize = 26,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        _fallback = new Panel { Children = { _icon } };

        _frame = new Border
        {
            CornerRadius = new CornerRadius(12),
            ClipToBounds = true,
            Child = new Panel { Children = { _fallback, _image } }
        };
        Content = _frame;

        // 首帧占位尺寸（图片加载完成后按真实宽高比立即替换，不展示假图）。
        Width = 180;
        Height = 120;
    }

    static ChatImageBubble()
    {
        NodeProperty.Changed.AddClassHandler<ChatImageBubble>((view, _) => view.Restart());
        RequestWidthProperty.Changed.AddClassHandler<ChatImageBubble>((view, _) => view.Restart());
    }

    public NodeItem? Node
    {
        get => GetValue(NodeProperty);
        set => SetValue(NodeProperty, value);
    }

    public int RequestWidth
    {
        get => GetValue(RequestWidthProperty);
        set => SetValue(RequestWidthProperty, value);
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        _attached = true;
        Restart();
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnDetachedFromVisualTree(e);
        _attached = false;
        _cts?.Cancel();
        _cts?.Dispose();
        _cts = null;
    }

    private void Restart()
    {
        _cts?.Cancel();
        _cts?.Dispose();
        _cts = null;

        _image.Source = null;
        _image.Opacity = 0;
        _fallback.IsVisible = true;
        _icon.Foreground = Brush("BondTertiary", Brushes.Gray);
        _frame.Background = Brush("BondSurfaceAlt", Brushes.LightGray);

        var node = Node;
        if (!_attached || node is null || !node.HasThumbnail) return;

        var cts = new CancellationTokenSource();
        _cts = cts;
        _ = LoadAsync(node, cts.Token);
    }

    private async Task LoadAsync(NodeItem node, CancellationToken ct)
    {
        var service = App.Services?.GetService(typeof(IThumbnailService)) as IThumbnailService;
        if (service is null) return;

        Bitmap? bitmap;
        try
        {
            bitmap = await service.GetAsync(node, RequestWidth, ct);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch
        {
            bitmap = null;
        }

        if (ct.IsCancellationRequested || !_attached || !ReferenceEquals(node, Node)) return;
        if (bitmap is null) return;
        ApplyBitmap(bitmap);
    }

    /// <summary>按真实像素比例计算显示尺寸（等比）；小图最多放大 1.6 倍，长条图短边保底 48。</summary>
    private void ApplyBitmap(Bitmap bitmap)
    {
        var pixel = bitmap.PixelSize;
        if (pixel.Width <= 0 || pixel.Height <= 0) return;

        var scale = Math.Min(Math.Min(MaxBoxWidth / pixel.Width, MaxBoxHeight / pixel.Height), 1.6);
        var width = pixel.Width * scale;
        var height = pixel.Height * scale;

        if (Math.Min(width, height) < MinBoxSide)
        {
            var boost = MinBoxSide / Math.Min(width, height);
            if (width * boost <= MaxBoxWidth && height * boost <= MaxBoxHeight)
            {
                width *= boost;
                height *= boost;
            }
        }

        Width = Math.Max(24, Math.Round(width));
        Height = Math.Max(24, Math.Round(height));

        _image.Source = bitmap;
        _image.Opacity = 1;
        _fallback.IsVisible = false;
        _icon.Foreground = Brush("BondTertiary", Brushes.Gray);
    }

    private IBrush Brush(string key, IBrush fallback)
        => this.TryFindResource(key, out var value) && value is IBrush brush ? brush : fallback;
}

/// <summary>
/// 聊天页交互（task-3）：文件消息卡点击 = 下载到下载目录。
/// <para>放在 ChatPage.axaml.cs 而不是 Pages.axaml.cs，是因为本任务的改动范围只含本文件；
/// 图片消息的点击仍由气泡内 Button 的 OpenAttachmentCommand 走预览。</para>
/// </summary>
public partial class ChatPage
{
    private void OnDownloadAttachmentPressed(object? sender, PointerPressedEventArgs e)
    {
        if (sender is not Control { DataContext: ChatMessage message }) return;
        if (DataContext is not ChatPageViewModel vm) return;

        var point = e.GetCurrentPoint(sender as Control);
        if (!point.Properties.IsLeftButtonPressed) return;
        if (!message.CanDownload) return;

        if (vm.DownloadAttachmentCommand.CanExecute(message))
        {
            vm.DownloadAttachmentCommand.Execute(message);
        }

        e.Handled = true;
    }
}
