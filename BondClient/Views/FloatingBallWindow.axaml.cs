using System;
using System.Collections.Generic;
using System.Collections.Specialized;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Controls.Shapes;
using Avalonia.Input;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Styling;
using Avalonia.Threading;
using BondClient.Models;
using BondClient.Services;
using BondClient.ViewModels;
using Path = System.IO.Path;
using SkiaSharp;
using Svg.Skia;

namespace BondClient.Views;

public partial class FloatingBallWindow : Window
{
    private const double DragThresholdDip = 5.0;
    private const double EdgeSnapThresholdDip = 30.0;
    private const double BallSizeDip = 56.0;
    private const double BallPadDip = 20.0;
    private const double PanelWidthDip = 400.0;
    private const double PanelHeightDip = 520.0;
    private const double TransferWidthDip = 560.0;
    private const double TransferHeightDip = 460.0;
    private const double SettingsWidthDip = 560.0;
    private const double SettingsHeightDip = 460.0;
    private const double PanelCR = 16.0;
    private const double BallCR = 28.0;
    private const double PillWidthDip = 14.0;
    private const double PillHeightDip = 72.0;
    private const double PillCR = 7.0;
    private const double IconSizeDip = 28.0;
    private const double IconTargetSizeDip = 18.0;

    // Apple CASpringAnimation: near-critical damping, moderate speed
    private const double BorderZeta = 0.92, BorderOmega = 4.5;
    private const double IconZeta = 0.85, IconOmega = 5.5;
    // Pill→panel: separate curves for width (fast bloom) and height (follow-through)
    private const double PillWZeta = 0.78, PillWOmega = 5.0;
    private const double PillHZeta = 0.82, PillHOmega = 4.2;
    // Position: snappy overshoot
    private const double PosZeta = 0.85, PosOmega = 5.0;

    private static readonly double BorderEndVal = ComputeSpringEnd(BorderZeta, BorderOmega);
    private static readonly double IconEndVal = ComputeSpringEnd(IconZeta, IconOmega);
    private static readonly double PillWEndVal = ComputeSpringEnd(PillWZeta, PillWOmega);
    private static readonly double PillHEndVal = ComputeSpringEnd(PillHZeta, PillHOmega);
    private static readonly double PosEndVal = ComputeSpringEnd(PosZeta, PosOmega);

    private double _dpi = 1.0;
    private double _ballSize, _ballPad, _panelW, _panelH;
    private int _ballWinW, _ballWinH;
    private double _edgeSnap, _dragThresh;

    private double _icoEndTx, _icoEndTy, _icoEndSc;
    private const double IcoStartTx = 0.0, IcoStartTy = 0.0, IcoStartSc = 1.0;

    private bool _ptrDown, _dragging;
    private PixelPoint _ptrStart, _winStart;
    private enum SnapDir { None, Left, Right, Top, Bottom }
    private SnapDir _snapDir = SnapDir.None;
    private bool _expanded;
    private bool _morphInProgress;

    // Morph
    private uint _mGen;
    private bool _mDir;
    private double _mT;
    private long _mTick;
    private double _fW, _fH, _fCR, _tW, _tH, _tCR;
    private int _cx, _cy;
    private int _scx, _scy, _tcx, _tcy; // Start/Target center for smooth position animation
    private double _iFx, _iFy, _iFs, _iTx, _iTy, _iTs;

    // Content fade
    private bool _contentFadeIn;
    private bool _showTransfer;
    private bool _showApproval;
    private bool _showSettings;
    private bool _showDashboard;

    // Transfer progress
    private bool _showTransferProgress;
    private uint _transferCrossfadeGen;
    private bool _isDark;

    // Debug mode
    private bool _debugMode;
    private DispatcherTimer? _aboutLongPressTimer;

    // Dashboard loading
    private DispatcherTimer? _loadingTimer;
    private DispatcherTimer? _approvalCountdown;
    private bool _devicesLoaded;

    // Toast

    // Hover
    private uint _hGen;
    private double _hT;
    private long _hTick;
    private double _hFrom, _hTo;

    // Pill morph (edge snap)
    private bool _isPill;
    private uint _pillGen;
    private double _pillT;
    private long _pillTick;
    private double _pillFromW, _pillToW, _pillFromH, _pillToH, _pillFromCR, _pillToCR;

    // Cache
    private ScaleTransform? _mScale;
    private TranslateTransform? _mTrans;
    private double _lastW = double.NaN, _lastH, _lastCR;
    private double _lastItx = double.NaN, _lastIty, _lastIts;
    private bool _morphFromPill;

    // Services
    private PasswordManager _password = null!;
    private DiscoveryService _discovery = null!;
    private TransferService _transfer = null!;
    private TransferViewModel _vm = null!;

    // Rainbow
    private DispatcherTimer? _rainbowTimer;
    private double _rainbowHue;

    private static readonly string PosFile = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "BondClient", "position.txt");

    public FloatingBallWindow()
    {
        InitializeComponent();
        if (MorphIcon.RenderTransform is TransformGroup tg)
        {
            _mScale = tg.Children[0] as ScaleTransform;
            _mTrans = tg.Children[1] as TranslateTransform;
        }
    }

    protected override void OnOpened(EventArgs e)
    {
        base.OnOpened(e);
        _dpi = RenderScaling;

        _ballSize = BallSizeDip; _ballPad = BallPadDip;
        _panelW = PanelWidthDip; _panelH = PanelHeightDip;
        _ballWinW = DipToPx(_ballSize + _ballPad * 2);
        _ballWinH = _ballWinW;
        _edgeSnap = EdgeSnapThresholdDip;
        _dragThresh = DragThresholdDip;
        _icoEndSc = IconTargetSizeDip / IconSizeDip;

        var elementCenter = 13.0 + IconSizeDip / 2.0; // 13 + 14 = 27
        var iconCenterX = 16.0 + IconTargetSizeDip / 2.0; // Left padding 16 + half size 9 = 25
        var iconCenterY = 28.0; // Header height 56, so center is 28

        _icoEndTx = iconCenterX - elementCenter;
        _icoEndTy = iconCenterY - elementCenter;

        LoadSvgIcon();
        RestorePosition();
        ClampToScreen();
        AutoSnapIfOffScreen();
        if (!File.Exists(PosFile))
        {
            var s = Screens.Primary;
            if (s?.WorkingArea is { } wa)
                Position = new PixelPoint(wa.X + wa.Width - _ballWinW - DipToPx(40),
                                          wa.Y + wa.Height / 2 - _ballWinH / 2);
        }

        InitServices();

        DragDrop.AddDragOverHandler(this, OnDragOver);
        DragDrop.AddDropHandler(this, OnDrop);
    }

    #region SVG Icon

    private void LoadSvgIcon()
    {
        try
        {
            var uri = new Uri("avares://BondClient/Assets/logo.svg");
            using var stream = AssetLoader.Open(uri);
            var svg = new SKSvg();
            svg.Load(stream);

            if (svg.Picture != null)
            {
                var size = (int)(IconSizeDip * _dpi);
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
                using var ms = new MemoryStream();
                data.SaveTo(ms);
                ms.Position = 0;

                MorphIcon.Source = new Bitmap(ms);
            }
        }
        catch { }
    }

    #endregion

    #region Services

    private void InitServices()
    {
        _password = new PasswordManager();
        _password.Load();

        _discovery = new DiscoveryService(_password);
        _transfer = new TransferService(_password);

        _vm = new TransferViewModel(_password, _discovery, _transfer);
        DataContext = _vm;

        _vm.RequestReceived += OnIncomingRequest;
        _vm.DevicesUpdated += () =>
        {
            DeviceCount.Text = $"({_vm.Devices.Count})";
            EmptyHint.IsVisible = _vm.Devices.Count == 0;
            DashDeviceCount.Text = $"({_vm.Devices.Count})";

            if (!_devicesLoaded && _vm.Devices.Count > 0)
            {
                _devicesLoaded = true;
                _loadingTimer?.Stop();
                ShowDeviceList();
            }

            DashConnectionDot.Background = _vm.Devices.Count > 0
                ? TryGetBrush("BondSuccess")
                : TryGetBrush("BondDisabled");
        };
        _vm.TransferDone += name =>
        {
            StatusText.Text = $"已接收: {name}";
            ShowToast($"已接收: {name}", ToastType.Success);
        };
        _vm.TransferStarted += OnTransferStarted;
        _vm.TransferEnded += OnTransferEnded;
        _vm.TransferError += err => ShowToast(err, ToastType.Error);
        _vm.Progress.PropertyChanged += (_, _) =>
        {
            if (_showTransferProgress) UpdateTransferProgress();
        };

        _vm.PendingRequests.CollectionChanged += OnPendingRequestsChanged;
        _vm.RecentFiles.CollectionChanged += (_, _) =>
        {
            DashRecentCard.IsVisible = _vm.RecentFiles.Count > 0;
        };

        _discovery.Start();
        _transfer.Start();

        // Initialize theme state + listen to ALL theme changes from SukiUI
        var suki = SukiUI.SukiTheme.GetInstance(Application.Current!);
        _isDark = suki.ActiveBaseTheme == Avalonia.Styling.ThemeVariant.Dark;
        suki.OnBaseThemeChanged = (theme) =>
        {
            _isDark = theme == Avalonia.Styling.ThemeVariant.Dark;
            Dispatcher.UIThread.Post(() => ApplyThemeColors(_isDark));
        };
        ApplyThemeColors(_isDark); // Always apply on startup

        // Check firewall status for LAN compatibility
        _ = Task.Run(() => CheckFirewall());

        _rainbowTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(30) };
        _rainbowTimer.Tick += OnRainbowTick;

        _loadingTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
        _loadingTimer.Tick += (_, _) =>
        {
            _loadingTimer.Stop();
            if (!_devicesLoaded)
            {
                _devicesLoaded = true;
                ShowDeviceList();
            }
        };

        _approvalCountdown = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _approvalCountdown.Tick += (_, _) =>
        {
            for (int i = _vm.PendingRequests.Count - 1; i >= 0; i--)
            {
                var req = _vm.PendingRequests[i];
                req.RemainingSeconds--;
                if (req.RemainingSeconds <= 0)
                {
                    _vm.RejectRequest(req);
                    ShowToast("请求已超时", ToastType.Info);
                }
            }
        };
    }

    private void OnPendingRequestsChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        ApprovalCount.Text = _vm.PendingRequests.Count > 0 ? $"({_vm.PendingRequests.Count})" : "";
        if (_vm.PendingRequests.Count == 0)
        {
            ApprovalPanel.IsVisible = false;
            StopRainbow();
            _approvalCountdown?.Stop();

            if (_expanded && !_showTransfer && !_showSettings)
            {
                DashboardPanel.IsVisible = true;
                DashboardPanel.Opacity = 1;
                _showDashboard = true;
                SettingsBtn.IsVisible = true;
            }
        }
    }

    private void ShowDeviceList()
    {
        DashLoadingIndicator.IsVisible = false;
        DashDeviceList.IsVisible = true;
        DashEmptyHint.IsVisible = _vm.Devices.Count == 0;
    }

    private void UpdateFileInfo(string[] files)
    {
        var names = string.Join(", ", files.Select(Path.GetFileName));
        FileNamesText.Text = names;
        FileCountLabel.Text = files.Length > 1 ? $"({files.Length} 个文件)" : "";
    }

    private void CheckFirewall()
    {
        try
        {
            var status = FirewallHelper.CheckStatus();
            if (!status.IsChecked) return;

            if (status.IsPublic && (!status.HasUdpRule || !status.HasTcpRule))
            {
                // Try to add rules automatically
                var added = FirewallHelper.TryAddRules();
                if (added)
                {
                    Dispatcher.UIThread.Post(() =>
                        ShowToast($"已为{status.ProfileName}添加防火墙规则", ToastType.Success));
                }
                else
                {
                    // No admin rights — create batch script and prompt user
                    var scriptPath = FirewallHelper.CreateBatchScript();
                    Dispatcher.UIThread.Post(() =>
                        ShowToast($"当前为{status.ProfileName}，局域网发现可能受限。请以管理员运行 add_firewall_rules.bat", ToastType.Info));
                }
            }
        }
        catch { }
    }

    private void OnTransferStarted()
    {
        if (_expanded && TransferPanel.IsVisible)
        {
            StartTransferProgressCrossfade(true);
        }
    }

    private void OnTransferEnded()
    {
        if (_showTransferProgress)
        {
            StartTransferProgressCrossfade(false);
        }
    }

    private void StartTransferProgressCrossfade(bool toProgress)
    {
        _transferCrossfadeGen++;
        var gen = _transferCrossfadeGen;
        var fromPanel = toProgress ? TransferSelectMode : TransferProgressMode;
        var toPanel = toProgress ? TransferProgressMode : TransferSelectMode;

        toPanel.IsVisible = true;
        toPanel.Opacity = 0;

        var startTime = DateTime.UtcNow;
        var duration = 300.0;

        void Tick()
        {
            if (_transferCrossfadeGen != gen) return;
            var elapsed = (DateTime.UtcNow - startTime).TotalMilliseconds;
            var t = Math.Min(elapsed / duration, 1.0);
            var eased = EaseOutCubic(t);

            fromPanel.Opacity = 1 - eased;
            toPanel.Opacity = eased;

            if (t < 1)
            {
                Dispatcher.UIThread.Post(Tick, DispatcherPriority.Render);
            }
            else
            {
                fromPanel.IsVisible = false;
                fromPanel.Opacity = 0;
                toPanel.Opacity = 1;
                _showTransferProgress = toProgress;
            }
        }

        Dispatcher.UIThread.Post(Tick, DispatcherPriority.Render);
    }

    private void UpdateTransferProgress()
    {
        var p = _vm.Progress;
        ProgressFileName.Text = p.FileName;
        ProgressSpeed.Text = p.SpeedText;
        ProgressETA.Text = p.ETA;
        ProgressLabel.Text = p.ProgressLabel;
        ProgressPercent.Text = $"{p.Percent:P0}";

        if (ProgressBarFill.Parent is Grid barGrid && barGrid.Bounds.Width > 0)
            ProgressBarFill.Width = barGrid.Bounds.Width * p.Percent;
    }

    #endregion

    #region Approval

    private void OnApproveRequest(object? sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.DataContext is PendingRequest req)
        {
            _vm.ApproveRequest(req);
            // Switch to transfer panel to show receive progress
            if (_expanded && !_morphInProgress)
            {
                DoMorphToTransfer();
            }
        }
    }

    private void OnRejectRequest(object? sender, RoutedEventArgs e)
    {
        if (sender is Button btn && btn.DataContext is PendingRequest req)
        {
            _vm.RejectRequest(req);
        }
    }

    #endregion

    #region Device Selection

    private void OnDeviceChecked(object? sender, RoutedEventArgs e)
    {
        if (sender is CheckBox cb && cb.IsChecked == true && cb.DataContext is DeviceItem item && item.PasswordVisible)
        {
            // Find the password TextBox in the same StackPanel
            if (cb.Parent is StackPanel sp)
            {
                foreach (var child in sp.Children)
                {
                    if (child is TextBox tb && tb.Classes.Contains("password"))
                    {
                        Dispatcher.UIThread.Post(() => tb.Focus(), DispatcherPriority.Render);
                        break;
                    }
                }
            }
        }
    }

    #endregion

    #region Drag-Drop

    private void OnDragOver(object? sender, DragEventArgs e)
    {
        e.DragEffects = e.DataTransfer.Contains(DataFormat.File) ? DragDropEffects.Copy : DragDropEffects.None;
    }

    private async void OnDrop(object? sender, DragEventArgs e)
    {
        var items = e.DataTransfer.GetItems(DataFormat.File);
        var paths = new List<string>();
        foreach (var item in items)
        {
            var file = item.TryGetFile();
            if (file?.Path != null) paths.Add(file.Path.LocalPath);
        }
        if (paths.Count == 0) return;

        // If transfer in progress, ask for confirmation
        if (_showTransferProgress)
        {
            var dlg = new ConfirmDialog("当前有传输正在进行，是否取消并发送新文件？");
            var confirmed = await dlg.ShowDialog<bool>(this);
            if (!confirmed) return;
            _vm?.CancelTransfer();
        }

        _vm!.QueuedFiles = [.. paths];
        UpdateFileInfo(_vm.QueuedFiles!);
        StatusText.Text = $"已选择: {string.Join(", ", paths.Select(Path.GetFileName))}";
        EmptyHint.IsVisible = false;

        if (!_expanded)
        {
            DoExpand(withTransfer: true);
        }
        else if (!TransferPanel.IsVisible)
        {
            _vm?.RefreshDevices();
            if (!_morphInProgress)
            {
                DoMorphToTransfer();
            }
        }
        else if (_showTransferProgress)
        {
            _vm!.RefreshDevices();
            StartTransferProgressCrossfade(false);
        }
        else
        {
            UpdateFileInfo(_vm!.QueuedFiles!);
            _vm.RefreshDevices();
        }
    }

    #endregion

    #region Toast

    private enum ToastType { Success, Error, Info }

    private void ShowToast(string message, ToastType type = ToastType.Info)
    {
        if (!_expanded) return; // Don't show toast when ball is collapsed

        var accentBrush = new SolidColorBrush(Color.Parse("#FF5D7358"));
        var errorBrush = new SolidColorBrush(Color.Parse("#FFA34A42"));
        var secondaryBrush = new SolidColorBrush(Color.Parse("#FF6F6A63"));

        var border = new Border
        {
            Classes = { "toast", type switch
            {
                ToastType.Success => "toast-success",
                ToastType.Error => "toast-error",
                _ => "toast-info"
            }},
            Opacity = 0,
            Child = new StackPanel
            {
                Orientation = Avalonia.Layout.Orientation.Horizontal,
                Spacing = 8,
                Children =
                {
                    new Avalonia.Controls.Shapes.Path
                    {
                        Width = 14, Height = 14,
                        Stroke = type switch
                        {
                            ToastType.Success => accentBrush,
                            ToastType.Error => errorBrush,
                            _ => secondaryBrush
                        },
                        Data = type switch
                        {
                            ToastType.Success => this.FindResource("Ico.Copy") as StreamGeometry,
                            ToastType.Error => this.FindResource("Ico.Close") as StreamGeometry,
                            _ => this.FindResource("Ico.Info") as StreamGeometry
                        } ?? new StreamGeometry(),
                        VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center
                    },
                    new TextBlock
                    {
                        Text = message,
                        FontSize = 12,
                        Foreground = type switch
                        {
                            ToastType.Success => accentBrush,
                            ToastType.Error => errorBrush,
                            _ => secondaryBrush
                        },
                        VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
                        TextTrimming = Avalonia.Media.TextTrimming.CharacterEllipsis,
                        MaxWidth = 260
                    }
                }
            }
        };

        ToastContainer.Children.Add(border);
        var cts = new CancellationTokenSource();
        border.Tag = cts;
        _ = AnimateToastLifecycle(border, cts);
    }

    private async Task AnimateToastLifecycle(Border border, CancellationTokenSource cts)
    {
        var ct = cts.Token;
        try
        {
            // Slide in
            var sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < 250)
            {
                if (ct.IsCancellationRequested) return;
                var t = sw.ElapsedMilliseconds / 250.0;
                border.Opacity = EaseOutCubic(t);
                border.RenderTransform = new TranslateTransform(0, -10 * (1 - EaseOutCubic(t)));
                await Task.Delay(16);
            }
            border.Opacity = 1;
            border.RenderTransform = new TranslateTransform(0, 0);

            // Wait 3s
            await Task.Delay(3000, ct);

            // Fade out
            sw.Restart();
            while (sw.ElapsedMilliseconds < 250)
            {
                if (ct.IsCancellationRequested) return;
                var t = sw.ElapsedMilliseconds / 250.0;
                border.Opacity = 1 - EaseOutCubic(t);
                await Task.Delay(16);
            }
        }
        catch (OperationCanceledException) { }
        finally
        {
            ToastContainer.Children.Remove(border);
            cts.Dispose();
        }
    }

    #endregion

    #region Content crossfade

    private double _crossfadeT;
    private long _crossfadeTick;
    private uint _crossfadeGen;

    private void StartContentCrossfade()
    {
        _crossfadeGen++;
        _crossfadeT = 0; _crossfadeTick = 0;
        RequestAnimationFrame(CrossfadeTick);
    }

    private void CrossfadeTick(TimeSpan now)
    {
        var g = _crossfadeGen;
        if (_crossfadeTick == 0) { _crossfadeTick = now.Ticks; if (_crossfadeGen == g) RequestAnimationFrame(CrossfadeTick); return; }
        var dt = (now.Ticks - _crossfadeTick) / (double)TimeSpan.TicksPerSecond;
        _crossfadeTick = now.Ticks;
        _crossfadeT += dt / 0.3;
        if (_crossfadeT >= 1) _crossfadeT = 1;

        var raw = _crossfadeT;
        var t = EaseOutCubic(raw);

        DashboardPanel.Opacity = 1 - t;
        TransferPanel.Opacity = t;
        TransferPanel.IsVisible = true;

        if (raw >= 1)
        {
            if (_crossfadeGen == g) { DashboardPanel.Opacity = 0; DashboardPanel.IsVisible = false; }
            return;
        }
        if (_crossfadeGen == g) RequestAnimationFrame(CrossfadeTick);
    }

    #endregion

    #region Incoming request + Rainbow

    private void OnIncomingRequest()
    {
        StartRainbow();
        _approvalCountdown?.Start();
    }

    private void ShowApprovalPanel()
    {
        StopRainbow(); // Stop rainbow when approval panel is visible
        DeviceNameDisplay.Opacity = 0;
        TransferPanel.IsVisible = false;
        TransferPanel.Opacity = 0;
        DashboardPanel.IsVisible = false;
        DashboardPanel.Opacity = 0;
        ApprovalPanel.IsVisible = true;
        _showApproval = true;
        _showDashboard = false;
        SettingsBtn.IsVisible = false;
    }

    private void StartRainbow()
    {
        _rainbowTimer?.Start();
    }

    private void StopRainbow()
    {
        _rainbowTimer?.Stop();
        ApplyThemeColors(_isDark);
    }

    private void OnRainbowTick(object? sender, EventArgs e)
    {
        // Only apply rainbow to ball, not expanded panel
        if (_expanded || _snapDir != SnapDir.None) return;
        _rainbowHue = (_rainbowHue + 3) % 360;
        var color = HsvToRgb(_rainbowHue, 0.5, 1.0);
        BallBorder.Background = new SolidColorBrush(color);
    }

    private static Color HsvToRgb(double h, double s, double v)
    {
        var c = v * s;
        var x = c * (1 - Math.Abs(h / 60 % 2 - 1));
        var m = v - c;
        double r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return Color.FromRgb((byte)((r + m) * 255), (byte)((g + m) * 255), (byte)((b + m) * 255));
    }

    #endregion

    #region UI Events

    private void OnSendClick(object? sender, RoutedEventArgs e)
    {
        var selected = _vm.Devices.Where(d => d.IsSelected).ToList();
        if (selected.Count == 0) { StatusText.Text = "请选择设备"; return; }
        if (_vm.QueuedFiles == null || _vm.QueuedFiles.Length == 0) { StatusText.Text = "请拖入文件"; return; }

        StopRainbow();
        _ = _vm.SendToSelected(selected);
    }

    private void OnSettingsClick(object? sender, RoutedEventArgs e)
    {
        if (_morphInProgress || !_expanded) return;
        DoSettingsExpand();
    }

    private void OnSettingsBack(object? sender, RoutedEventArgs e)
    {
        if (_morphInProgress) return;
        DoSettingsCollapse();
    }

    private void OnBackToDashboard(object? sender, RoutedEventArgs e)
    {
        if (_morphInProgress) return;
        if (_expanded && !TransferPanel.IsVisible && !ApprovalPanel.IsVisible)
        {
            return;
        }
        if (!_expanded) return;
        DoMorphToDashboard();
    }

    private void OnCancelTransfer(object? sender, RoutedEventArgs e)
    {
        _vm?.CancelTransfer();
        ShowToast("传输已取消", ToastType.Info);
    }

    private void OnDashCopyPassword(object? sender, RoutedEventArgs e)
    {
        if (_password.HasPassword)
        {
            var top = TopLevel.GetTopLevel(this);
            top?.Clipboard?.SetTextAsync(_password.Password);
        }
    }

    private void OnDashRegeneratePassword(object? sender, RoutedEventArgs e)
    {
        _password.RegeneratePassword();
        DashPasswordDisplay.Text = _password.Password;
    }

    private void OnDashOpenDownloadFolder(object? sender, RoutedEventArgs e)
    {
        try
        {
            var path = _password.DownloadPath;
            if (!Directory.Exists(path)) Directory.CreateDirectory(path);
            System.Diagnostics.Process.Start("explorer.exe", path);
        }
        catch { }
    }

    private void OnDashRefreshDevices(object? sender, RoutedEventArgs e)
    {
        // Populate subnet list dynamically
        SubnetList.Children.Clear();

        // "All subnets" option
        var allBtn = new Button
        {
            Content = "全部网段",
            Tag = null,
            Background = Brushes.Transparent,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Left,
            Padding = new Avalonia.Thickness(10, 6),
            CornerRadius = new CornerRadius(6),
            FontSize = 12,
            Cursor = new Cursor(StandardCursorType.Hand)
        };
        allBtn.Click += OnSubnetSelected;
        SubnetList.Children.Add(allBtn);

        // Separator
        SubnetList.Children.Add(new Border
        {
            Height = 1,
            Background = TryGetBrush("BondBorder"),
            Margin = new Avalonia.Thickness(8, 4)
        });

        // Individual subnets
        var subnets = _discovery.GetAvailableSubnets();
        foreach (var subnet in subnets)
        {
            var subnetBtn = new Button
            {
                Tag = subnet.BroadcastEndpoint,
                Background = Brushes.Transparent,
                HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
                HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Left,
                Padding = new Avalonia.Thickness(10, 6),
                CornerRadius = new CornerRadius(6),
                Cursor = new Cursor(StandardCursorType.Hand)
            };

            var panel = new StackPanel { Spacing = 2 };
            panel.Children.Add(new TextBlock
            {
                Text = $"{subnet.LocalIP}/{subnet.Cidr}",
                FontSize = 12,
                Foreground = TryGetBrush("BondPrimary")
            });
            panel.Children.Add(new TextBlock
            {
                Text = subnet.Description,
                FontSize = 10,
                Foreground = TryGetBrush("BondTertiary")
            });
            subnetBtn.Content = panel;
            subnetBtn.Click += OnSubnetSelected;
            SubnetList.Children.Add(subnetBtn);
        }

        // Show popup
        SubnetPopup.IsOpen = true;
    }

    private async void OnSubnetSelected(object? sender, RoutedEventArgs e)
    {
        if (sender is not Button btn) return;

        // Close popup
        SubnetPopup.IsOpen = false;

        // Reset UI to loading state
        _vm.RefreshDevices();
        _devicesLoaded = false;
        DashLoadingIndicator.IsVisible = true;
        DashDeviceList.IsVisible = false;
        DashEmptyHint.IsVisible = false;
        _loadingTimer?.Start();

        if (btn.Tag is IPEndPoint ep)
        {
            ShowToast($"正在扫描 {ep.Address}...", ToastType.Info);
            await _discovery.ScanSubnet(ep);
        }
        else
        {
            ShowToast("正在搜索全部网段...", ToastType.Info);
            _ = Task.Run(() => _discovery.AnnounceOnce(CancellationToken.None));
        }
    }

    private void OnSettingsCopyPassword(object? sender, RoutedEventArgs e)
    {
        if (_password.HasPassword)
        {
            var top = TopLevel.GetTopLevel(this);
            top?.Clipboard?.SetTextAsync(_password.Password);
        }
    }

    private void OnSettingsRegeneratePassword(object? sender, RoutedEventArgs e)
    {
        _password.RegeneratePassword();
        SettingsPasswordDisplay.Text = _password.HasPassword ? _password.Password : "未设置";
        SettingsDisablePasswordBtn.IsEnabled = _password.HasPassword;
    }

    private void OnSettingsDisablePassword(object? sender, RoutedEventArgs e)
    {
        _password.DisablePassword();
        SettingsPasswordDisplay.Text = "未设置";
        SettingsDisablePasswordBtn.IsEnabled = false;
    }

    private async void OnSettingsBrowseFolder(object? sender, PointerPressedEventArgs e)
    {
        e.Handled = true;
        e.Pointer.Capture(null);
        try
        {
            var topLevel = TopLevel.GetTopLevel(this);
            if (topLevel == null) return;

            var folders = await topLevel.StorageProvider.OpenFolderPickerAsync(new Avalonia.Platform.Storage.FolderPickerOpenOptions
            {
                Title = "选择下载目录",
                AllowMultiple = false
            });

            if (folders.Count > 0)
            {
                var path = folders[0].Path.LocalPath;
                if (!string.IsNullOrEmpty(path))
                {
                    SettingsDownloadPath.Text = path;
                }
            }
        }
        catch { }
    }

    private void OnAutoApproveToggled(object? sender, RoutedEventArgs e)
    {
        if (sender is ToggleSwitch toggle)
            _password.SetAutoApprove(toggle.IsChecked == true);
    }

    private void OnDeviceNameChanged(object? sender, TextChangedEventArgs e)
    {
        if (sender is TextBox tb)
        {
            var name = tb.Text?.Trim();
            if (!string.IsNullOrEmpty(name))
                DeviceNameDisplay.Text = name;
        }
    }

    #region Debug Mode

    private void OnAboutPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        _aboutLongPressTimer?.Stop();
        _aboutLongPressTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
        _aboutLongPressTimer.Tick += (_, _) =>
        {
            _aboutLongPressTimer.Stop();
            _debugMode = !_debugMode;
            DebugPanel.IsVisible = _debugMode;
            AboutSubtitle.Text = _debugMode ? "调试模式已开启" : "局域网文件传输工具";
            ShowToast(_debugMode ? "调试模式已开启" : "调试模式已关闭", ToastType.Info);
        };
        _aboutLongPressTimer.Start();
    }

    private void OnAboutPointerReleased(object? sender, PointerReleasedEventArgs e)
    {
        _aboutLongPressTimer?.Stop();
    }

    private void DebugGoDashboard(object? sender, RoutedEventArgs e)
    {
        if (!_expanded) return;
        if (_showSettings) DoSettingsCollapse();
        if (_showTransfer || _showApproval) DoMorphToDashboard();
    }

    private void DebugGoTransfer(object? sender, RoutedEventArgs e)
    {
        if (!_expanded) return;
        _vm.QueuedFiles = ["debug_test.txt"];
        UpdateFileInfo(_vm.QueuedFiles);
        _vm.RefreshDevices();
        if (_showSettings) DoSettingsCollapse();
        if (!_showTransfer) DoMorphToTransfer();
    }

    private void DebugGoApproval(object? sender, RoutedEventArgs e)
    {
        if (!_expanded) return;
        if (_showSettings) DoSettingsCollapse();
        if (!_showApproval)
        {
            ApprovalPanel.IsVisible = true;
            ApprovalPanel.Opacity = 1;
            TransferPanel.IsVisible = false;
            DashboardPanel.IsVisible = false;
            SettingsPanel.IsVisible = false;
            DeviceNameDisplay.Opacity = 0;
            _showApproval = true;
            _showTransfer = false;
            _showDashboard = false;
            _showSettings = false;
            SettingsBtn.IsVisible = false;
        }
    }

    private void DebugGoSettings(object? sender, RoutedEventArgs e)
    {
        if (!_expanded) return;
        if (!_showSettings) DoSettingsExpand();
    }

    private static int _fakeDeviceCounter;

    private void DebugAddFakeDevice(object? sender, RoutedEventArgs e)
    {
        _fakeDeviceCounter++;
        var fake = new DeviceInfo
        {
            Id = $"debug-{_fakeDeviceCounter}-{Guid.NewGuid():N}",
            Name = $"测试设备 {_fakeDeviceCounter}",
            Ip = $"192.168.1.{100 + _fakeDeviceCounter}",
            Port = 19850,
            HasPassword = _fakeDeviceCounter % 2 == 0,
            Timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds()
        };
        _vm.Devices.Add(new DeviceItem(fake));
        DeviceCount.Text = $"({_vm.Devices.Count})";
        DashDeviceCount.Text = $"({_vm.Devices.Count})";
        ShowToast($"已添加: {fake.Name} ({fake.Ip})", ToastType.Info);
    }

    private void DebugClearFakeDevices(object? sender, RoutedEventArgs e)
    {
        var fakes = _vm.Devices.Where(d => d.Device.Id.StartsWith("debug-")).ToList();
        foreach (var f in fakes) _vm.Devices.Remove(f);
        _fakeDeviceCounter = 0;
        DeviceCount.Text = $"({_vm.Devices.Count})";
        DashDeviceCount.Text = $"({_vm.Devices.Count})";
        ShowToast($"已清除 {fakes.Count} 个测试设备", ToastType.Info);
    }

    private static int _fakeRequestCounter;

    private void DebugAddFakeRequest(object? sender, RoutedEventArgs e)
    {
        _fakeRequestCounter++;
        var names = new[] { "文档.pdf", "照片.jpg", "视频.mp4", "代码.zip", "音乐.mp3" };
        var sizes = new[] { 1024L * 1024 * 5, 1024L * 1024 * 12, 1024L * 1024 * 68, 1024L * 1024 * 150, 1024L * 1024 * 3 };
        var fileCount = Random.Shared.Next(1, 5);
        var files = Enumerable.Range(0, fileCount).Select(i => new FileEntry
        {
            RelativePath = names[i % names.Length],
            Size = sizes[i % sizes.Length],
            IsDirectory = false
        }).ToList();
        var totalSize = files.Sum(f => f.Size);

        var request = new TransferRequest
        {
            FromId = $"debug-req-{_fakeRequestCounter}",
            FromName = $"测试用户 {_fakeRequestCounter}",
            Files = files,
            TotalSize = totalSize,
            Password = null
        };

        var fileList = string.Join(", ", files.Take(3).Select(f => System.IO.Path.GetFileName(f.RelativePath)));
        if (files.Count > 3) fileList += "...";

        _vm.PendingRequests.Add(new PendingRequest
        {
            Request = request,
            FromName = request.FromName,
            FromIp = $"192.168.1.{200 + _fakeRequestCounter}",
            FileCount = fileCount,
            TotalSize = TransferViewModel.FormatSize(totalSize),
            FileList = fileList,
            RemainingSeconds = 30
        });

        OnIncomingRequest();
        ShowToast($"已添加测试请求: {request.FromName}", ToastType.Info);
    }

    private void DebugClearFakeRequests(object? sender, RoutedEventArgs e)
    {
        var fakes = _vm.PendingRequests.Where(r => r.Request.FromId.StartsWith("debug-req-")).ToList();
        foreach (var f in fakes) _vm.PendingRequests.Remove(f);
        _fakeRequestCounter = 0;
        ShowToast($"已清除 {fakes.Count} 个测试请求", ToastType.Info);
    }

    #endregion

    private void DoSettingsExpand()
    {
        _hGen++; Sc = 1;
        var tw = BallBorder.Width + _ballPad * 2;
        var th = BallBorder.Height + _ballPad * 2;
        _cx = Position.X + DipToPx(tw) / 2;
        _cy = Position.Y + DipToPx(th) / 2;
        _scx = _cx; _scy = _cy; _tcx = _cx; _tcy = _cy;
        _fW = BallBorder.Width; _tW = SettingsWidthDip;
        _fH = BallBorder.Height; _tH = SettingsHeightDip;
        _fCR = BallBorder.CornerRadius.TopLeft; _tCR = PanelCR;
        _iFx = _mTrans?.X ?? 0; _iFy = _mTrans?.Y ?? 0; _iFs = _mScale?.ScaleX ?? 1;
        _iTx = _icoEndTx; _iTy = _icoEndTy; _iTs = _icoEndSc;

        // Hide other panels, prepare settings
        DeviceNameDisplay.Opacity = 0;
        ApprovalPanel.IsVisible = false; ApprovalPanel.Opacity = 0;
        TransferPanel.IsVisible = false; TransferPanel.Opacity = 0;
        DashboardPanel.IsVisible = false; DashboardPanel.Opacity = 0;
        SettingsBtn.IsVisible = false;

        SettingsDeviceNameBox.Text = _password.DeviceName;
        SettingsPasswordDisplay.Text = _password.HasPassword ? _password.Password : "未设置";
        SettingsDisablePasswordBtn.IsEnabled = _password.HasPassword;
        SettingsDownloadPath.Text = _password.DownloadPath;
        SettingsPort.Text = _password.TransferPort.ToString();
        SettingsAutoApprove.IsChecked = _password.AutoApprove;

        // Set theme toggle to current state
        var suki = SukiUI.SukiTheme.GetInstance(Application.Current!);
        SettingsThemeToggle.IsChecked = suki.ActiveBaseTheme == Avalonia.Styling.ThemeVariant.Dark;

        SettingsPanel.IsVisible = true;
        SettingsPanel.Opacity = 0;
        _showSettings = true;
        _showTransfer = false;
        _showApproval = false;
        _showDashboard = false;
        _contentFadeIn = true;

        StartMorph(true);
    }

    private void DoSettingsCollapse()
    {
        SubnetPopup.IsOpen = false;
        _hGen++; Sc = 1;
        var tw = BallBorder.Width + _ballPad * 2;
        var th = BallBorder.Height + _ballPad * 2;
        _cx = Position.X + DipToPx(tw) / 2;
        _cy = Position.Y + DipToPx(th) / 2;
        _scx = _cx; _scy = _cy; _tcx = _cx; _tcy = _cy;
        _fW = BallBorder.Width; _tW = _panelW;
        _fH = BallBorder.Height; _tH = _panelH;
        _fCR = BallBorder.CornerRadius.TopLeft; _tCR = PanelCR;
        _iFx = _mTrans?.X ?? 0; _iFy = _mTrans?.Y ?? 0; _iFs = _mScale?.ScaleX ?? 1;
        _iTx = _icoEndTx; _iTy = _icoEndTy; _iTs = _icoEndSc;

        // Save device name if changed
        var newName = SettingsDeviceNameBox.Text?.Trim();
        if (!string.IsNullOrEmpty(newName) && newName != _password.DeviceName)
        {
            _password.SetDeviceName(newName);
            DeviceNameDisplay.Text = newName;
        }

        // Save download path if changed
        var newDownloadPath = SettingsDownloadPath.Text?.Trim();
        if (!string.IsNullOrEmpty(newDownloadPath) && newDownloadPath != _password.DownloadPath)
        {
            _password.SetDownloadPath(newDownloadPath);
        }

        // Save port if changed
        if (int.TryParse(SettingsPort.Text?.Trim(), out var newPort) && newPort != _password.TransferPort)
        {
            _password.SetTransferPort(newPort);
        }

        // Hide settings immediately, fade in dashboard
        SettingsPanel.IsVisible = false;
        SettingsPanel.Opacity = 0;
        DashboardPanel.IsVisible = true;
        DashboardPanel.Opacity = 0;
        _showSettings = false;
        _showDashboard = true;
        _contentFadeIn = true;
        DashPasswordDisplay.Text = _password?.HasPassword == true ? _password.Password : "未设置";

        _devicesLoaded = false;
        DashLoadingIndicator.IsVisible = true;
        DashDeviceList.IsVisible = false;
        DashEmptyHint.IsVisible = false;
        _loadingTimer?.Start();

        StartMorph(true);
    }

    private void DoMorphToTransfer()
    {
        _hGen++; Sc = 1;
        var tw = BallBorder.Width + _ballPad * 2;
        var th = BallBorder.Height + _ballPad * 2;
        _cx = Position.X + DipToPx(tw) / 2;
        _cy = Position.Y + DipToPx(th) / 2;
        _scx = _cx; _scy = _cy; _tcx = _cx; _tcy = _cy;
        _fW = BallBorder.Width; _tW = TransferWidthDip;
        _fH = BallBorder.Height; _tH = TransferHeightDip;
        _fCR = BallBorder.CornerRadius.TopLeft; _tCR = PanelCR;
        _iFx = _mTrans?.X ?? 0; _iFy = _mTrans?.Y ?? 0; _iFs = _mScale?.ScaleX ?? 1;
        _iTx = _icoEndTx; _iTy = _icoEndTy; _iTs = _icoEndSc;

        DashboardPanel.IsVisible = false; DashboardPanel.Opacity = 0;
        SettingsPanel.IsVisible = false; SettingsPanel.Opacity = 0;
        ApprovalPanel.IsVisible = false; ApprovalPanel.Opacity = 0;
        TransferPanel.IsVisible = true; TransferPanel.Opacity = 0;
        TransferSelectMode.IsVisible = true; TransferSelectMode.Opacity = 1;
        TransferProgressMode.IsVisible = false; TransferProgressMode.Opacity = 0;
        _showTransferProgress = false;
        SettingsBtn.IsVisible = false;
        DeviceNameDisplay.Opacity = 0;

        _showTransfer = true;
        _showDashboard = false;
        _showSettings = false;
        _showApproval = false;
        _contentFadeIn = true;

        StartMorph(true);
    }

    private void DoMorphToDashboard()
    {
        _hGen++; Sc = 1;
        var tw = BallBorder.Width + _ballPad * 2;
        var th = BallBorder.Height + _ballPad * 2;
        _cx = Position.X + DipToPx(tw) / 2;
        _cy = Position.Y + DipToPx(th) / 2;
        _scx = _cx; _scy = _cy; _tcx = _cx; _tcy = _cy;
        _fW = BallBorder.Width; _tW = _panelW;
        _fH = BallBorder.Height; _tH = _panelH;
        _fCR = BallBorder.CornerRadius.TopLeft; _tCR = PanelCR;
        _iFx = _mTrans?.X ?? 0; _iFy = _mTrans?.Y ?? 0; _iFs = _mScale?.ScaleX ?? 1;
        _iTx = _icoEndTx; _iTy = _icoEndTy; _iTs = _icoEndSc;

        TransferPanel.IsVisible = false; TransferPanel.Opacity = 0;
        TransferSelectMode.IsVisible = true; TransferSelectMode.Opacity = 1;
        TransferProgressMode.IsVisible = false; TransferProgressMode.Opacity = 0;
        _showTransferProgress = false;
        SettingsPanel.IsVisible = false; SettingsPanel.Opacity = 0;
        ApprovalPanel.IsVisible = false; ApprovalPanel.Opacity = 0;
        DashboardPanel.IsVisible = true; DashboardPanel.Opacity = 0;
        DeviceNameDisplay.Opacity = 0;
        SettingsBtn.IsVisible = true;

        _showTransfer = false;
        _showApproval = false;
        _showSettings = false;
        _showDashboard = true;
        _contentFadeIn = true;
        DashPasswordDisplay.Text = _password?.HasPassword == true ? _password.Password : "未设置";

        _devicesLoaded = false;
        DashLoadingIndicator.IsVisible = true;
        DashDeviceList.IsVisible = false;
        DashEmptyHint.IsVisible = false;
        _loadingTimer?.Start();

        StartMorph(true);
    }

    #endregion

    #region Position

    private void RestorePosition()
    {
        try
        {
            if (!File.Exists(PosFile)) return;
            var p = File.ReadAllText(PosFile).Split(',');
            if (p.Length == 2 && int.TryParse(p[0], out var x) && int.TryParse(p[1], out var y))
            {
                Position = new PixelPoint(x, y);
                var s = Screens.ScreenFromPoint(Position);
                if (s?.WorkingArea is { } wa)
                {
                    // AutoSnapIfOffScreen handles snap detection
                }
            }
        }
        catch { }
    }

    private void SavePos()
    {
        try
        {
            var dir = Path.GetDirectoryName(PosFile)!;
            if (!Directory.Exists(dir)) Directory.CreateDirectory(dir);
            File.WriteAllText(PosFile, $"{Position.X},{Position.Y}");
        }
        catch { }
    }

    private void ClampToScreen()
    {
        var scr = Screens.ScreenFromPoint(Position);
        if (scr?.WorkingArea is not { } wa) return;
        var minVisible = DipToPx(16);
        var x = Math.Clamp(Position.X, wa.X - _ballWinW + minVisible, wa.X + wa.Width - minVisible);
        var y = Math.Clamp(Position.Y, wa.Y, wa.Y + wa.Height - minVisible);
        if (x != Position.X || y != Position.Y) Position = new PixelPoint(x, y);
    }

    public void ResetPosition()
    {
        var s = Screens.Primary;
        if (s?.WorkingArea is not { } wa) return;
        _isPill = false; _pillGen++;
        Position = new PixelPoint(wa.X + wa.Width / 2 - _ballWinW / 2,
                                  wa.Y + wa.Height / 2 - _ballWinH / 2);
        BallBorder.Width = _ballSize; BallBorder.Height = _ballSize;
        BallBorder.CornerRadius = new CornerRadius(BallCR);
        if (BallBorder.Clip is RectangleGeometry clip)
        { clip.Rect = new Rect(0, 0, _ballSize, _ballSize); clip.RadiusX = BallCR; clip.RadiusY = BallCR; }
        SavePos();
    }

    #endregion

    #region Drag (pointer)

    private void OnPointerPressed(object? s, PointerPressedEventArgs e)
    {
        if (e.GetCurrentPoint(this).Properties.IsRightButtonPressed) return;
        _ptrDown = true; _dragging = false;
        _ptrStart = this.PointToScreen(e.GetPosition(this));
        _winStart = Position;
        if (s is Control c) e.Pointer.Capture(c);
    }

    private void OnPointerMoved(object? s, PointerEventArgs e)
    {
        if (!_ptrDown) return;
        var cur = this.PointToScreen(e.GetPosition(this));
        var dx = cur.X - _ptrStart.X;
        var dy = cur.Y - _ptrStart.Y;
        if (!_dragging && (Math.Abs(dx) > _dragThresh || Math.Abs(dy) > _dragThresh))
        {
            _dragging = true;
            if (_snapDir != SnapDir.None)
            {
                // Pill drag: reset start position to current, but keep pill shape
                _ptrStart = cur;
                _winStart = Position;
            }
        }
        if (_dragging)
        {
            var x = _winStart.X + dx;
            var y = _winStart.Y + dy;
            var wa = GetScreenBounds();
            if (wa != default)
            {
                if (_expanded)
                {
                    var halfW = _ballWinW / 2;
                    var halfH = _ballWinH / 2;
                    x = Math.Clamp(x, wa.X - halfW, wa.X + wa.Width - halfW);
                    y = Math.Clamp(y, wa.Y, wa.Y + wa.Height - halfH);
                }
                else if (_snapDir != SnapDir.None)
                {
                    // Pill drag: allow free movement on screen, update orientation in real-time
                    var padPx = DipToPx(_ballPad);
                    var ballPx = DipToPx(_ballSize);
                    x = Math.Clamp(x, wa.X - padPx, wa.X + wa.Width - padPx - ballPx);
                    y = Math.Clamp(y, wa.Y - padPx, wa.Y + wa.Height - padPx - ballPx);

                    // Detect which edge is closest and update pill orientation
                    var pillW = (int)DipToPx(PillWidthDip);
                    var pillH = (int)DipToPx(PillWidthDip);
                    var cx = x + _ballWinW / 2;
                    var cy = y + _ballWinH / 2;
                    var distL = Math.Abs(cx - wa.X);
                    var distR = Math.Abs(cx - (wa.X + wa.Width));
                    var distT = Math.Abs(cy - wa.Y);
                    var distB = Math.Abs(cy - (wa.Y + wa.Height));
                    var minDist = Math.Min(Math.Min(distL, distR), Math.Min(distT, distB));

                    SnapDir newDir;
                    if (minDist == distL) newDir = SnapDir.Left;
                    else if (minDist == distR) newDir = SnapDir.Right;
                    else if (minDist == distT) newDir = SnapDir.Top;
                    else newDir = SnapDir.Bottom;

                    if (newDir != _snapDir)
                    {
                        ReorientPill(newDir);
                    }
                }
                else
                {
                    var padPx = DipToPx(_ballPad);
                    var ballPx = DipToPx(_ballSize);
                    x = Math.Clamp(x, wa.X - padPx, wa.X + wa.Width - padPx - ballPx);
                    y = Math.Clamp(y, wa.Y - padPx, wa.Y + wa.Height - padPx - ballPx);
                }
            }
            Position = new PixelPoint(x, y);
        }
    }

    private void OnPointerReleased(object? s, PointerReleasedEventArgs e)
    {
        if (!_ptrDown) return;
        _ptrDown = false;
        if (_dragging)
        {
            _dragging = false;
            if (_snapDir != SnapDir.None)
            {
                // Was dragging a pill — check if still near an edge
                var wa = GetScreenBounds();
                if (wa != default)
                {
                    var distL = Math.Abs(Position.X - wa.X);
                    var distR = Math.Abs((Position.X + _ballWinW) - (wa.X + wa.Width));
                    var distT = Math.Abs(Position.Y - wa.Y);
                    var distB = Math.Abs((Position.Y + _ballWinH) - (wa.Y + wa.Height));
                    var minDist = Math.Min(Math.Min(distL, distR), Math.Min(distT, distB));
                    var esPx = DipToPx(_edgeSnap);

                    if (minDist <= esPx)
                    {
                        // Still near edge — re-snap
                        if (minDist == distL) _snapDir = SnapDir.Left;
                        else if (minDist == distR) _snapDir = SnapDir.Right;
                        else if (minDist == distT) _snapDir = SnapDir.Top;
                        else _snapDir = SnapDir.Bottom;
                        MorphToPill();
                        SnapFlush();
                    }
                    else
                    {
                        // Dragged away from edge — animated morph to ball
                        _snapDir = SnapDir.None;
                        MorphToBall();
                    }
                }
            }
            else if (!_expanded)
            {
                // Normal ball drag — check if near edge for snap
                SnapAndSlide();
            }
            SavePos();
        }
        else Click();
        e.Pointer.Capture(null);
    }

    #endregion

    #region Edge snap

    private void SnapAndSlide()
    {
        var wa = GetScreenBounds();
        if (wa == default) return;
        var esPx = DipToPx(_edgeSnap);

        var distL = Math.Abs(Position.X - wa.X);
        var distR = Math.Abs((Position.X + _ballWinW) - (wa.X + wa.Width));
        var distT = Math.Abs(Position.Y - wa.Y);
        var distB = Math.Abs((Position.Y + _ballWinH) - (wa.Y + wa.Height));
        var minDist = Math.Min(Math.Min(distL, distR), Math.Min(distT, distB));

        if (minDist > esPx) return;

        if (minDist == distL) _snapDir = SnapDir.Left;
        else if (minDist == distR) _snapDir = SnapDir.Right;
        else if (minDist == distT) _snapDir = SnapDir.Top;
        else _snapDir = SnapDir.Bottom;

        // Calculate target position — pill flush with edge, fully visible
        var pad = (int)DipToPx(_ballPad);
        var pillW = (int)DipToPx(PillWidthDip);
        var pillH = (int)DipToPx(PillWidthDip);
        int targetX = _snapDir switch
        {
            SnapDir.Left => wa.X - pad,
            SnapDir.Right => wa.X + wa.Width - pad - pillW,
            _ => Position.X
        };
        int targetY = _snapDir switch
        {
            SnapDir.Top => wa.Y - pad,
            SnapDir.Bottom => wa.Y + wa.Height - pad - pillH,
            _ => Position.Y
        };

        MorphToPill();

        // Animate slide to edge
        var startX = Position.X; var startY = Position.Y;
        var gen = ++_slideGen; var sw = System.Diagnostics.Stopwatch.StartNew();
        void Tick()
        {
            if (_slideGen != gen) return;
            var t = Math.Min(sw.ElapsedMilliseconds / 300.0, 1.0);
            var eased = EaseOutCubic(t);
            Position = new PixelPoint(
                (int)(startX + (targetX - startX) * eased),
                (int)(startY + (targetY - startY) * eased));
            if (t < 1) Dispatcher.UIThread.Post(Tick, DispatcherPriority.Render);
            else SnapFlush(); // Final correction
        }
        Dispatcher.UIThread.Post(Tick, DispatcherPriority.Render);
    }

    private uint _slideGen;

    private void OnPointerEntered(object? s, PointerEventArgs e)
    {
        // Only scale hover — no morph, no slide
        if (!_expanded && !_morphInProgress) HoverIn();
    }

    private void OnPointerExited(object? s, PointerEventArgs e)
    {
        if (!_expanded && !_morphInProgress) HoverOut();
    }

    private void AutoSnapIfOffScreen()
    {
        var wa = GetScreenBounds();
        if (wa == default) return;

        // Check if any part of the ball is off-screen
        var ballL = Position.X;
        var ballR = Position.X + _ballWinW;
        var ballT = Position.Y;
        var ballB = Position.Y + _ballWinH;
        if (ballL >= wa.X && ballR <= wa.X + wa.Width && ballT >= wa.Y && ballB <= wa.Y + wa.Height)
            return; // Fully on screen

        // Find nearest edge
        var distL = ballL - wa.X;         // distance from left edge (negative = off-screen)
        var distR = wa.X + wa.Width - ballR; // distance from right edge
        var distT = ballT - wa.Y;
        var distB = wa.Y + wa.Height - ballB;
        var minDist = Math.Min(Math.Min(distL, distR), Math.Min(distT, distB));

        if (minDist == distL) _snapDir = SnapDir.Left;
        else if (minDist == distR) _snapDir = SnapDir.Right;
        else if (minDist == distT) _snapDir = SnapDir.Top;
        else _snapDir = SnapDir.Bottom;

        // Move ball to be fully on screen first, then snap
        Position = new PixelPoint(
            Math.Clamp(Position.X, wa.X, wa.X + wa.Width - _ballWinW),
            Math.Clamp(Position.Y, wa.Y, wa.Y + wa.Height - _ballWinH));

        MorphToPill();
        SnapFlush();
        SavePos();
    }

    #endregion

    #region Hover

    private void HoverIn()
    {
        if (_expanded || _morphInProgress) return;
        var c = Sc; if (Math.Abs(c - 1.06) < 0.001) return;
        _hFrom = c; _hTo = 1.06; RunHover();
    }

    private void HoverOut()
    {
        var c = Sc; if (Math.Abs(c - 1.0) < 0.001) return;
        _hFrom = c; _hTo = 1.0; RunHover();
    }

    private void RunHover()
    {
        _hGen++; _hT = 0; _hTick = 0;
        RequestAnimationFrame(HoverTick);
    }

    private void HoverTick(TimeSpan now)
    {
        var g = _hGen;
        if (!Adv(now, ref _hTick, ref _hT, 0.3)) { if (_hGen == g) RequestAnimationFrame(HoverTick); return; }
        var t = Clamp01(SpringNorm(_hT, 0.6, 12, ComputeSpringEnd(0.6, 12)));
        Sc = _hFrom + (_hTo - _hFrom) * t;
        if (_hT < 1 && _hGen == g) RequestAnimationFrame(HoverTick);
    }

    private double Sc
    {
        get => BallBorder.RenderTransform is ScaleTransform st ? st.ScaleX : 1;
        set { if (BallBorder.RenderTransform is ScaleTransform st) { st.ScaleX = value; st.ScaleY = value; } }
    }

    // Pill morph: ball ? thin pill when edge-snapped
    private void MorphToPill()
    {
        if (_isPill || _expanded || _morphInProgress) return;
        _isPill = true;
        var isHoriz = _snapDir is SnapDir.Top or SnapDir.Bottom;
        _pillFromW = BallBorder.Width; _pillToW = isHoriz ? PillHeightDip : PillWidthDip;
        _pillFromH = BallBorder.Height; _pillToH = isHoriz ? PillWidthDip : PillHeightDip;
        _pillFromCR = BallBorder.CornerRadius.TopLeft; _pillToCR = PillCR;
        _pillGen++; _pillT = 0; _pillTick = 0;
        _pillSquash = true;
        RequestAnimationFrame(PillTick);
    }

    private void MorphToBall()
    {
        if (!_isPill || _expanded || _morphInProgress) return;
        _isPill = false;
        _pillFromW = BallBorder.Width; _pillToW = _ballSize;
        _pillFromH = BallBorder.Height; _pillToH = _ballSize;
        _pillFromCR = BallBorder.CornerRadius.TopLeft; _pillToCR = BallCR;
        _pillGen++; _pillT = 0; _pillTick = 0;
        _pillSquash = false;
        RequestAnimationFrame(PillTick);
    }

    private bool _pillSquash;

    private void ReorientPill(SnapDir newDir)
    {
        var isHoriz = newDir is SnapDir.Top or SnapDir.Bottom;
        var newW = isHoriz ? PillHeightDip : PillWidthDip;
        var newH = isHoriz ? PillWidthDip : PillHeightDip;
        // Skip if already correct dimensions
        if (Math.Abs(BallBorder.Width - newW) < 0.5 && Math.Abs(BallBorder.Height - newH) < 0.5)
            return;
        _pillFromW = BallBorder.Width; _pillToW = newW;
        _pillFromH = BallBorder.Height; _pillToH = newH;
        _pillFromCR = BallBorder.CornerRadius.TopLeft; _pillToCR = PillCR;
        _pillGen++; _pillT = 0; _pillTick = 0;
        _pillSquash = true;
        _isPill = true;
        _snapDir = newDir;
        RequestAnimationFrame(PillTick);
    }

    private void PillTick(TimeSpan now)
    {
        var g = _pillGen;
        if (!Adv(now, ref _pillTick, ref _pillT, 0.6)) { if (_pillGen == g) RequestAnimationFrame(PillTick); return; }
        var t = Clamp01(SpringNorm(_pillT, 0.32, 5.5, ComputeSpringEnd(0.32, 5.5)));

        var tw = _pillSquash ? Clamp01(SpringNorm(Math.Min(_pillT * 1.1, 1), 0.32, 5.5, ComputeSpringEnd(0.32, 5.5))) : t;
        var th = _pillSquash ? t : Clamp01(SpringNorm(Math.Min(_pillT * 1.1, 1), 0.32, 5.5, ComputeSpringEnd(0.32, 5.5)));

        var w = L(_pillFromW, _pillToW, tw);
        var h = L(_pillFromH, _pillToH, th);
        var cr = L(_pillFromCR, _pillToCR, t);

        BallBorder.Width = w;
        BallBorder.Height = h;
        BallBorder.CornerRadius = new CornerRadius(cr);
        if (BallBorder.Clip is RectangleGeometry clip)
        {
            clip.Rect = new Rect(0, 0, w, h);
            clip.RadiusX = cr;
            clip.RadiusY = cr;
        }

        var iconScale = Math.Min(w, h) / _ballSize;
        if (_mScale != null) { _mScale.ScaleX = iconScale; _mScale.ScaleY = iconScale; }

        // Morph complete — snap to exact flush position
        if (_pillT >= 1 && _snapDir != SnapDir.None)
            SnapFlush();

        if (_pillT < 1 && _pillGen == g) RequestAnimationFrame(PillTick);
    }

    private void SnapFlush()
    {
        var wa = GetScreenBounds();
        if (wa == default) return;
        var pad = (int)DipToPx(_ballPad);
        var pillW = (int)DipToPx(PillWidthDip);  // 14 DIP (vertical) or used for horizontal
        var pillH = (int)DipToPx(PillWidthDip);

        // Pill fully visible, outer edge flush with screen edge
        // Pill is at offset (pad, pad) inside the window
        // For left snap: pill left edge = wa.X → window left = wa.X - pad
        // For right snap: pill right edge = wa.X+W → window left = wa.X+W - pad - pillW
        Position = _snapDir switch
        {
            SnapDir.Left =>   new PixelPoint(wa.X - pad, Position.Y),
            SnapDir.Right =>  new PixelPoint(wa.X + wa.Width - pad - pillW, Position.Y),
            SnapDir.Top =>    new PixelPoint(Position.X, wa.Y - pad),
            SnapDir.Bottom => new PixelPoint(Position.X, wa.Y + wa.Height - pad - pillH),
            _ => Position
        };
    }

    private PixelRect GetScreenBounds()
    {
        var scr = Screens.ScreenFromPoint(Position) ?? Screens.Primary;
        return scr?.WorkingArea ?? default;
    }

    #endregion

    #region Morph

    private void Click()
    {
        if (_morphInProgress) { ReverseMorph(); return; }
        if (_expanded)
        {
            SubnetPopup.IsOpen = false;
            if (_showSettings) DoSettingsCollapse();
            else DoCollapse();
        }
        else
        {
            var fromSnap = _snapDir;
            // Kill pill animation but DON'T reset shape — let DoExpand morph from pill→panel
            _snapDir = SnapDir.None;
            _isPill = false;
            _pillGen++;
            if (_vm.PendingRequests.Count > 0)
                DoExpand(showApproval: true, fromSnap: fromSnap);
            else
                DoExpand(fromSnap: fromSnap);
        }
    }

    private SnapDir _expandFromSnap; // Remember which edge we expanded from

    private void DoExpand(bool withTransfer = false, bool showApproval = false, SnapDir fromSnap = SnapDir.None)
    {
        _expandFromSnap = fromSnap;
        _morphFromPill = fromSnap != SnapDir.None;
        _hGen++; Sc = 1;
        var panelW = withTransfer ? TransferWidthDip : _panelW;
        var panelH = withTransfer ? TransferHeightDip : _panelH;

        // Current center (from pill or ball position)
        _cx = Position.X + DipToPx(BallBorder.Width + _ballPad * 2) / 2;
        _cy = Position.Y + DipToPx(BallBorder.Height + _ballPad * 2) / 2;
        _scx = _cx; _scy = _cy; _tcx = _cx; _tcy = _cy;

        // Target center: where the panel should end up
        _tcx = _cx; _tcy = _cy;

        var wa = GetScreenBounds();
        if (fromSnap != SnapDir.None && wa != default)
        {
            var halfPanelW = DipToPx(panelW) / 2;
            var halfPanelH = DipToPx(panelH) / 2;
            var margin = DipToPx(24);

            // Constrain target center so panel stays on screen
            switch (fromSnap)
            {
                case SnapDir.Left:
                    _tcx = Math.Max(_tcx, wa.X + halfPanelW + margin);
                    break;
                case SnapDir.Right:
                    _tcx = Math.Min(_tcx, wa.X + wa.Width - halfPanelW - margin);
                    break;
                case SnapDir.Top:
                    _tcy = Math.Max(_tcy, wa.Y + halfPanelH + margin);
                    break;
                case SnapDir.Bottom:
                    _tcy = Math.Min(_tcy, wa.Y + wa.Height - halfPanelH - margin);
                    break;
            }
        }
        else
        {
            // Normal ball→panel: center stays fixed
            _tcx = _cx; _tcy = _cy;
        }

        // Morph FROM current shape TO panel
        _fW = BallBorder.Width;
        _fH = BallBorder.Height;
        _fCR = BallBorder.CornerRadius.TopLeft; _tCR = PanelCR;
        _iFx = _mTrans?.X ?? 0; _iFy = _mTrans?.Y ?? 0; _iFs = _mScale?.ScaleX ?? 1;
        _iTx = _icoEndTx; _iTy = _icoEndTy; _iTs = _icoEndSc;
        _tW = panelW; _tH = panelH;
        _scx = _cx; _scy = _cy;

        // Reset icon scale if coming from pill (icon was scaled down)
        if (_mScale != null && _mScale.ScaleX < 0.9)
        {
            _iFx = 0; _iFy = 0; _iFs = _mScale.ScaleX;
        }

        DeviceNameDisplay.Text = _password?.DeviceName ?? Environment.MachineName;
        _showTransfer = withTransfer;
        _showApproval = showApproval;
        _showSettings = false;

        if (showApproval)
        {
            DeviceNameDisplay.Opacity = 0;
            ApprovalPanel.IsVisible = true;
            TransferPanel.IsVisible = false;
            TransferPanel.Opacity = 0;
            SettingsPanel.IsVisible = false;
            DashboardPanel.IsVisible = false;
            SettingsBtn.IsVisible = false;
        }
        else if (withTransfer)
        {
            DeviceNameDisplay.Opacity = 0;
            ApprovalPanel.IsVisible = false;
            TransferPanel.IsVisible = true;
            TransferPanel.Opacity = 0;
            TransferSelectMode.IsVisible = true;
            TransferSelectMode.Opacity = 1;
            TransferProgressMode.IsVisible = false;
            TransferProgressMode.Opacity = 0;
            _showTransferProgress = false;
            SettingsPanel.IsVisible = false;
            DashboardPanel.IsVisible = false;
            SettingsBtn.IsVisible = false;
            UpdateFileInfo(_vm!.QueuedFiles!);
        }
        else
        {
            DeviceNameDisplay.Opacity = 0;
            ApprovalPanel.IsVisible = false;
            TransferPanel.IsVisible = false;
            TransferPanel.Opacity = 0;
            SettingsPanel.IsVisible = false;
            DashboardPanel.IsVisible = true;
            DashboardPanel.Opacity = 0;
            SettingsBtn.IsVisible = true;
            _showDashboard = true;
            DashPasswordDisplay.Text = _password?.HasPassword == true ? _password.Password : "未设置";
        }

        PanelContent.IsVisible = true;
        PanelContent.Opacity = 0;
        _contentFadeIn = true;

        if (!withTransfer && !showApproval)
        {
            _devicesLoaded = false;
            DashLoadingIndicator.IsVisible = true;
            DashDeviceList.IsVisible = false;
            DashEmptyHint.IsVisible = false;
            _loadingTimer?.Start();
        }

        _vm?.RefreshDevices();
        StartMorph(true);
    }

    private void DoCollapse()
    {
        SubnetPopup.IsOpen = false;
        _morphFromPill = _expandFromSnap != SnapDir.None;
        _hGen++; Sc = 1;
        var tw = BallBorder.Width + _ballPad * 2;
        var th = BallBorder.Height + _ballPad * 2;
        _cx = Position.X + DipToPx(tw) / 2;
        _cy = Position.Y + DipToPx(th) / 2;
        _scx = _cx; _scy = _cy; _tcx = _cx; _tcy = _cy;

        if (_expandFromSnap != SnapDir.None)
        {
            var isHoriz = _expandFromSnap is SnapDir.Top or SnapDir.Bottom;
            _fW = BallBorder.Width; _tW = isHoriz ? PillHeightDip : PillWidthDip;
            _fH = BallBorder.Height; _tH = isHoriz ? PillWidthDip : PillHeightDip;
            _fCR = BallBorder.CornerRadius.TopLeft; _tCR = PillCR;

            // Target center = pill position at edge
            var wa = GetScreenBounds();
            if (wa != default)
            {
                var pad = DipToPx(_ballPad);
                var pillWinW = DipToPx(_tW + _ballPad * 2);
                var pillWinH = DipToPx(_tH + _ballPad * 2);
                var pillWPx = DipToPx(_tW);
                var pillHPx = DipToPx(_tH);
                // Center = window position + half window size
                // Window position matches SnapFlush: edge - pad
                switch (_expandFromSnap)
                {
                    case SnapDir.Left:
                        _tcx = wa.X - pad + pillWinW / 2; _tcy = _cy;
                        break;
                    case SnapDir.Right:
                        _tcx = wa.X + wa.Width - pad - pillWPx + pillWinW / 2; _tcy = _cy;
                        break;
                    case SnapDir.Top:
                        _tcx = _cx; _tcy = wa.Y - pad + pillWinH / 2;
                        break;
                    case SnapDir.Bottom:
                        _tcx = _cx; _tcy = wa.Y + wa.Height - pad - pillHPx + pillWinH / 2;
                        break;
                }
            }
        }
        else
        {
            _fW = BallBorder.Width; _tW = _ballSize;
            _fH = BallBorder.Height; _tH = _ballSize;
            _fCR = BallBorder.CornerRadius.TopLeft; _tCR = BallCR;
            _tcx = _cx; _tcy = _cy; // Center stays fixed
        }

        _scx = _cx; _scy = _cy;

        _iFx = _mTrans?.X ?? 0; _iFy = _mTrans?.Y ?? 0; _iFs = _mScale?.ScaleX ?? 1;
        _iTx = IcoStartTx; _iTy = IcoStartTy; _iTs = IcoStartSc;

        _contentFadeIn = false;
        _showApproval = false;
        _showDashboard = false;
        _showTransferProgress = false;
        ApprovalPanel.IsVisible = false;
        TransferPanel.IsVisible = false;
        TransferProgressMode.IsVisible = false;
        TransferSelectMode.IsVisible = true;
        TransferSelectMode.Opacity = 1;
        _vm.QueuedFiles = null;
        _loadingTimer?.Stop();
        StopRainbow();
        StartMorph(false);
    }

    private void ReverseMorph()
    {
        _crossfadeGen++;

        _fW = BallBorder.Width; _fH = BallBorder.Height;
        _fCR = BallBorder.CornerRadius.TopLeft;
        _mDir = !_mDir;
        if (_mDir)
        {
            if (_showSettings) { _tW = SettingsWidthDip; _tH = SettingsHeightDip; }
            else if (_showTransfer) { _tW = TransferWidthDip; _tH = TransferHeightDip; }
            else { _tW = _panelW; _tH = _panelH; }
            _tCR = PanelCR;
            _morphFromPill = false;
            PanelContent.IsVisible = true;
        }
        else
        {
            if (_expandFromSnap != SnapDir.None)
            {
                // Reverse back to pill, not ball
                var isHoriz = _expandFromSnap is SnapDir.Top or SnapDir.Bottom;
                _tW = isHoriz ? PillHeightDip : PillWidthDip;
                _tH = isHoriz ? PillWidthDip : PillHeightDip;
                _tCR = PillCR;
                _morphFromPill = true;

                // Set target center to pill position at edge
                var wa = GetScreenBounds();
                if (wa != default)
                {
                    var pillWinW = DipToPx(_tW + _ballPad * 2);
                    var pillWinH = DipToPx(_tH + _ballPad * 2);
                    _cx = Position.X + DipToPx(BallBorder.Width + _ballPad * 2) / 2;
                    _cy = Position.Y + DipToPx(BallBorder.Height + _ballPad * 2) / 2;
                    _scx = _cx; _scy = _cy;
                    switch (_expandFromSnap)
                    {
                        case SnapDir.Left: _tcx = wa.X + pillWinW / 2; _tcy = _cy; break;
                        case SnapDir.Right: _tcx = wa.X + wa.Width - pillWinW / 2; _tcy = _cy; break;
                        case SnapDir.Top: _tcx = _cx; _tcy = wa.Y + pillWinH / 2; break;
                        case SnapDir.Bottom: _tcx = _cx; _tcy = wa.Y + wa.Height - pillWinH / 2; break;
                    }
                }
            }
            else
            {
                _tW = _ballSize; _tH = _ballSize; _tCR = BallCR;
                _morphFromPill = false;
            }
        }

        if (_mDir || _expandFromSnap == SnapDir.None)
        {
            var tw = BallBorder.Width + _ballPad * 2;
            var th = BallBorder.Height + _ballPad * 2;
            _cx = Position.X + DipToPx(tw) / 2;
            _cy = Position.Y + DipToPx(th) / 2;
            _scx = _cx; _scy = _cy; _tcx = _cx; _tcy = _cy;
        }

        _iFx = _mTrans?.X ?? 0; _iFy = _mTrans?.Y ?? 0; _iFs = _mScale?.ScaleX ?? 1;
        if (_mDir) { _iTx = _icoEndTx; _iTy = _icoEndTy; _iTs = _icoEndSc; _contentFadeIn = true; }
        else { _iTx = IcoStartTx; _iTy = IcoStartTy; _iTs = IcoStartSc; _contentFadeIn = false; }
        _mT = 0; _mTick = 0;
    }

    private void StartMorph(bool expand)
    {
        _mGen++; _mDir = expand; _mT = 0; _mTick = 0;
        _lastW = double.NaN; _lastItx = double.NaN;
        _morphInProgress = true;
        // Cancel all active toasts before hiding container
        foreach (var child in ToastContainer.Children)
        {
            if (child is Border b && b.Tag is CancellationTokenSource cts)
                cts.Cancel();
        }
        ToastContainer.IsVisible = false;
        RequestAnimationFrame(MorphTick);
    }

    private void MorphTick(TimeSpan now)
    {
        var g = _mGen;
        if (!Adv(now, ref _mTick, ref _mT, 0.65))
        { if (_mGen == g) RequestAnimationFrame(MorphTick); return; }

        var raw = _mT > 1 ? 1 : _mT;

        if (raw >= 1)
        {
            BallBorder.Width = _tW; BallBorder.Height = _tH;
            BallBorder.CornerRadius = new CornerRadius(_tCR);
            if (BallBorder.Clip is RectangleGeometry clip)
            { clip.Rect = new Rect(0, 0, _tW, _tH); clip.RadiusX = _tCR; clip.RadiusY = _tCR; }
            if (!_dragging)
            {
                var ww = DipToPx(_tW + _ballPad * 2);
                var wh = DipToPx(_tH + _ballPad * 2);
                Position = new PixelPoint(_tcx - ww / 2, _tcy - wh / 2);
            }
            if (_mGen == g) EndMorph();
            return;
        }

        double w, h, cr;

        if (_morphFromPill)
        {
            var wt = Clamp01(SpringNorm(raw, PillWZeta, PillWOmega, PillWEndVal));
            var ht = Clamp01(SpringNorm(raw, PillHZeta, PillHOmega, PillHEndVal));
            w = L(_fW, _tW, wt);
            h = L(_fH, _tH, ht);

            var posRaw = Clamp01(raw * 1.15);
            var posT = Clamp01(SpringNorm(posRaw, PosZeta, PosOmega, PosEndVal));
            var curCx = (int)L(_scx, _tcx, posT);
            var curCy = (int)L(_scy, _tcy, posT);

            var minDim = Math.Min(w, h);
            var circularCR = minDim / 2.0;
            var linearCR = L(_fCR, _tCR, wt);
            var blend = Clamp01((minDim - 20) / 60.0);
            cr = circularCR + (linearCR - circularCR) * SmoothStep(blend);

            // Apply position
            if (!_dragging)
            {
                var ww = DipToPx(w + _ballPad * 2);
                var wh = DipToPx(h + _ballPad * 2);
                Position = new PixelPoint(curCx - ww / 2, curCy - wh / 2);
            }
        }
        else
        {
            var bt = Clamp01(SpringNorm(raw, BorderZeta, BorderOmega, BorderEndVal));
            w = L(_fW, _tW, bt); h = L(_fH, _tH, bt);

            var posT = EaseOutCubic(Clamp01(raw * 1.2));
            var curCx = (int)L(_scx, _tcx, posT);
            var curCy = (int)L(_scy, _tcy, posT);

            var crT = bt;
            var minDim = Math.Min(w, h);
            var circularCR = minDim / 2.0;
            var linearCR = L(_fCR, _tCR, crT);
            var blend = Clamp01((minDim / _ballSize - 1.0) / 3.0);
            cr = circularCR + (linearCR - circularCR) * SmoothStep(blend);

            if (!_dragging)
            {
                var ww = DipToPx(w + _ballPad * 2);
                var wh = DipToPx(h + _ballPad * 2);
                Position = new PixelPoint(curCx - ww / 2, curCy - wh / 2);
            }
        }

        var sizeChanged = Math.Abs(w - _lastW) > 0.5 || Math.Abs(h - _lastH) > 0.5;
        var crChanged = Math.Abs(cr - _lastCR) > 0.3;

        if (sizeChanged)
        {
            BallBorder.Width = w; BallBorder.Height = h;
            _lastW = w; _lastH = h;
            if (BallBorder.Clip is RectangleGeometry clip) clip.Rect = new Rect(0, 0, w, h);
        }
        if (crChanged)
        {
            BallBorder.CornerRadius = new CornerRadius(cr);
            if (BallBorder.Clip is RectangleGeometry clip) { clip.RadiusX = cr; clip.RadiusY = cr; }
            _lastCR = cr;
        }

        var it = Clamp01(SpringNorm(raw, IconZeta, IconOmega, IconEndVal));
        SetMorphIcon(L(_iFx, _iTx, it), L(_iFy, _iTy, it), L(_iFs, _iTs, it));

        // Content fade: Apple-style delayed ease-out
        // Expand: content appears after shape is ~40% done
        // Collapse: content disappears in first 30%
        if (_contentFadeIn)
        {
            var contentT = raw < 0.45 ? 0 : EaseOutCubic((raw - 0.45) / 0.55);
            PanelContent.Opacity = contentT;
            if (_showSettings)
            {
                DeviceNameDisplay.Opacity = 0;
                SettingsPanel.Opacity = contentT;
            }
            else if (_showApproval)
            {
                DeviceNameDisplay.Opacity = 0;
                ApprovalPanel.Opacity = contentT;
            }
            else if (_showTransfer)
            {
                DeviceNameDisplay.Opacity = 0;
                TransferPanel.Opacity = contentT;
            }
            else if (_showDashboard)
            {
                DeviceNameDisplay.Opacity = 0;
                DashboardPanel.Opacity = contentT;
            }
            else
            {
                DeviceNameDisplay.Opacity = contentT;
            }
        }
        else
        {
            var contentT = raw < 0.25 ? 1 - EaseOutCubic(raw / 0.25) : 0;
            PanelContent.Opacity = contentT;
            DeviceNameDisplay.Opacity = contentT;
        }

        if (_mGen == g) RequestAnimationFrame(MorphTick);
    }

    private void EndMorph()
    {
        _morphInProgress = false;
        ToastContainer.IsVisible = true;
        if (!_mDir)
        {
            _expanded = false;
            PanelContent.IsVisible = false; PanelContent.Opacity = 0;
            DeviceNameDisplay.Opacity = 1;
            ApprovalPanel.IsVisible = false; ApprovalPanel.Opacity = 0;
            TransferPanel.IsVisible = false; TransferPanel.Opacity = 0;
            SettingsPanel.IsVisible = false; SettingsPanel.Opacity = 0;
            DashboardPanel.IsVisible = false; DashboardPanel.Opacity = 0;
            SettingsBtn.IsVisible = false;
            _showSettings = false;
            _showDashboard = false;
            SetMorphIcon(IcoStartTx, IcoStartTy, IcoStartSc);
            _lastItx = double.NaN;

            if (_expandFromSnap != SnapDir.None)
            {
                // Collapsed back to pill — position already correct from morph
                _snapDir = _expandFromSnap;
                _isPill = true;
            }
            else
            {
                ClampToScreen();
                AutoSnapIfOffScreen();
            }
            _expandFromSnap = SnapDir.None;
        }
        else
        {
            _expanded = true;
            PanelContent.Opacity = 1;
            if (_showSettings)
            {
                DeviceNameDisplay.Opacity = 0;
                SettingsPanel.Opacity = 1;
                SettingsBtn.IsVisible = false;
            }
            else if (_showApproval)
            {
                ApprovalPanel.Opacity = 1;
                DeviceNameDisplay.Opacity = 0;
            }
            else if (_showTransfer) { TransferPanel.Opacity = 1; DeviceNameDisplay.Opacity = 0; }
            else if (_showDashboard) { DashboardPanel.Opacity = 1; DeviceNameDisplay.Opacity = 0; SettingsBtn.IsVisible = true; }
            else { DeviceNameDisplay.Opacity = 1; SettingsBtn.IsVisible = true; }
        }
    }

    #endregion

    #region MorphIcon

    private void SetMorphIcon(double tx, double ty, double scale)
    {
        if (Math.Abs(tx - _lastItx) < 0.1 && Math.Abs(ty - _lastIty) < 0.1 && Math.Abs(scale - _lastIts) < 0.005) return;
        _lastItx = tx; _lastIty = ty; _lastIts = scale;
        if (_mTrans != null) { _mTrans.X = tx; _mTrans.Y = ty; }
        if (_mScale != null) { _mScale.ScaleX = scale; _mScale.ScaleY = scale; }
    }

    #endregion

    #region Easing

    private static double ComputeSpringEnd(double zeta, double omega)
    {
        if (zeta >= 1) return 1 - (1 + omega) * Math.Exp(-omega);
        var omegaD = omega * Math.Sqrt(1 - zeta * zeta);
        return 1 - Math.Exp(-zeta * omega) * (Math.Cos(omegaD) + (zeta * omega / omegaD) * Math.Sin(omegaD));
    }

    private static double SpringNorm(double t, double zeta, double omega, double endVal)
    {
        if (t <= 0) return 0; if (t >= 1) return 1;
        if (Math.Abs(endVal) < 0.001) return t;
        double rawVal;
        if (zeta >= 1) rawVal = 1 - (1 + omega * t) * Math.Exp(-omega * t);
        else
        {
            var omegaD = omega * Math.Sqrt(1 - zeta * zeta);
            rawVal = 1 - Math.Exp(-zeta * omega * t) * (Math.Cos(omegaD * t) + (zeta * omega / omegaD) * Math.Sin(omegaD * t));
        }
        return rawVal / endVal;
    }

    // Quintic smoothstep: C2 continuous, no curvature discontinuity
    private static double SmoothStep(double t) => t * t * t * (t * (6 * t - 15) + 10);

    // Apple-style ease-out cubic: fast start, smooth deceleration
    private static double EaseOutCubic(double t) => 1 - (1 - t) * (1 - t) * (1 - t);

    // Apple deceleration curve: cubic-bezier(0.0, 0.0, 0.2, 1.0) approximation
    // Snappy start, long smooth settle — used in iOS/macOS modal transitions
    private static double AppleDecelerate(double t) => 1 - Math.Pow(1 - t, 4);

    private static double L(double a, double b, double t) => a + (b - a) * t;
    private static double Clamp01(double v) => v < 0 ? 0 : v > 1 ? 1 : v;
    private int DipToPx(double dip) => (int)Math.Round(dip * _dpi);

    #endregion

    #region Helpers

    private static bool Adv(TimeSpan now, ref long prev, ref double progress, double duration)
    {
        if (prev == 0) { prev = now.Ticks; return false; }
        var dt = (now.Ticks - prev) / (double)TimeSpan.TicksPerSecond;
        prev = now.Ticks; progress += dt / duration; return true;
    }

    #endregion

    private void OnThemeToggleSwitch(object? sender, RoutedEventArgs e)
    {
        var app = Application.Current;
        if (app == null) return;

        var suki = SukiUI.SukiTheme.GetInstance(app);
        var wantDark = SettingsThemeToggle.IsChecked == true;
        var isDark = suki.ActiveBaseTheme == Avalonia.Styling.ThemeVariant.Dark;
        if (wantDark != isDark)
            suki.ChangeBaseTheme(wantDark ? Avalonia.Styling.ThemeVariant.Dark : Avalonia.Styling.ThemeVariant.Light);
        // ApplyThemeColors is called automatically via OnBaseThemeChanged callback
    }

    private void ApplyThemeColors(bool isDark)
    {
        var r = Application.Current?.Resources;
        if (r == null) return;

        SetBrush(r, "BondPrimary",   isDark ? "#FFF5F2EB" : "#FF141413");
        SetBrush(r, "BondSecondary", isDark ? "#FFC8C2B8" : "#FF6F6A63");
        SetBrush(r, "BondTertiary",  isDark ? "#FF9E978D" : "#FF9A948B");
        SetBrush(r, "BondDisabled",  isDark ? "#FF6F6A63" : "#FFB0AEA5");
        SetBrush(r, "BondBg",        isDark ? "#FF1A1917" : "#FFFAF9F5");
        SetBrush(r, "BondSurface",   isDark ? "#FF242320" : "#FFF3EFE8");
        SetBrush(r, "BondBorder",    isDark ? "#FF44403A" : "#FFE8E6DC");
        SetBrush(r, "BondStroke",    isDark ? "#FF9E978D" : "#FF9A948B");
        SetBrush(r, "BondSuccess",   isDark ? "#FF6F8A69" : "#FF5D7358");
        SetBrush(r, "BondError",     isDark ? "#FFC1635A" : "#FFA34A42");
    }

    private static void SetBrush(IResourceDictionary r, string key, string color)
    {
        r[key] = new SolidColorBrush(Color.Parse(color));
    }

    private static SolidColorBrush TryGetBrush(string key)
    {
        if (Application.Current?.TryFindResource(key, out var v) == true && v is SolidColorBrush b)
            return b;
        return new SolidColorBrush(Colors.Gray);
    }

    private void OnExitClick(object? s, RoutedEventArgs e)
    {
        SavePos();
        _discovery?.Dispose();
        _transfer?.Dispose();
        if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime lt) lt.Shutdown();
    }

    public void ToggleVisibility()
    {
        if (IsVisible) Hide();
        else { Show(); Activate(); }
    }
}
