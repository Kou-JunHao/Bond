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
using Avalonia.Input;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Styling;
using Avalonia.Threading;
using BondClient.Services;
using BondClient.ViewModels;
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
    private const double IconSizeDip = 28.0;
    private const double IconTargetSizeDip = 18.0;

    // Apple CASpringAnimation: near-critical damping, moderate speed
    // ζ=0.92 → very slight overshoot (~1%), clean settle
    // ω=4.5  → deliberate speed, spring's zero-velocity start gives natural "weighted" feel
    private const double BorderZeta = 0.92, BorderOmega = 4.5;
    private const double IconZeta = 0.85, IconOmega = 5.5;

    private static readonly double BorderEndVal = ComputeSpringEnd(BorderZeta, BorderOmega);
    private static readonly double IconEndVal = ComputeSpringEnd(IconZeta, IconOmega);

    private double _dpi = 1.0;
    private double _ballSize, _ballPad, _panelW, _panelH;
    private int _ballWinW, _ballWinH;
    private double _edgeSnap, _dragThresh;

    private double _icoEndTx, _icoEndTy, _icoEndSc;
    private const double IcoStartTx = 0.0, IcoStartTy = 0.0, IcoStartSc = 1.0;

    private bool _ptrDown, _dragging;
    private PixelPoint _ptrStart, _winStart;
    private bool _snapL, _snapR, _hoverSnap;
    private bool _expanded;
    private bool _morphInProgress;

    // Morph
    private uint _mGen;
    private bool _mDir;
    private double _mT;
    private long _mTick;
    private double _fW, _fH, _fCR, _tW, _tH, _tCR;
    private int _cx, _cy;
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

    // Cache
    private ScaleTransform? _mScale;
    private TranslateTransform? _mTrans;
    private double _lastW = double.NaN, _lastH, _lastCR;
    private double _lastItx = double.NaN, _lastIty, _lastIts;

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
                ? new SolidColorBrush(Color.Parse("#FF81C784"))
                : new SolidColorBrush(Color.Parse("#FFCCCCCC"));
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

        // Initialize theme state
        var suki = SukiUI.SukiTheme.GetInstance(Application.Current!);
        _isDark = suki.ActiveBaseTheme == Avalonia.Styling.ThemeVariant.Dark;
        if (_isDark) ApplyThemeColors(true);

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
        var names = string.Join(", ", paths.Select(Path.GetFileName));
        StatusText.Text = $"已选择: {names}";
        EmptyHint.IsVisible = false;

        if (!_expanded)
        {
            DoExpand(withTransfer: true);
        }
        else if (!TransferPanel.IsVisible)
        {
            FileNamesText.Text = names;
            _vm?.RefreshDevices();
            if (!_morphInProgress)
            {
                DoMorphToTransfer();
            }
        }
        else if (_showTransferProgress)
        {
            FileNamesText.Text = names;
            _vm!.RefreshDevices();
            StartTransferProgressCrossfade(false);
        }
        else
        {
            FileNamesText.Text = names;
            _vm.RefreshDevices();
        }
    }

    #endregion

    #region Toast

    private enum ToastType { Success, Error, Info }

    private void ShowToast(string message, ToastType type = ToastType.Info)
    {
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
        if (!_expanded) DoExpand(showApproval: true);
        else ShowApprovalPanel();
    }

    private void ShowApprovalPanel()
    {
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
        BallBorder.Background = new SolidColorBrush(Color.Parse("#FDF5E6"));
    }

    private void OnRainbowTick(object? sender, EventArgs e)
    {
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
            Background = new SolidColorBrush(Color.Parse("#FFE0E0E0")),
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
                Foreground = new SolidColorBrush(Color.Parse("#FF333333"))
            });
            panel.Children.Add(new TextBlock
            {
                Text = subnet.Description,
                FontSize = 10,
                Foreground = new SolidColorBrush(Color.Parse("#FF999999"))
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

    private void DoSettingsExpand()
    {
        _hGen++; Sc = 1;
        var tw = BallBorder.Width + _ballPad * 2;
        var th = BallBorder.Height + _ballPad * 2;
        _cx = Position.X + DipToPx(tw) / 2;
        _cy = Position.Y + DipToPx(th) / 2;
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
        _hGen++; Sc = 1;
        var tw = BallBorder.Width + _ballPad * 2;
        var th = BallBorder.Height + _ballPad * 2;
        _cx = Position.X + DipToPx(tw) / 2;
        _cy = Position.Y + DipToPx(th) / 2;
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
                    if (x <= wa.X + DipToPx(_edgeSnap)) _snapL = true;
                    else if (x + _ballWinW >= wa.X + wa.Width - DipToPx(_edgeSnap)) _snapR = true;
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
        { _dragging = true; _snapL = false; _snapR = false; }
        if (_dragging) Position = new PixelPoint(_winStart.X + dx, _winStart.Y + dy);
    }

    private void OnPointerReleased(object? s, PointerReleasedEventArgs e)
    {
        if (!_ptrDown) return;
        _ptrDown = false;
        if (_dragging)
        {
            _dragging = false; Snap(); SavePos();
            if (_morphInProgress)
            {
                var tw = BallBorder.Width + _ballPad * 2;
                var th = BallBorder.Height + _ballPad * 2;
                _cx = Position.X + DipToPx(tw) / 2;
                _cy = Position.Y + DipToPx(th) / 2;
            }
        }
        else Click();
        e.Pointer.Capture(null);
    }

    #endregion

    #region Edge snap

    private void Snap()
    {
        var scr = Screens.ScreenFromPoint(Position);
        if (scr?.WorkingArea is not { } wa) return;
        var esPx = DipToPx(_edgeSnap);
        if (Position.X <= wa.X + esPx)
        { Position = new PixelPoint(wa.X - _ballWinW + DipToPx(16), Position.Y); _snapL = true; }
        else if (Position.X + _ballWinW >= wa.X + wa.Width - esPx)
        { Position = new PixelPoint(wa.X + wa.Width - DipToPx(16), Position.Y); _snapR = true; }
        SavePos();
    }

    private void OnPointerEntered(object? s, PointerEventArgs e)
    {
        if (!_expanded && !_morphInProgress && (_snapL || _snapR))
        { _hoverSnap = true; Slide(true); }
        if (!_expanded && !_morphInProgress) HoverIn();
    }

    private void OnPointerExited(object? s, PointerEventArgs e)
    {
        if (_hoverSnap) { _hoverSnap = false; Slide(false); }
        if (!_expanded && !_morphInProgress) HoverOut();
    }

    private void Slide(bool o)
    {
        var scr = Screens.ScreenFromPoint(Position);
        if (scr?.WorkingArea is not { } wa) return;
        if (o && _snapL) Position = new PixelPoint(wa.X, Position.Y);
        else if (o && _snapR) Position = new PixelPoint(wa.X + wa.Width - _ballWinW, Position.Y);
        else if (!o && _snapL) Position = new PixelPoint(wa.X - _ballWinW + DipToPx(16), Position.Y);
        else if (!o && _snapR) Position = new PixelPoint(wa.X + wa.Width - DipToPx(16), Position.Y);
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

    #endregion

    #region Morph

    private void Click()
    {
        if (_morphInProgress) { ReverseMorph(); return; }
        if (_expanded)
        {
            if (_showSettings) DoSettingsCollapse();
            else DoCollapse();
        }
        else DoExpand();
    }

    private void DoExpand(bool withTransfer = false, bool showApproval = false)
    {
        _hGen++; Sc = 1; _snapL = false; _snapR = false;
        var tw = (int)(_ballSize + _ballPad * 2);
        _cx = Position.X + DipToPx(tw) / 2;
        _cy = Position.Y + DipToPx(tw) / 2;
        _fW = BallBorder.Width;
        _fH = BallBorder.Height;
        _fCR = BallBorder.CornerRadius.TopLeft; _tCR = PanelCR;
        _iFx = IcoStartTx; _iFy = IcoStartTy; _iFs = IcoStartSc;
        _iTx = _icoEndTx; _iTy = _icoEndTy; _iTs = _icoEndSc;

        if (withTransfer)
        {
            _tW = TransferWidthDip; _tH = TransferHeightDip;
        }
        else
        {
            _tW = _panelW; _tH = _panelH;
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
            FileNamesText.Text = string.Join(", ", _vm!.QueuedFiles!.Select(Path.GetFileName));
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
        _hGen++; Sc = 1;
        var tw = BallBorder.Width + _ballPad * 2;
        var th = BallBorder.Height + _ballPad * 2;
        _cx = Position.X + DipToPx(tw) / 2;
        _cy = Position.Y + DipToPx(th) / 2;
        _fW = BallBorder.Width; _tW = _ballSize;
        _fH = BallBorder.Height; _tH = _ballSize;
        _fCR = BallBorder.CornerRadius.TopLeft; _tCR = BallCR;
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
            PanelContent.IsVisible = true;
        }
        else { _tW = _ballSize; _tH = _ballSize; _tCR = BallCR; }
        var tw = BallBorder.Width + _ballPad * 2;
        var th = BallBorder.Height + _ballPad * 2;
        _cx = Position.X + DipToPx(tw) / 2;
        _cy = Position.Y + DipToPx(th) / 2;
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
        var bt = Clamp01(SpringNorm(raw, BorderZeta, BorderOmega, BorderEndVal));

        var w = L(_fW, _tW, bt); var h = L(_fH, _tH, bt);

        // Corner radius: follows spring response directly for consistent motion
        var crT = bt;
        var minDim = Math.Min(w, h);
        var circularCR = minDim / 2.0;
        var linearCR = L(_fCR, _tCR, crT);
        var blend = Clamp01((minDim / _ballSize - 1.0) / 3.0);
        var cr = circularCR + (linearCR - circularCR) * SmoothStep(blend);

        var sizeChanged = Math.Abs(w - _lastW) > 0.5 || Math.Abs(h - _lastH) > 0.5;
        var crChanged = Math.Abs(cr - _lastCR) > 0.3;

        if (sizeChanged)
        {
            if (!_dragging)
            {
                var ww = DipToPx(w + _ballPad * 2);
                var wh = DipToPx(h + _ballPad * 2);
                Position = new PixelPoint(_cx - ww / 2, _cy - wh / 2);
            }
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

        if (raw >= 1) { if (_mGen == g) EndMorph(); return; }
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
        ApplyThemeColors(_isDark);
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
        {
            suki.ChangeBaseTheme(wantDark ? Avalonia.Styling.ThemeVariant.Dark : Avalonia.Styling.ThemeVariant.Light);
            _isDark = wantDark;
            ApplyThemeColors(wantDark);
        }
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
