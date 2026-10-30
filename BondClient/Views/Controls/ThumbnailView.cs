using System;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Bond.Models;
using Bond.Services;
using Material.Icons;
using Material.Icons.Avalonia;

namespace Bond.Views.Controls;

/// <summary>
/// 缩略图单元格：有缩略图显示图（180ms 淡入），没有（204/不支持/失败）回退类型图标。
/// 挂载时请求、卸载/节点变化时取消（滚出视口即取消），解码在服务层后台线程完成。
/// </summary>
public sealed class ThumbnailView : UserControl
{
    public static readonly StyledProperty<NodeItem?> NodeProperty =
        AvaloniaProperty.Register<ThumbnailView, NodeItem?>(nameof(Node));

    public static readonly StyledProperty<double> SizeProperty =
        AvaloniaProperty.Register<ThumbnailView, double>(nameof(Size), 32);

    public static readonly StyledProperty<int> RequestWidthProperty =
        AvaloniaProperty.Register<ThumbnailView, int>(nameof(RequestWidth), 128);

    /// <summary>非正方形盒子宽度（平铺卡片用）；&lt;=0 表示沿用 Size 的方形。</summary>
    public static readonly StyledProperty<double> BoxWidthProperty =
        AvaloniaProperty.Register<ThumbnailView, double>(nameof(BoxWidth), 0d);

    /// <summary>非正方形盒子高度（平铺卡片用）；&lt;=0 表示沿用 Size 的方形。</summary>
    public static readonly StyledProperty<double> BoxHeightProperty =
        AvaloniaProperty.Register<ThumbnailView, double>(nameof(BoxHeight), 0d);

    /// <summary>圆角覆盖（&lt;0 表示自动：小方块按 28%、矩形 8）。</summary>
    public static readonly StyledProperty<double> RadiusProperty =
        AvaloniaProperty.Register<ThumbnailView, double>(nameof(Radius), -1d);

    /// <summary>回退类型图标尺寸覆盖（&lt;=0 表示自动 = min(w,h)*0.44；文件夹会再放大 1.18 倍）。</summary>
    public static readonly StyledProperty<double> FallbackIconSizeProperty =
        AvaloniaProperty.Register<ThumbnailView, double>(nameof(FallbackIconSize), 0d);

    /// <summary>回退态是否铺灰色底板；平铺网格要 False（透明背景上直接画类型色图标）。</summary>
    public static readonly StyledProperty<bool> FallbackPlateProperty =
        AvaloniaProperty.Register<ThumbnailView, bool>(nameof(FallbackPlate), true);

    private readonly Border _placeholder;
    private readonly Border _clip;
    private readonly MaterialIcon _icon;
    private readonly Image _image;
    private CancellationTokenSource? _cts;
    private bool _attached;

    public ThumbnailView()
    {
        _placeholder = new Border();

        _icon = new MaterialIcon
        {
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };

        _image = new Image { Stretch = Stretch.UniformToFill, Opacity = 0 };
        _image.Transitions = new Transitions
        {
            new DoubleTransition { Property = OpacityProperty, Duration = TimeSpan.FromMilliseconds(180) }
        };

        _clip = new Border { ClipToBounds = true, IsVisible = false };
        _clip.Child = _image;

        Content = new Panel { Children = { _placeholder, _icon, _clip } };
    }

    static ThumbnailView()
    {
        NodeProperty.Changed.AddClassHandler<ThumbnailView>((v, _) =>
        {
            v.Restart();
            v.ApplySize();
        });
        SizeProperty.Changed.AddClassHandler<ThumbnailView>((v, _) => v.ApplySize());
        BoxWidthProperty.Changed.AddClassHandler<ThumbnailView>((v, _) => v.ApplySize());
        BoxHeightProperty.Changed.AddClassHandler<ThumbnailView>((v, _) => v.ApplySize());
        RadiusProperty.Changed.AddClassHandler<ThumbnailView>((v, _) => v.ApplySize());
        FallbackIconSizeProperty.Changed.AddClassHandler<ThumbnailView>((v, _) => v.ApplySize());
    }

    public NodeItem? Node
    {
        get => GetValue(NodeProperty);
        set => SetValue(NodeProperty, value);
    }

    public double Size
    {
        get => GetValue(SizeProperty);
        set => SetValue(SizeProperty, value);
    }

    public int RequestWidth
    {
        get => GetValue(RequestWidthProperty);
        set => SetValue(RequestWidthProperty, value);
    }

    public double BoxWidth
    {
        get => GetValue(BoxWidthProperty);
        set => SetValue(BoxWidthProperty, value);
    }

    public double BoxHeight
    {
        get => GetValue(BoxHeightProperty);
        set => SetValue(BoxHeightProperty, value);
    }

    public double Radius
    {
        get => GetValue(RadiusProperty);
        set => SetValue(RadiusProperty, value);
    }

    public double FallbackIconSize
    {
        get => GetValue(FallbackIconSizeProperty);
        set => SetValue(FallbackIconSizeProperty, value);
    }

    public bool FallbackPlate
    {
        get => GetValue(FallbackPlateProperty);
        set => SetValue(FallbackPlateProperty, value);
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        _attached = true;
        ApplySize();
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

    private void ApplySize()
    {
        var fallback = Math.Max(12, Size);
        var w = BoxWidth > 0 ? BoxWidth : fallback;
        var h = BoxHeight > 0 ? BoxHeight : fallback;
        Width = w;
        Height = h;

        // 小方块（列表 32px）用按比例半径；矩形盒子（平铺）用固定 8，贴合网格圆角语言；
        // 平铺网格显式传 Radius=8，保证和缩略图区一致。
        var radius = Radius >= 0
            ? new CornerRadius(Radius)
            : w == h ? new CornerRadius(Math.Round(w * 0.28)) : new CornerRadius(8);
        _placeholder.CornerRadius = radius;
        _clip.CornerRadius = radius;

        // 图标尺寸：显式指定优先（平铺 44）；文件夹再放大一点点，和文件一眼区分。
        var iconSize = FallbackIconSize > 0 ? FallbackIconSize : Math.Min(w, h) * 0.44;
        if (Node?.IsFolder == true) iconSize *= 1.18;
        _icon.IconSize = iconSize;
        _icon.HorizontalAlignment = HorizontalAlignment.Center;
        _icon.VerticalAlignment = VerticalAlignment.Center;
    }

    private void ResetVisual(NodeItem? node)
    {
        _image.Source = null;
        _image.Opacity = 0;
        _clip.IsVisible = false;
        _placeholder.IsVisible = true;

        _icon.IsVisible = true;
        _icon.Opacity = 1;
        _icon.Kind = node?.Icon ?? MaterialIconKind.FileOutline;
        _icon.Foreground = node?.IconBrush ?? Brushes.Gray;
        _placeholder.Background = FallbackPlate
            ? node?.IconBackground ?? ResBrush("BondSurfaceAlt", Brushes.LightGray)
            : Brushes.Transparent;

        // PDF 按「纸页」呈现：白底 + 细边框 + 等比留白。
        // 空白页/纯文字页以前会被 UniformToFill 拉满成一块白斑，看起来像破图；这样它读起来是文档。
        var isPdf = node?.IsPdf == true;
        _image.Stretch = isPdf ? Stretch.Uniform : Stretch.UniformToFill;
        _clip.Background = isPdf ? ResBrush("BondSurface", Brushes.White) : Brushes.Transparent;
        _clip.BorderBrush = isPdf ? ResBrush("BondBorder", Brushes.LightGray) : Brushes.Transparent;
        _clip.BorderThickness = isPdf ? new Thickness(1) : default;
        // 极轻投影：白页从卡片底上"浮"起来，一眼看出是一页纸，而不是空卡/加载失败。
        _clip.BoxShadow = isPdf ? BoxShadows.Parse("0 2 6 0 #14000000") : default;
    }

    private IBrush ResBrush(string key, IBrush fallback)
        => this.TryFindResource(key, out var value) && value is IBrush brush ? brush : fallback;

    private void Restart()
    {
        _cts?.Cancel();
        _cts?.Dispose();
        _cts = null;

        var node = Node;
        ResetVisual(node);

        if (!_attached || node is null || !node.HasThumbnail) return;

        // 有缩略图类型：先显示占位 + 淡化图标，等图到了再淡入
        _icon.Opacity = 0.45;
        _placeholder.Background = FallbackPlate ? ResBrush("BondSurfaceAlt", Brushes.LightGray) : Brushes.Transparent;

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

        if (ct.IsCancellationRequested || !_attached || !ReferenceEquals(node, Node)) return;

        if (bitmap is null)
        {
            // 204 / 不支持：回退类型图标，绝不显示破图
            _placeholder.IsVisible = false;
            _icon.IsVisible = true;
            _icon.Opacity = 1;
            return;
        }

        _image.Source = bitmap;
        _clip.IsVisible = true;
        _placeholder.IsVisible = false;
        _icon.IsVisible = false;
        _image.Opacity = 1;
    }
}
