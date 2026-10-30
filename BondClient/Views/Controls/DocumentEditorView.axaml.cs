using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;
using Avalonia.Platform;
using Avalonia.Threading;
using Avalonia.Themes;
using Bond.Services;
using Bond.Services.Editor;
using Microsoft.Web.WebView2.Core;

namespace Bond.Views.Controls;

/// <summary>
/// 富文本文档编辑器宿主（task-138）：Avalonia <see cref="NativeControlHost"/> 建子窗口，
/// 把 WebView2 控制器挂进去，页面加载本地 vendored 的 Quill（完全离线）。
/// 内核只是 <see cref="IDocumentEditorHost"/> 的一个实现，外层文档页不依赖 WebView2。
/// </summary>
public partial class DocumentEditorView : UserControl, IDocumentEditorHost, IDisposable
{
    private const string VirtualHost = "bond.editor";
    private const string EditorUrl = "https://bond.editor/index.html";
    private static readonly TimeSpan ReadyTimeout = TimeSpan.FromSeconds(25);

    private readonly WebHost _webHost = new();
    private readonly DispatcherTimer _boundsTimer;
    private readonly object _gate = new();

    private Task? _initTask;
    private CoreWebView2Controller? _controller;
    private CoreWebView2? _core;

    private bool _ready;
    private bool _dark;
    private string? _pendingMarkdown;
    private string _lastMarkdown = "";
    private string? _lastBoundsKey;
    private TaskCompletionSource<string?>? _markdownRequest;
    private string? _failureMessage;

    // task-169：笔记图片安全口径。
    // 只支持「公网 http(s) 外链」和「本地内嵌 data:image/…;base64」两类图片来源；
    // /api/** 鉴权地址、相对路径、本地文件路径一律不解析、不改写、不保存 token，
    // 由 editor.js 显示「图片地址不受支持」占位图，这里负责给用户一条提示。
    private readonly HashSet<string> _unsupportedImageNotified = new(StringComparer.OrdinalIgnoreCase);

    public DocumentEditorView()
    {
        InitializeComponent();
        HostContainer.Children.Add(_webHost);

        _webHost.HwndReady += (_, _) => AttachControllerToHost();
        _webHost.SizeChanged += (_, _) => UpdateBounds();

        _boundsTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
        _boundsTimer.Tick += (_, _) => UpdateBounds();

        AttachedToVisualTree += (_, _) =>
        {
            _dark = TopLevel.GetTopLevel(this)?.ActualThemeVariant == Avalonia.Styling.ThemeVariant.Dark;
            _boundsTimer.Start();
        };
        DetachedFromVisualTree += (_, _) => _boundsTimer.Stop();

        if (Application.Current is { } app)
        {
            app.ActualThemeVariantChanged += OnAppThemeChanged;
        }
    }

    /// <summary>内核就绪（脚本 ready 已收到）。</summary>
    public event EventHandler? Ready;

    /// <summary>内容被编辑，载荷为 Markdown 文本。</summary>
    public event EventHandler<string>? MarkdownChanged;

    /// <summary>用户在内核里按了 Ctrl+S。</summary>
    public event EventHandler? SaveRequested;

    /// <summary>初始化失败 / 运行期内核报错，载荷为可展示原因。</summary>
    public event EventHandler<string>? Failed;

    public bool IsReady => _ready;

    public string? FailureMessage => _failureMessage;

    /// <summary>供兜底面板「复制当前内容」使用。</summary>
    public string CurrentMarkdown => _lastMarkdown;

    /// <summary>校验资源并在需要时创建 WebView2（可重复调用）。</summary>
    public Task EnsureInitializedAsync()
    {
        lock (_gate)
        {
            _initTask ??= InitializeAsync();
            return _initTask;
        }
    }

    private async Task InitializeAsync()
    {
        try
        {
            var editorDir = Path.Combine(AppContext.BaseDirectory, "Assets", "editor");
            if (!File.Exists(Path.Combine(editorDir, "index.html")))
            {
                SetFailure($"找不到编辑器资源目录：{editorDir}");
                return;
            }

            if (_webHost.Hwnd == IntPtr.Zero)
            {
                // NativeControlHost 尚未创建子窗口（布局还没到）：等一秒再试。
                await Task.Delay(300).ConfigureAwait(true);
            }

            if (_webHost.Hwnd == IntPtr.Zero)
            {
                SetFailure("原生宿主窗口未能创建（缺少 app.manifest 的 supportedOS 声明时会出现）。");
                return;
            }

            var userData = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Bond",
                "editor-webview2");
            Directory.CreateDirectory(userData);

            CoreWebView2Environment env;
            try
            {
                env = await CoreWebView2Environment.CreateAsync(null, userData).ConfigureAwait(true);
            }
            catch (Exception ex)
            {
                SetFailure($"WebView2 Runtime 不可用或版本过低：{Describe(ex)}");
                return;
            }

            _controller = await env.CreateCoreWebView2ControllerAsync(_webHost.Hwnd!).ConfigureAwait(true);
            _controller.DefaultBackgroundColor = System.Drawing.Color.Transparent;
            _controller.IsVisible = !_suspended;
            _core = _controller.CoreWebView2;
            _core.WebMessageReceived += OnWebMessageReceived;
            _core.SetVirtualHostNameToFolderMapping(VirtualHost, editorDir, CoreWebView2HostResourceAccessKind.Allow);
            _core.Navigate(EditorUrl);

            // 等待 JS 侧 ready 消息；超时也允许继续（可能已渲染但消息延迟）
            var deadline = Stopwatch.StartNew();
            while (!_ready && deadline.Elapsed < ReadyTimeout)
            {
                await Task.Delay(120).ConfigureAwait(true);
            }

            if (!_ready)
            {
                SetFailure($"编辑器页面在 {ReadyTimeout.TotalSeconds:0} 秒内没有就绪（资源缺失或被安全策略阻止）。");
                return;
            }

            UpdateBounds();
            // task-169：先告诉内核 API 基址，后续 Markdown 里的相对图片地址才能解析。
            await SendThemeAsync().ConfigureAwait(true);
            if (_pendingMarkdown is { } pending)
            {
                _pendingMarkdown = null;
                await LoadMarkdownCoreAsync(pending).ConfigureAwait(true);
            }
        }
        catch (Exception ex)
        {
            SetFailure($"初始化编辑器失败：{Describe(ex)}");
        }
    }

    private void AttachControllerToHost()
    {
        if (_controller is not null)
        {
            UpdateBounds();
        }
    }

    private void UpdateBounds()
    {
        if (_suspended || _controller is null || _webHost.Hwnd == IntPtr.Zero)
        {
            return;
        }

        var parent = _webHost.ParentHwnd;
        if (parent == IntPtr.Zero)
        {
            return;
        }

        if (!NativeMethods.GetClientRect(parent, out var rect))
        {
            return;
        }

        var width = Math.Max(1, rect.Right);
        var height = Math.Max(1, rect.Bottom);
        var key = width + "x" + height;
        if (key == _lastBoundsKey)
        {
            return;
        }

        _lastBoundsKey = key;
        try
        {
            NativeMethods.SetWindowPos(_webHost.Hwnd, IntPtr.Zero, 0, 0, width, height,
                NativeMethods.SwpNoZOrder | NativeMethods.SwpNoActivate);
            _controller.Bounds = new System.Drawing.Rectangle(0, 0, width, height);
        }
        catch (Exception)
        {
            // 窗口正在销毁时忽略
        }
    }

    public async Task LoadMarkdownAsync(string markdown)
    {
        if (_ready && _core is not null)
        {
            await LoadMarkdownCoreAsync(markdown).ConfigureAwait(true);
            return;
        }

        _pendingMarkdown = markdown;
        _lastMarkdown = markdown;
        await EnsureInitializedAsync().ConfigureAwait(true);
    }

    private Task LoadMarkdownCoreAsync(string markdown)
    {
        _lastMarkdown = markdown;
        HideFallback();
        // task-169：每次载入前同步一次 API 基址（设置页可能已切换服务器），
        // 再让内核把 /api/… 相对图片地址解析成本站绝对地址。
        PostJson(new { type = "load", markdown });
        return Task.CompletedTask;
    }

    public async Task<string?> GetMarkdownAsync()
    {
        if (!_ready || _core is null)
        {
            return _lastMarkdown;
        }

        if (_markdownRequest is not null)
        {
            return _lastMarkdown;
        }

        var tcs = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
        _markdownRequest = tcs;
        var id = Guid.NewGuid().ToString("N");
        PostJson(new { type = "requestMarkdown", id });
        var completed = await Task.WhenAny(tcs.Task, Task.Delay(2500)).ConfigureAwait(true);
        if (completed != tcs.Task)
        {
            _markdownRequest = null;
            return _lastMarkdown;
        }

        return await tcs.Task.ConfigureAwait(true) ?? _lastMarkdown;
    }

    public void FocusEditor()
    {
        if (_ready)
        {
            PostJson(new { type = "focus" });
        }
    }

    /// <summary>
    /// task-138：WebView2 是独立的原生子窗口，会盖住 Avalonia 的浮层（差异对比、版本历史等）。
    /// 浮层打开期间先隐藏 WebView，让界面控件可见。
    /// </summary>
    public void SetSuspended(bool suspended)
    {
        _suspended = suspended;
        if (_controller is not null)
        {
            _controller.IsVisible = !suspended;
        }

        // 原生子窗口即使看不到也可能继续画在 Avalonia 之上，尺寸也一并收成 0，恢复时再按布局重算。
        // 另外把宿主本身设为不可见：Avalonia 对 NativeControlHost 的可见性会映射到 ShowWindow，
        // 这是绕开「子窗口永远盖住 Avalonia」的最后一道保险。
        if (_webHost is not null)
        {
            _webHost.IsVisible = !suspended;
        }

        if (_webHost?.Hwnd is { } hwnd && hwnd != IntPtr.Zero)
        {
            if (suspended)
            {
                // 注意不能带 SWP_NOSIZE（0x0001），否则 0 尺寸不会生效。
                NativeMethods.SetWindowPos(hwnd, IntPtr.Zero, 0, 0, 0, 0, 0x0004 | 0x0010);
            }
            else
            {
                // 恢复时强制重算一次，避免 bounds key 恰好没变而跳过。
                _lastBoundsKey = string.Empty;
                UpdateBounds();
            }
        }
    }

    private bool _suspended;

    public async Task SetDarkThemeAsync(bool dark)
    {
        _dark = dark;
        if (_ready)
        {
            await SendThemeAsync().ConfigureAwait(true);
        }
    }

    private Task SendThemeAsync()
    {
        if (_ready)
        {
            PostJson(new { type = "theme", dark = _dark });
        }

        return Task.CompletedTask;
    }

    /// <summary>把已上传到网盘的图片插入编辑器（第一轮只支持 URL 图片）。</summary>
    public void InsertImageUrl(string url, string? alt = null)
    {
        if (_ready)
        {
            PostJson(new { type = "insertImage", url, alt = alt ?? "" });
        }
    }

    private void PostJson(object payload)
    {
        try
        {
            _core?.PostWebMessageAsJson(JsonSerializer.Serialize(payload));
        }
        catch (Exception)
        {
            // 内核已销毁
        }
    }

    private void OnWebMessageReceived(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        try
        {
            using var doc = JsonDocument.Parse(e.WebMessageAsJson);
            var root = doc.RootElement;
            if (!root.TryGetProperty("type", out var typeProp))
            {
                return;
            }

            switch (typeProp.GetString())
            {
                case "ready":
                    _ready = true;
                    LoadingPanel.IsVisible = false;
                    HideFallback();
                    Ready?.Invoke(this, EventArgs.Empty);
                    break;
                case "dirty":
                    if (root.TryGetProperty("markdown", out var md))
                    {
                        _lastMarkdown = md.GetString() ?? _lastMarkdown;
                        MarkdownChanged?.Invoke(this, _lastMarkdown);
                    }

                    break;
                case "markdown":
                    if (_markdownRequest is { } tcs)
                    {
                        _markdownRequest = null;
                        tcs.TrySetResult(root.TryGetProperty("markdown", out var reply) ? reply.GetString() : null);
                    }

                    break;
                case "save":
                    SaveRequested?.Invoke(this, EventArgs.Empty);
                    break;
                case "error":
                    var msg = root.TryGetProperty("message", out var m) ? m.GetString() : "编辑器内核报错";
                    SetFailure(msg ?? "编辑器内核报错", showFallback: false);
                    Failed?.Invoke(this, msg ?? "编辑器内核报错");
                    break;
                case "imageUnsupported":
                    // task-169：本地路径/file: 等地址编辑器不读取本地文件，明确提示用户改用链接。
                    NotifyUnsupportedImage(root.TryGetProperty("src", out var badSrc) ? badSrc.GetString() : null);
                    break;
                case "loaded":
                    break;
            }
        }
        catch (Exception)
        {
            // 非协议消息忽略
        }
    }

    /// <summary>把不支持来源的图片（/api/** 鉴权地址、相对路径、本地文件）提示给用户。</summary>
    private void NotifyUnsupportedImage(string? src)
    {
        if (string.IsNullOrWhiteSpace(src)
            || _unsupportedImageNotified.Count >= 8
            || !_unsupportedImageNotified.Add(src))
        {
            return;
        }

        try
        {
            if (App.Services?.GetService(typeof(INotificationCenter)) is INotificationCenter notify)
            {
                var shown = src == "data-image-too-large" ? "" : src.Length > 60 ? src[..60] + "…" : src;
                var reason = src == "data-image-too-large"
                    ? "内嵌图片超过 2 MB"
                    : "图片地址不受支持";
                var detail = shown.Length > 0 ? "：" + shown : "";
                notify.Notify(
                    $"笔记里有{reason}的图片{detail}；仅支持公网 https 图片链接或本地内嵌图片",
                    NoticeKind.Warning);
            }
        }
        catch (Exception)
        {
            // 通知失败不影响编辑
        }
    }

    private void SetFailure(string message, bool showFallback = true)
    {
        _failureMessage = message;
        LoadingPanel.IsVisible = false;
        if (showFallback)
        {
            FallbackText.Text = message;
            FallbackPanel.IsVisible = true;
        }

        Failed?.Invoke(this, message);
    }

    private void HideFallback()
    {
        FallbackPanel.IsVisible = false;
        _failureMessage = null;
    }

    private static string Describe(Exception ex)
    {
        var depth = 0;
        var cur = ex;
        while (cur.InnerException is not null && depth++ < 3)
        {
            cur = cur.InnerException;
        }

        return cur.Message;
    }

    private void OnAppThemeChanged(object? sender, EventArgs e)
    {
        var dark = Application.Current?.ActualThemeVariant == Avalonia.Styling.ThemeVariant.Dark;
        if (dark != _dark)
        {
            _ = SetDarkThemeAsync(dark);
        }
    }

    private async void OnCopyContentClick(object? sender, RoutedEventArgs e)
    {
        try
        {
            var clipboard = TopLevel.GetTopLevel(this)?.Clipboard;
            if (clipboard is null)
            {
                return;
            }

            var text = await GetMarkdownAsync().ConfigureAwait(true);
            await clipboard.SetTextAsync(text ?? _lastMarkdown).ConfigureAwait(true);
        }
        catch (Exception)
        {
            // 复制失败不抛给用户
        }
    }

    private async void OnRetryClick(object? sender, RoutedEventArgs e)
    {
        RetryButton.IsEnabled = false;
        try
        {
            await ResetAndInitializeAsync().ConfigureAwait(true);
        }
        finally
        {
            RetryButton.IsEnabled = true;
        }
    }

    private async Task ResetAndInitializeAsync()
    {
        Disposing();
        lock (_gate)
        {
            _initTask = null;
        }

        LoadingPanel.IsVisible = true;
        await EnsureInitializedAsync().ConfigureAwait(true);
    }

    private void Disposing()
    {
        _ready = false;
        _lastBoundsKey = null;
        _markdownRequest?.TrySetResult(null);
        _markdownRequest = null;
        _unsupportedImageNotified.Clear();
        try
        {
            _controller?.Close();
        }
        catch (Exception)
        {
            // ignore
        }

        _controller = null;
        _core = null;
    }

    public void Dispose()
    {
        _boundsTimer.Stop();
        if (Application.Current is { } app)
        {
            app.ActualThemeVariantChanged -= OnAppThemeChanged;
        }

        Disposing();
        GC.SuppressFinalize(this);
    }

    /// <summary>封装原生子窗口的宿主控件。</summary>
    private sealed class WebHost : NativeControlHost
    {
        public IntPtr Hwnd { get; private set; }

        public IntPtr ParentHwnd { get; private set; }

        public event EventHandler? HwndReady;

        protected override IPlatformHandle CreateNativeControlCore(IPlatformHandle parent)
        {
            ParentHwnd = parent.Handle;
            Hwnd = NativeMethods.CreateWindowEx(
                0,
                "STATIC",
                string.Empty,
                NativeMethods.WsChild | NativeMethods.WsVisible,
                0,
                0,
                1,
                1,
                parent.Handle,
                IntPtr.Zero,
                IntPtr.Zero,
                IntPtr.Zero);

            if (Hwnd == IntPtr.Zero)
            {
                throw new InvalidOperationException(
                    "无法创建编辑器子窗口：应用清单需要声明 supportedOS（Windows 10/11）。");
            }

            HwndReady?.Invoke(this, EventArgs.Empty);
            return new PlatformHandle(Hwnd, "HWND");
        }

        protected override void DestroyNativeControlCore(IPlatformHandle control)
        {
            Hwnd = IntPtr.Zero;
            ParentHwnd = IntPtr.Zero;
        }
    }

    private static class NativeMethods
    {
        public const int WsChild = 0x40000000;
        public const int WsVisible = 0x10000000;
        public const uint SwpNoZOrder = 0x0004;
        public const uint SwpNoActivate = 0x0010;

        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern IntPtr CreateWindowEx(
            int exStyle,
            string className,
            string windowName,
            int style,
            int x,
            int y,
            int width,
            int height,
            IntPtr parent,
            IntPtr menu,
            IntPtr instance,
            IntPtr param);

        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool GetClientRect(IntPtr hwnd, out NativeRect rect);

        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool SetWindowPos(
            IntPtr hwnd,
            IntPtr insertAfter,
            int x,
            int y,
            int cx,
            int cy,
            uint flags);

        [StructLayout(LayoutKind.Sequential)]
        public struct NativeRect
        {
            public int Left;
            public int Top;
            public int Right;
            public int Bottom;
        }
    }
}
