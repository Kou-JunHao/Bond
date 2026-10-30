using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Threading;
using Bond.Services;

namespace Bond.Views.Controls;

/// <summary>
/// PDF 阅读器视图：连续滚动、只渲染视口 ±1 页、缩放（适应宽度 / 百分比）、旋转。
/// 页面渲染在后台线程完成，UI 线程只做像素拷贝到 WriteableBitmap。
/// </summary>
public partial class PdfReaderView : UserControl
{
    private static readonly double[] ZoomLevels = [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0];

    private readonly ObservableCollection<PdfPage> _pages = [];
    private readonly List<(Bitmap Bitmap, DateTime RetiredAt)> _retired = [];
    private PdfRenderer? _doc;
    private double _zoom = 1;
    private int _rotation;
    private double _baseWidth;
    private int _lastFirstIndex = -1;

    public static readonly StyledProperty<PdfRenderer?> DocumentProperty =
        AvaloniaProperty.Register<PdfReaderView, PdfRenderer?>(nameof(Document));

    public PdfReaderView()
    {
        InitializeComponent();
        PagesHost.ItemsSource = _pages;
        Scroller.ScrollChanged += (_, _) => UpdateVisiblePages();
        SizeChanged += (_, _) => ApplyLayoutIfNeeded();
        // 点击阅读器任意位置获取键盘焦点，以便 PageUp/PageDown/↑/↓/Home/End 翻页。
        PointerPressed += (_, _) => Focus();

        // 旧位图延迟 2 秒后在 UI 线程显式释放：Avalonia 12 的 WriteableBitmap 若由 GC
        // 终结器线程释放原生表面，可能与合成器并发访问，触发 coreclr 0xC0000005 崩溃。
        _retireTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        _retireTimer.Tick += (_, _) => DisposeRetiredBitmaps();
        _retireTimer.Start();
    }

    private readonly DispatcherTimer _retireTimer;

    private void Retire(Bitmap? bitmap)
    {
        if (bitmap is null) return;
        _retired.Add((bitmap, DateTime.UtcNow));
    }

    private void DisposeRetiredBitmaps()
    {
        if (_retired.Count == 0) return;
        var cutoff = DateTime.UtcNow.AddSeconds(-2);
        for (int i = _retired.Count - 1; i >= 0; i--)
        {
            if (_retired[i].RetiredAt > cutoff) continue;
            try
            {
                _retired[i].Bitmap.Dispose();
            }
            catch
            {
                // 释放失败交给 GC，不影响阅读。
            }

            _retired.RemoveAt(i);
        }
    }

    public PdfRenderer? Document
    {
        get => GetValue(DocumentProperty);
        set => SetValue(DocumentProperty, value);
    }

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == DocumentProperty)
            ReloadDocument(change.GetNewValue<PdfRenderer?>());
    }

    private void ReloadDocument(PdfRenderer? document)
    {
        _doc = document;
        _rotation = 0;
        _lastFirstIndex = -1;
        foreach (var p in _pages)
        {
            // 不 Dispose 旧位图：Avalonia 12 合成器可能仍持有它的原生表面，释放会导致 coreclr 原生崩溃（0xC0000005）。
            // 只解除引用，由 Retire 延迟到 UI 线程安全时机释放。
            Retire(p.Bitmap);
            p.Bitmap = null;
            p.IsLoading = false;
        }
        // _pages 是 ItemsControl 绑定的 ObservableCollection：Clear() 的 Reset 通知会让虚拟化容器残留旧页，
        // 换文档时表现为「旧页仍可见/页数错乱」。用 CollectionSync 逐项移除，保证 UI 与数据一致。
        Bond.Core.CollectionSync.Apply(_pages, []);

        if (document is null || !document.IsOpen || document.PageCount <= 0)
        {
            ZoomText.Text = "100%";
            PageIndicator.Text = string.Empty;
            return;
        }

        for (int i = 0; i < document.PageCount; i++)
            _pages.Add(new PdfPage(i + 1));

        _zoom = 1;
        ApplyLayout();
        Scroller.Offset = new Vector(0, 0);
        // 默认「适应宽度」：页面宽度 = 阅读区宽度 - 内边距，宽窗口下不需要左右滚动。
        ZoomText.Text = $"{Math.Round(_zoom * 100)}%";
        UpdatePageIndicator();
        Dispatcher.UIThread.Post(UpdateVisiblePages, DispatcherPriority.Loaded);
    }

    private void ApplyLayoutIfNeeded()
    {
        if (_doc is null || _pages.Count == 0) return;
        double baseWidth = Math.Clamp(Scroller.Bounds.Width - 64, 320, 1200);
        if (baseWidth <= 320 && Scroller.Bounds.Width <= 0) return;
        if (Math.Abs(baseWidth - _baseWidth) < 2) return;
        _baseWidth = baseWidth;
        ClearBitmaps();
        ApplyLayout();
        Dispatcher.UIThread.Post(UpdateVisiblePages, DispatcherPriority.Loaded);
    }

    private void ApplyLayout()
    {
        if (_doc is null || _pages.Count == 0) return;
        if (_baseWidth <= 0)
            _baseWidth = Math.Clamp(Scroller.Bounds.Width - 64, 320, 1200);

        double w = _baseWidth * _zoom;
        double h = w * _doc.PageAspect;
        if (_rotation is 90 or 270) (w, h) = (h, w);

        foreach (var p in _pages)
        {
            p.DisplayWidth = w;
            p.DisplayHeight = h;
        }
    }

    private void UpdatePageIndicator()
    {
        if (_doc is null || _pages.Count == 0)
        {
            PageIndicator.Text = string.Empty;
            return;
        }

        int first = _lastFirstIndex >= 0 ? _lastFirstIndex : 0;
        PageIndicator.Text = $"第 {Math.Min(first + 1, _pages.Count)} / {_pages.Count} 页";
    }

    private int ComputeFirstVisibleIndex()
    {
        if (_pages.Count == 0) return 0;
        double row = _pages[0].DisplayHeight + 14;
        if (row <= 0) return 0;
        return Math.Clamp((int)Math.Floor(Scroller.Offset.Y / row), 0, _pages.Count - 1);
    }

    private void UpdateVisiblePages()
    {
        if (_doc is null || _pages.Count == 0) return;

        int first = ComputeFirstVisibleIndex();
        if (first != _lastFirstIndex)
        {
            _lastFirstIndex = first;
            UpdatePageIndicator();
        }

        int from = Math.Max(0, first - 1);
        int to = Math.Min(_pages.Count - 1, first + 2);
        for (int i = from; i <= to; i++)
            EnsureRendered(_pages[i]);

        TrimFarBitmaps(first);
    }

    private void EnsureRendered(PdfPage page)
    {
        if (_doc is null || page.HasBitmap || page.IsLoading) return;

        page.IsLoading = true;
        int index = page.PageNumber - 1;
        bool rotated = _rotation is 90 or 270;
        double unscaledWidth = rotated ? page.DisplayHeight : page.DisplayWidth;
        double scaling = TopLevel.GetTopLevel(this)?.RenderScaling ?? 1.0;
        int pixelWidth = Math.Max(200, (int)Math.Round(unscaledWidth * scaling));
        int rotation = _rotation;
        var doc = _doc;

        _ = Task.Run(() =>
        {
            var raw = doc.RenderPage(index, pixelWidth);
            if (raw is null)
            {
                Dispatcher.UIThread.Post(() => page.IsLoading = false);
                return;
            }

            var (bytes, w, h) = raw.Value;
            if (rotation != 0)
                (bytes, w, h) = RotateBgra(bytes, w, h, rotation);

            Dispatcher.UIThread.Post(() =>
            {
                page.IsLoading = false;
                try
                {
                    var bitmap = new WriteableBitmap(
                        new PixelSize(w, h), new Vector(96, 96),
                        PixelFormat.Bgra8888, AlphaFormat.Opaque);
                    using (var fb = bitmap.Lock())
                    {
                        int copy = Math.Min(bytes.Length, fb.RowBytes * h);
                        Marshal.Copy(bytes, 0, fb.Address, copy);
                    }
                    Retire(page.Bitmap);
                    page.Bitmap = bitmap;
                }
                catch
                {
                    // 渲染/拷入失败：保持占位，不影响其它页。
                }
            });
        });
    }

    private void TrimFarBitmaps(int first)
    {
        const int KeepBefore = 3;
        const int KeepAfter = 4;
        foreach (var page in _pages)
        {
            if (!page.HasBitmap) continue;
            int index = page.PageNumber - 1;
            if (index < first - KeepBefore || index > first + KeepAfter)
            {
                // 同 ReloadDocument：只解除引用，延迟到 UI 线程释放（避免合成器仍在使用时释放原生表面）。
                Retire(page.Bitmap);
                page.Bitmap = null;
            }
        }
    }

    private void ClearBitmaps()
    {
        foreach (var page in _pages)
        {
            // 只解除引用，延迟到 UI 线程释放（见 ReloadDocument 注释）。
            Retire(page.Bitmap);
            page.Bitmap = null;
            page.IsLoading = false;
        }
    }

    private void ChangeZoom(double newZoom, string? label = null)
    {
        if (_doc is null || _pages.Count == 0) return;
        newZoom = Math.Clamp(newZoom, ZoomLevels[0], ZoomLevels[^1]);
        if (Math.Abs(newZoom - _zoom) < 0.001)
        {
            if (label is not null) ZoomText.Text = label;
            return;
        }

        int anchor = ComputeFirstVisibleIndex();
        _zoom = newZoom;
        ClearBitmaps();
        ApplyLayout();
        ZoomText.Text = label ?? $"{Math.Round(_zoom * 100)}%";

        double row = _pages[0].DisplayHeight + 14;
        Scroller.Offset = new Vector(Scroller.Offset.X, Math.Max(0, anchor * row));
        Dispatcher.UIThread.Post(UpdateVisiblePages, DispatcherPriority.Loaded);
    }

    private void OnZoomIn(object? sender, RoutedEventArgs e)
    {
        double next = ZoomLevels.FirstOrDefault(z => z > _zoom + 0.001);
        if (next <= 0) next = ZoomLevels[^1];
        ChangeZoom(next);
    }

    private void OnZoomOut(object? sender, RoutedEventArgs e)
    {
        double prev = ZoomLevels.LastOrDefault(z => z < _zoom - 0.001);
        if (prev <= 0) prev = ZoomLevels[0];
        ChangeZoom(prev);
    }

    private void OnFitWidth(object? sender, RoutedEventArgs e)
    {
        ChangeZoom(1);
        Scroller.Offset = new Vector(Scroller.Offset.X, 0);
    }

    /// <summary>适应页面：整页高度落在阅读区高度内（宽度方向不超出适应宽度）。</summary>
    private void OnFitPage(object? sender, RoutedEventArgs e)
    {
        if (_doc is null || _pages.Count == 0) return;
        double baseWidth = _baseWidth > 0
            ? _baseWidth
            : Math.Clamp(Scroller.Bounds.Width - 64, 320, 1200);
        double pageHeight = _rotation is 90 or 270
            ? baseWidth
            : baseWidth * _doc.PageAspect;

        double viewport = Math.Max(240, Scroller.Bounds.Height - 28);
        double target = pageHeight > 0 ? Math.Min(1, viewport / pageHeight) : 1;
        ChangeZoom(target, "适应页面");
        Scroller.Offset = new Vector(Scroller.Offset.X, 0);
    }

    private void OnRotate(object? sender, RoutedEventArgs e)
    {
        if (_doc is null || _pages.Count == 0) return;
        int anchor = ComputeFirstVisibleIndex();
        _rotation = (_rotation + 90) % 360;
        ClearBitmaps();
        ApplyLayout();
        ZoomText.Text = $"{Math.Round(_zoom * 100)}%";
        double row = _pages[0].DisplayHeight + 14;
        Scroller.Offset = new Vector(Scroller.Offset.X, Math.Max(0, anchor * row));
        Dispatcher.UIThread.Post(UpdateVisiblePages, DispatcherPriority.Loaded);
    }

    private void OnJumpKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        TryJump(JumpBox.Text);
        e.Handled = true;
    }

    private void TryJump(string? text)
    {
        if (_doc is null || _pages.Count == 0) return;
        if (!int.TryParse(text?.Trim(), out int number)) return;
        ScrollToPage(number - 1);
        JumpBox.Text = string.Empty;
    }

    /// <summary>滚动到指定页（0 基），并立即更新渲染范围与页码指示。</summary>
    private void ScrollToPage(int zeroBasedIndex)
    {
        if (_pages.Count == 0) return;
        int index = Math.Clamp(zeroBasedIndex, 0, _pages.Count - 1);
        double row = _pages[0].DisplayHeight + 14;
        Scroller.Offset = new Vector(Scroller.Offset.X, Math.Max(0, index * row));
        UpdateVisiblePages();
    }

    /// <summary>键盘翻页：PageUp/PageDown 整页、↑/↓ 小幅、Home/End 首尾。</summary>
    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (_doc is null || _pages.Count == 0)
        {
            base.OnKeyDown(e);
            return;
        }

        int first = ComputeFirstVisibleIndex();
        double row = _pages[0].DisplayHeight + 14;
        double step = Math.Max(48, row * 0.08);
        switch (e.Key)
        {
            case Key.PageDown:
                ScrollToPage(first + 1);
                e.Handled = true;
                break;
            case Key.PageUp:
                ScrollToPage(first - 1);
                e.Handled = true;
                break;
            case Key.Down:
                Scroller.Offset = new Vector(Scroller.Offset.X, Scroller.Offset.Y + step);
                UpdateVisiblePages();
                e.Handled = true;
                break;
            case Key.Up:
                Scroller.Offset = new Vector(Scroller.Offset.X, Math.Max(0, Scroller.Offset.Y - step));
                UpdateVisiblePages();
                e.Handled = true;
                break;
            case Key.Home:
                ScrollToPage(0);
                e.Handled = true;
                break;
            case Key.End:
                ScrollToPage(_pages.Count - 1);
                e.Handled = true;
                break;
            default:
                base.OnKeyDown(e);
                break;
        }
    }

    /// <summary>BGRA32 像素按顺时针角度旋转。</summary>
    private static (byte[] Bytes, int Width, int Height) RotateBgra(byte[] src, int w, int h, int angle)
    {
        angle = ((angle % 360) + 360) % 360;
        if (angle == 0) return (src, w, h);

        int nw = angle == 180 ? w : h;
        int nh = angle == 180 ? h : w;
        var dst = new byte[src.Length];
        int span = w * 4;

        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                int si = y * span + x * 4;
                int dx, dy;
                if (angle == 90)
                {
                    dx = h - 1 - y;
                    dy = x;
                }
                else if (angle == 180)
                {
                    dx = w - 1 - x;
                    dy = h - 1 - y;
                }
                else
                {
                    dx = y;
                    dy = w - 1 - x;
                }
                int di = dy * nw * 4 + dx * 4;
                dst[di] = src[si];
                dst[di + 1] = src[si + 1];
                dst[di + 2] = src[si + 2];
                dst[di + 3] = src[si + 3];
            }
        }

        return (dst, nw, nh);
    }
}
