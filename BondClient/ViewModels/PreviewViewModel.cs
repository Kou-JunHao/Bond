using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Bond.Models;
using Bond.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Bond.ViewModels;

/// <summary>
/// 预览面板（覆盖层）ViewModel：图片缩放/平移/上下张、PDF 首页、文本只读、视频内嵌播放（LibVLC 帧回调）、
/// 其他类型元信息。libvlc 缺失时视频退化为"用系统播放器打开"。
/// </summary>
public partial class PreviewViewModel : ObservableObject
{
    private readonly IPreviewService _preview;
    private readonly IFileService _files;
    private readonly INotificationCenter _notify;

    private readonly List<NodeItem> _siblings = [];
    private int _index = -1;
    private CancellationTokenSource? _cts;

    private VideoPlayback? _video;
    private WriteableBitmap? _videoBitmap;
    private int _videoStride;
    private bool _syncingPosition;
    private float _speedRate = 1f;

    /// <summary>PDF 本地渲染器（关闭面板/切换文件时 Dispose，释放 PDFium 文档）。</summary>
    private PdfRenderer? _pdf;

    public PreviewViewModel(IPreviewService preview, IFileService files, INotificationCenter notify)
    {
        _preview = preview;
        _files = files;
        _notify = notify;
    }

    [ObservableProperty]
    private bool _isOpen;

    [ObservableProperty]
    private bool _isLoading;

    [ObservableProperty]
    private bool _hasError;

    [ObservableProperty]
    private string _errorText = string.Empty;

    [ObservableProperty]
    private string _title = string.Empty;

    [ObservableProperty]
    private string _metaText = string.Empty;

    [ObservableProperty]
    private string _kindText = string.Empty;

    [ObservableProperty]
    private Bitmap? _imageSource;

    [ObservableProperty]
    private string _textContent = string.Empty;

    [ObservableProperty]
    private bool _isTruncated;

    [ObservableProperty]
    private string _noteText = string.Empty;

    [ObservableProperty]
    private double _zoom = 1;

    [ObservableProperty]
    private double _offsetX;

    [ObservableProperty]
    private double _offsetY;

    [ObservableProperty]
    private bool _isImagePreview;

    [ObservableProperty]
    private bool _isTextPreview;

    [ObservableProperty]
    private bool _isPdfPreview;

    [ObservableProperty]
    private bool _isVideoPreview;

    [ObservableProperty]
    private bool _isOtherPreview;

    /// <summary>PDF 本地渲染器；非 null 且处于 PDF 预览时显示阅读器视图。</summary>
    [ObservableProperty]
    private PdfRenderer? _pdfDocument;

    // ── 办公文档（task-60）：服务端转 PDF → 本地阅读器 ──
    [ObservableProperty]
    private bool _isOfficePreview;

    [ObservableProperty]
    private bool _isOfficeLoading;

    [ObservableProperty]
    private string _officeStatusText = string.Empty;

    [ObservableProperty]
    private bool _canGoPrev;

    [ObservableProperty]
    private bool _canGoNext;

    [ObservableProperty]
    private string _positionText = string.Empty;

    // ── 视频播放状态 ──

    [ObservableProperty]
    private WriteableBitmap? _videoFrame;

    [ObservableProperty]
    private bool _isVideoAvailable;

    [ObservableProperty]
    private bool _isVideoLoading;

    [ObservableProperty]
    private string _videoStatusText = string.Empty;

    [ObservableProperty]
    private bool _isPlaying;

    [ObservableProperty]
    private double _positionValue;

    [ObservableProperty]
    private string _timeText = "00:00";

    [ObservableProperty]
    private string _durationText = "00:00";

    [ObservableProperty]
    private double _volume = 80;

    [ObservableProperty]
    private string _speedText = "1.0×";

    /// <summary>新帧已写入位图（VLC 线程），视图侧转发到 UI 线程重绘。</summary>
    public event Action? VideoFrameUpdated;

    public NodeItem? Current => _index >= 0 && _index < _siblings.Count ? _siblings[_index] : null;

    /// <summary>当前是否有可显示的图片/PDF 页图。</summary>
    public bool HasImage => ImageSource is not null;

    /// <summary>只有"不支持的类型"才显示元信息占位面板（视频有自己的播放面板）。</summary>
    public bool ShowInfoPanel => IsOtherPreview;

    partial void OnImageSourceChanged(Bitmap? value) => OnPropertyChanged(nameof(HasImage));

    partial void OnIsOtherPreviewChanged(bool value) => OnPropertyChanged(nameof(ShowInfoPanel));

    /// <summary>PDF 本地渲染可用时显示阅读器（连续滚动/缩放）；办公文档转 PDF 后同样复用。</summary>
    public bool IsPdfReaderAvailable => (IsPdfPreview || IsOfficePreview) && PdfDocument is not null;

    partial void OnIsPdfPreviewChanged(bool value) => OnPropertyChanged(nameof(IsPdfReaderAvailable));

    partial void OnIsOfficePreviewChanged(bool value) => OnPropertyChanged(nameof(IsPdfReaderAvailable));

    partial void OnPdfDocumentChanged(PdfRenderer? value) => OnPropertyChanged(nameof(IsPdfReaderAvailable));

    /// <summary>视频面板的"不可用/回退"提示态。</summary>
    public bool IsVideoUnavailable => IsVideoPreview && !IsVideoAvailable && !IsVideoLoading;

    /// <summary>已进入视频预览、首帧还没到：显示"正在缓冲"。</summary>
    public bool IsVideoBuffering => IsVideoPreview && IsVideoLoading;

    partial void OnIsVideoPreviewChanged(bool value)
    {
        OnPropertyChanged(nameof(IsVideoUnavailable));
        OnPropertyChanged(nameof(IsVideoBuffering));
    }

    partial void OnIsVideoAvailableChanged(bool value) => OnPropertyChanged(nameof(IsVideoUnavailable));

    partial void OnIsVideoLoadingChanged(bool value)
    {
        OnPropertyChanged(nameof(IsVideoUnavailable));
        OnPropertyChanged(nameof(IsVideoBuffering));
    }


    partial void OnVolumeChanged(double value) => _video?.SetVolume((int)Math.Round(value));

    partial void OnPositionValueChanged(double value)
    {
        if (_syncingPosition) return;
        _video?.Seek((float)value);
    }

    private bool IsPdfFirstPage => Current?.IsPdf == true;

    public async Task OpenAsync(NodeItem? node, IEnumerable<NodeItem>? siblings = null)
    {
        if (node is null) return;

        var list = (siblings ?? [node])
            .Where(n => n.Kind != NodeKind.Folder)
            .ToList();
        if (list.All(n => n.Id != node.Id)) list.Insert(0, node);

        _siblings.Clear();
        _siblings.AddRange(list);
        _index = Math.Max(0, _siblings.FindIndex(n => n.Id == node.Id));

        IsOpen = true;
        await LoadCurrentAsync();
    }

    [RelayCommand]
    private void Close()
    {
        _cts?.Cancel();
        IsOpen = false;
        ImageSource = null;
        TextContent = string.Empty;
        DisposeVideo();
        DisposePdf();
        _siblings.Clear();
        _index = -1;
    }

    [RelayCommand]
    private void TogglePlay() => _video?.TogglePause();

    /// <summary>倍速循环：1 → 1.25 → 1.5 → 2 → 0.5 → 1。</summary>
    [RelayCommand]
    private void CycleSpeed()
    {
        _speedRate = _speedRate switch
        {
            < 1.2f => 1.25f,
            < 1.4f => 1.5f,
            < 1.8f => 2f,
            < 2.5f => 0.5f,
            _ => 1f
        };
        _video?.SetRate(_speedRate);
        SpeedText = _speedRate.ToString("0.0#", System.Globalization.CultureInfo.InvariantCulture) + "×";
    }

    [RelayCommand]
    private async Task NextAsync()
    {
        if (_index + 1 >= _siblings.Count) return;
        _index++;
        await LoadCurrentAsync();
    }

    [RelayCommand]
    private async Task PrevAsync()
    {
        if (_index <= 0) return;
        _index--;
        await LoadCurrentAsync();
    }

    [RelayCommand]
    private void ResetZoom() => ResetView();

    /// <summary>复位缩放与平移（按钮 / 双击 / 外部调用共用）。</summary>
    public void ResetView()
    {
        Zoom = 1;
        OffsetX = 0;
        OffsetY = 0;
    }

    [RelayCommand]
    private async Task DownloadAsync()
    {
        if (Current is not { } node) return;

        var saved = await _files.DownloadAsync(node, LocalPrefs.Current.DownloadDirectory);
        _notify.Notify(saved is null ? $"下载失败：{node.Name}" : $"已下载到 {saved}",
            saved is null ? NoticeKind.Error : NoticeKind.Success);
    }

    [RelayCommand]
    private async Task OpenExternalAsync()
    {
        if (Current is not { } node) return;

        var path = await _preview.DownloadToTempAsync(node);
        if (path is null)
        {
            _notify.Notify("打开失败：无法获取文件", NoticeKind.Error);
            return;
        }

        try
        {
            Process.Start(new ProcessStartInfo { FileName = path, UseShellExecute = true });
        }
        catch
        {
            _notify.Notify("没有可用的默认打开程序", NoticeKind.Warning);
        }
    }

    private async Task LoadCurrentAsync()
    {
        _cts?.Cancel();
        _cts?.Dispose();
        var cts = new CancellationTokenSource();
        _cts = cts;

        var node = Current;
        if (node is null) return;

        ResetState();
        Title = node.Name;
        KindText = node.KindText;
        MetaText = $"{node.SizeText} · {node.ModifiedText}";
        CanGoPrev = _index > 0;
        CanGoNext = _index >= 0 && _index + 1 < _siblings.Count;
        // 这个计数是「预览面板内第几个文件」，与阅读器里的「第 N / M 页」含义不同，
        // 统一加「文件」前缀，避免用户以为两个数字互相矛盾。
        PositionText = _siblings.Count > 1 ? $"文件 {_index + 1} / {_siblings.Count}" : string.Empty;

        IsLoading = true;
        try
        {
            if (node.IsImage)
            {
                IsImagePreview = true;
                await LoadImageAsync(node, cts.Token);
            }
            else if (node.IsPdf)
            {
                IsPdfPreview = true;
                await LoadPdfAsync(node, cts.Token);
            }
            else if (node.IsOffice)
            {
                IsOfficePreview = true;
                await LoadOfficeAsync(node, cts.Token);
            }
            else if (node.IsVideo)
            {
                IsVideoPreview = true;
                await LoadVideoAsync(node, cts.Token);
            }
            else if (node.IsText)
            {
                IsTextPreview = true;
                await LoadTextAsync(node, cts.Token);
            }
            else
            {
                IsOtherPreview = true;
                NoteText = "该类型暂不支持内嵌预览，可下载或用默认程序打开。";
            }
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch (Exception ex)
        {
            ShowError(ex.Message);
        }
        finally
        {
            if (!cts.IsCancellationRequested) IsLoading = false;
        }
    }

    /// <summary>
    /// 办公文档预览：`preview-url` 的 officePdfUrl → 下载转好的 PDF → 本地 PdfRenderer 渲染。
    /// 首次需要服务端转换，给明确进度文案（不显示假百分比）；20 秒后提示可先下载。
    /// </summary>
    private async Task LoadOfficeAsync(NodeItem node, CancellationToken ct)
    {
        IsOfficeLoading = true;
        OfficeStatusText = "正在转换，首次打开可能需要几秒…";

        var slowHint = Task.Delay(TimeSpan.FromSeconds(20), ct).ContinueWith(t =>
        {
            if (!t.IsCanceled && !ct.IsCancellationRequested)
            {
                OfficeStatusText = "转换较慢，可先下载后用本地程序查看";
            }
        }, TaskScheduler.FromCurrentSynchronizationContext());

        try
        {
            var (bytes, status) = await _preview.GetOfficePdfAsync(node, ct);
            ct.ThrowIfCancellationRequested();

            if (bytes is { Length: > 0 })
            {
                var renderer = new PdfRenderer(bytes);
                var adopted = false;
                try
                {
                    var error = string.Empty;
                    var opened = await Task.Run(() => renderer.Open(out error), ct);
                    ct.ThrowIfCancellationRequested();

                    if (opened)
                    {
                        AdoptPdfDocument(renderer);
                        adopted = true;
                        NoteText = $"{renderer.PageCount} 页 · 已转换预览（首次转换后走本地缓存）";
                        OfficeStatusText = string.Empty;
                        IsLoading = false; // 首屏已出图，收起顶部橙色加载条
                        return;
                    }
                }
                finally
                {
                    // task-95：同 LoadPdfAsync —— 取消时也要释放未被采纳的 renderer。
                    if (!adopted) renderer.Dispose();
                }

                SetOfficeFallback("PDF 渲染失败，请下载后用本地程序打开。");
                return;
            }

            switch (status)
            {
                case 0:
                    SetOfficeFallback("该类型暂不支持内嵌预览，可下载或用默认程序打开。");
                    break;
                case 415:
                    SetOfficeFallback("该文档无法转换预览，请下载后用本地程序打开。");
                    break;
                case 503:
                    SetOfficeFallback("文档转换服务暂时不可用，请稍后重试，或下载后用本地程序打开。");
                    break;
                default:
                    SetOfficeFallback($"文档转换失败（HTTP {status}），可下载后用本地程序打开。");
                    break;
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            SetOfficeFallback("文档预览失败：" + ex.Message);
        }
        finally
        {
            IsOfficeLoading = false;
            await slowHint;
        }
    }

    private void SetOfficeFallback(string note)
    {
        NoteText = note;
        IsOtherPreview = true;
        OfficeStatusText = string.Empty;
    }

    private async Task LoadImageAsync(NodeItem node, CancellationToken ct)
    {
        if (node.Size > 40L * 1024 * 1024)
        {
            ShowError("图片过大（>40MB），请用默认程序打开");
            return;
        }

        var (bytes, _) = await _preview.GetContentAsync(node, null, ct);
        ct.ThrowIfCancellationRequested();

        if (bytes is null || bytes.Length == 0)
        {
            ShowError(PreviewUnavailableText(node));
            return;
        }

        var bitmap = await Task.Run(() =>
        {
            try
            {
                using var ms = new System.IO.MemoryStream(bytes);
                return new Bitmap(ms);
            }
            catch
            {
                return null;
            }
        }, ct);

        ct.ThrowIfCancellationRequested();

        if (bitmap is null)
        {
            ShowError("图片解码失败，请下载后查看");
            return;
        }

        ImageSource = bitmap;
        NoteText = $"{bitmap.PixelSize.Width} × {bitmap.PixelSize.Height}";
    }

    private async Task LoadPdfAsync(NodeItem node, CancellationToken ct)
    {
        // 首选：本地 PDFium 渲染（Docnet.Core）→ 完整阅读器（连续滚动/缩放/旋转）。
        // 超过 120MB、字节不完整或渲染打开失败时，回退到服务端首页图，保证不白屏。
        const long Limit = 120L * 1024 * 1024;
        try
        {
            var (bytes, truncated) = await _preview.GetContentAsync(node, Limit, ct);
            ct.ThrowIfCancellationRequested();

            if (bytes is { Length: > 0 } && !truncated)
            {
                var renderer = new PdfRenderer(bytes);
                var adopted = false;
                try
                {
                    var error = string.Empty;
                    bool opened = await Task.Run(() => renderer.Open(out error), ct);
                    ct.ThrowIfCancellationRequested();

                    if (opened)
                    {
                        AdoptPdfDocument(renderer);
                        adopted = true;
                        NoteText = $"{renderer.PageCount} 页 · 可滚动阅读";
                        return;
                    }
                }
                finally
                {
                    // task-95：关闭预览会取消 ct，此时 renderer 可能已经 Open 成功但还没被采纳；
                    // 不释放的话 Docnet 原生对象会泄漏到 GC，最终在终结器线程上做 PDFium teardown → 原生崩溃。
                    if (!adopted) renderer.Dispose();
                }
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
            // 本地渲染失败 → 回退服务端首页图。
        }

        await LoadPdfFallbackAsync(node, ct);
    }

    /// <summary>服务端首页图回退（后端只有单页预览接口）。</summary>
    private async Task LoadPdfFallbackAsync(NodeItem node, CancellationToken ct)
    {
        var bytes = await _preview.GetThumbnailBytesAsync(node, 640, ct);
        ct.ThrowIfCancellationRequested();

        if (bytes is null)
        {
            NoteText = "服务端暂未提供该 PDF 的页图，可下载或用默认程序打开。";
            return;
        }

        var bitmap = await Task.Run(() =>
        {
            try
            {
                using var ms = new System.IO.MemoryStream(bytes);
                return new Bitmap(ms);
            }
            catch
            {
                return null;
            }
        }, ct);

        ct.ThrowIfCancellationRequested();

        if (bitmap is null)
        {
            ShowError("PDF 页图解码失败，请下载后查看");
            return;
        }

        ImageSource = bitmap;
        NoteText = "PDF 本地渲染不可用，显示服务端首页图；可用默认程序打开完整 PDF。";
    }

    private async Task LoadTextAsync(NodeItem node, CancellationToken ct)
    {
        const long Limit = 500 * 1024;
        var (bytes, truncated) = await _preview.GetContentAsync(node, Limit, ct);
        ct.ThrowIfCancellationRequested();

        if (bytes is null)
        {
            ShowError(PreviewUnavailableText(node));
            return;
        }

        TextContent = System.Text.Encoding.UTF8.GetString(bytes);
        IsTruncated = truncated;
        NoteText = truncated ? "文件较大，已截断（前 500KB）" : string.Empty;
    }

    private async Task LoadVideoAsync(NodeItem node, CancellationToken ct)
    {
        if (!VideoPlayback.TryInitialize(out var initError))
        {
            IsVideoAvailable = false;
            VideoStatusText = (initError ?? "视频播放器不可用") + "；可用“用默认程序打开”播放。";
            return;
        }

        // 先清掉上一段视频（DisposeVideo 会重置 loading/status，必须放在设置提示之前）。
        DisposeVideo();
        IsVideoLoading = true;
        VideoStatusText = "正在加载视频…";
        try
        {
            var url = await _preview.GetContentUrlAsync(node, ct);
            ct.ThrowIfCancellationRequested();
            if (url is null)
            {
                IsVideoLoading = false;
                VideoStatusText = "无法获取视频地址";
                return;
            }

            var player = new VideoPlayback();
            _video = player;

            player.FormatReady += CreateVideoBitmap;
            player.FrameArrived += OnVideoFrame;
            player.StateChanged += playing => IsPlaying = playing;
            player.PositionChanged += pos => SetPositionFromPlayer(pos);
            player.TimeChanged += UpdateVideoTimes;
            player.PlaybackEnded += () => IsPlaying = false;
            player.Failed += message =>
            {
                IsVideoLoading = false;
                VideoStatusText = message;
            };

            var ok = await player.StartAsync(url, ct);
            if (!ok) return;

            // 首帧到达前保持“缓冲中”提示（远程视频 VLC 打开常需数秒）。
            _ = WatchFirstFrameTimeoutAsync(ct);
        }
        catch (OperationCanceledException)
        {
            // 关闭/切换文件时取消，忽略。
        }
        catch (Exception ex)
        {
            IsVideoLoading = false;
            VideoStatusText = "视频加载失败：" + ex.Message;
        }
    }

    private void CreateVideoBitmap(int width, int height)
    {
        if (_videoBitmap is { PixelSize: var size } && size.Width == width && size.Height == height)
            return;

        _videoBitmap = new WriteableBitmap(
            new PixelSize(width, height), new Vector(96, 96), PixelFormat.Bgra8888, AlphaFormat.Opaque);
        _videoStride = width * 4;
        VideoFrame = _videoBitmap;
        IsVideoAvailable = true;
        VideoFrameUpdated?.Invoke();
    }

    /// <summary>VLC 线程回调：把 BGRA 帧写进 WriteableBitmap（上一帧未写完时丢帧）。</summary>
    private void OnVideoFrame(byte[] data)
    {
        var bitmap = _videoBitmap;
        if (bitmap is null || _videoStride <= 0) return;

        try
        {
            using (var fb = bitmap.Lock())
            {
                if (fb.RowBytes == _videoStride)
                {
                    Marshal.Copy(data, 0, fb.Address, _videoStride * bitmap.PixelSize.Height);
                }
                else
                {
                    for (var row = 0; row < bitmap.PixelSize.Height; row++)
                    {
                        Marshal.Copy(data, row * _videoStride,
                            fb.Address + (row * fb.RowBytes), _videoStride);
                    }
                }
            }

            VideoFrameUpdated?.Invoke();

            if (IsVideoLoading)
            {
                IsVideoLoading = false;
                VideoStatusText = string.Empty;
            }
        }
        catch
        {
            // 渲染期异常忽略，下一帧继续。
        }
    }

    /// <summary>首帧 15 秒还没来就提示回退，避免一直转圈。</summary>
    private async Task WatchFirstFrameTimeoutAsync(CancellationToken ct)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(15), ct);
            if (ct.IsCancellationRequested || !IsVideoLoading) return;

            IsVideoLoading = false;
            VideoStatusText = "视频加载超时（网络较慢或格式不支持），可点“用默认程序打开”。";
        }
        catch (OperationCanceledException)
        {
            // 切换/关闭时取消，忽略。
        }
    }

    private void SetPositionFromPlayer(float position)
    {
        _syncingPosition = true;
        try
        {
            PositionValue = Math.Clamp(position, 0f, 1f);
        }
        finally
        {
            _syncingPosition = false;
        }
    }

    private void UpdateVideoTimes(long timeMs, long lengthMs)
    {
        TimeText = FormatTime(timeMs);
        DurationText = FormatTime(lengthMs);
    }

    private static string FormatTime(long milliseconds)
    {
        var span = TimeSpan.FromMilliseconds(Math.Max(0, milliseconds));
        return span.TotalHours >= 1
            ? $"{(int)span.TotalHours}:{span.Minutes:00}:{span.Seconds:00}"
            : $"{span.Minutes:00}:{span.Seconds:00}";
    }

    private void DisposeVideo()
    {
        _video?.Dispose();
        _video = null;
        _videoBitmap = null;
        _videoStride = 0;
        VideoFrame = null;
        IsVideoAvailable = false;
        IsVideoLoading = false;
        VideoStatusText = string.Empty;
        IsPlaying = false;
        PositionValue = 0;
        TimeText = "00:00";
        DurationText = "00:00";
        SpeedText = "1.0×";
        _speedRate = 1f;
    }

    /// <summary>
    /// 采纳新的 PDF 渲染器：先释放上一份，再整体替换。
    /// task-95：同一时刻只保留一个 DocReader，避免旧实例滞留到 GC 后在终结器线程上释放 native 句柄
    /// （PDFium 的 teardown 必须在固定线程上做，见 <see cref="PdfRenderer"/>）。
    /// </summary>
    private void AdoptPdfDocument(PdfRenderer renderer)
    {
        _pdf?.Dispose();
        _pdf = renderer;
        PdfDocument = renderer;
    }

    private void DisposePdf()
    {
        _pdf?.Dispose();
        _pdf = null;
        PdfDocument = null;
    }

    private void ResetState()
    {
        DisposeVideo();
        DisposePdf();
        HasError = false;
        ErrorText = string.Empty;
        ImageSource = null;
        TextContent = string.Empty;
        IsTruncated = false;
        NoteText = string.Empty;
        IsImagePreview = false;
        IsTextPreview = false;
        IsPdfPreview = false;
        IsVideoPreview = false;
        IsOtherPreview = false;
        Zoom = 1;
        OffsetX = 0;
        OffsetY = 0;
    }

    private void ShowError(string message)
    {
        HasError = true;
        ErrorText = message;
        IsLoading = false;
    }

    /// <summary>
    /// 聊天文件取流失败时的统一中性文案：服务端对「撤回 / 移出会话 / 删除」等失败一律返回 404，
    /// 不区分原因，界面也就不猜测具体原因。
    /// </summary>
    private static string PreviewUnavailableText(NodeItem node)
        => ChatFileSpace.IsChatFile(node) ? "文件不可访问（可能已被撤回、移出会话或删除）" : "预览不可用，请下载后查看";
}
