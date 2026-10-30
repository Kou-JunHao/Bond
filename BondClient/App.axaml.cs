using Avalonia.Styling;
using System;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Avalonia.Platform;
using Avalonia.Threading;
using Bond.Services;
using Bond.ViewModels;
using Bond.Views;
using Microsoft.Extensions.DependencyInjection;

namespace Bond;

public partial class App : Application
{
    /// <summary>组合根产出的容器（唯一全局入口，且不可变）。</summary>
    public static IServiceProvider Services { get; private set; } = null!;

    /// <summary>剪贴板 / 文件对话框的宿主 TopLevel（主窗口优先）。</summary>
    public static TopLevel? ShellTopLevel { get; set; }

    /// <summary>task-153：主窗口界面缩放（1.0 = 100%）。悬浮球窗口不参与缩放。</summary>
    public static event EventHandler<double>? UiScaleChanged;

    private static double _currentUiScale = 1.0;

    public static double CurrentUiScale => _currentUiScale;

    /// <summary>prefs.json 里存的 "auto|1|1.1|1.25|1.5" → 缩放倍率（auto 视为 1）。</summary>
    public static double ResolveUiScale(string? stored) => stored switch
    {
        "1" => 1.0,
        "1.1" => 1.1,
        "1.25" => 1.25,
        "1.5" => 1.5,
        _ => 1.0,
    };

    /// <summary>prefs.json 里的值 → ComboBox 索引（0=跟随系统）。</summary>
    public static int ParseUiScaleIndex(string? stored) => stored switch
    {
        "1" => 1,
        "1.1" => 2,
        "1.25" => 3,
        "1.5" => 4,
        _ => 0,
    };

    /// <summary>应用缩放：先更新静态值，再广播给主窗口（幂等，可在任意线程调用）。</summary>
    public static void ApplyUiScale(double scale)
    {
        if (double.IsNaN(scale) || scale <= 0) scale = 1.0;
        _currentUiScale = scale;
        UiScaleChanged?.Invoke(null, scale);
    }

    private TrayIcon? _tray;
    private WindowCoordinator? _coordinator;
    private MainWindow? _main;
    private FloatingBallWindow? _ball;
    private LoginWindow? _login;

    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is not IClassicDesktopStyleApplicationLifetime desktop)
        {
            base.OnFrameworkInitializationCompleted();
            return;
        }

        // 两个窗口并存，关闭主窗口不应杀掉进程。
        desktop.ShutdownMode = ShutdownMode.OnExplicitShutdown;

        Services = AppServices.Build();
        _coordinator = (WindowCoordinator)Services.GetRequiredService<IWindowCoordinator>();

        // task-141：--auto-update / BOND_AUTO_UPDATE=1 时无人值守跑一次更新后退出（默认不启用）。
        if (Bond.Services.Updates.AutoUpdateRunner.IsRequested(Bond.Program.StartupArgs))
        {
            _ = Task.Run(async () =>
            {
                var exitCode = 1;
                try
                {
                    var update = Services.GetRequiredService<Bond.Services.Updates.IUpdateService>();
                    var policy = Services.GetRequiredService<Bond.Services.Updates.IUpdatePolicy>();
                    exitCode = await Bond.Services.Updates.AutoUpdateRunner.RunAsync(update, policy).ConfigureAwait(false);
                }
                catch (Exception ex)
                {
                    Console.Error.WriteLine("[auto-update] " + ex.Message);
                }
                finally
                {
                    Avalonia.Threading.Dispatcher.UIThread.Post(() => desktop.Shutdown(exitCode));
                }
            });

            base.OnFrameworkInitializationCompleted();
            return;
        }

        CreateTrayIcon(desktop);

        // 临时自检：BOND_SELFTEST=1 时用 App 自己的 HttpClient 打一次登录，日志落盘
        if (Environment.GetEnvironmentVariable("BOND_SELFTEST") == "1")
        {
            _ = Task.Run(async () =>
            {
                var log = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "bond-api-selftest.log");
                var api = Services.GetRequiredService<Bond.Services.Api.BondApiClient>();
                var lines = new System.Collections.Generic.List<string>
                {
                    "base=" + api.BaseUrl,
                    "proxy=" + (System.Net.Http.HttpClient.DefaultProxy?.GetProxy(new Uri(api.BaseUrl))?.ToString() ?? "none")
                };

                try
                {
                    var r = await api.PostAsync<Bond.Services.Api.UserDto>("/api/auth/login",
                        new { username = "__probe_not_exist__", password = "__wrong__" });
                    lines.Add(r is null
                        ? "RESULT=null  LastError=" + (api.LastError ?? "(空)")
                        : $"RESULT code={r.Code} message={r.Message}");
                }
                catch (Exception ex)
                {
                    lines.Add("THROW " + ex.GetType().Name + ": " + ex.Message);
                }

                try { System.IO.File.WriteAllLines(log, lines); } catch { }
            });
        }

        var api = Services.GetRequiredService<Bond.Services.Api.BondApiClient>();

        // 登录态失效（token 过期 / 刷新失败）→ 收回主界面，回到登录页
        api.AuthExpired += (_, _) => Avalonia.Threading.Dispatcher.UIThread.Post(ReturnToLogin);

        ApplySavedTheme();

        // 已登录（含从旧客户端迁移过来的 token）直接进主界面，否则先走登录 / 注册
        if (api.IsLoggedIn)
        {
            ShowShell(desktop);
        }
        else
        {
            _login = new LoginWindow(api);
            _login.Authenticated += (_, _) => ShowShell(desktop);
            _login.Show();
        }

        // task-141：依照用户选择的检查频率做一次启动检查；默认「从不」时不会发任何请求。
        _ = Services.GetRequiredService<SettingsPageViewModel>().RunScheduledCheckAsync();

        base.OnFrameworkInitializationCompleted();
    }

    // ── 主题 ──────────────────────────────────────────────────
    private static readonly string UiFile = System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Bond", "ui.theme");

    public static void ApplyTheme(bool dark)
    {
        var variant = dark ? ThemeVariant.Dark : ThemeVariant.Light;
        if (Current is { } app) app.RequestedThemeVariant = variant;

        try { SukiUI.SukiTheme.GetInstance().ChangeBaseTheme(variant); } catch { }

        try
        {
            System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(UiFile)!);
            System.IO.File.WriteAllText(UiFile, dark ? "dark" : "light");
        }
        catch { }
    }

    public static bool IsDarkTheme => Current?.RequestedThemeVariant == ThemeVariant.Dark;

    private static void ApplySavedTheme()
    {
        try
        {
            if (System.IO.File.Exists(UiFile) && System.IO.File.ReadAllText(UiFile).Trim() == "dark")
                ApplyTheme(true);
        }
        catch { }
    }

    // ── 登录态失效后的回退 ────────────────────────────────────
    public void ExitApplication()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime d)
        {
            // task-160：退出前停掉局域网发现，避免进程残留 UDP 监听。
            if (Services.GetService(typeof(Bond.Services.Lan.LanDiscoveryService)) is Bond.Services.Lan.LanDiscoveryService lan)
                lan.Stop();

            _main?.Hide();
            _ball?.Hide();
            _login?.Close();
            d.Shutdown();
        }
    }

    /// <summary>
    /// task-160 A：按「设置开关 + 登录态」启停局域网发现（幂等，UI 线程调用）。
    /// 未登录 / 开关关闭时必须停，避免出现「别人看得见我、我看不见别人」的半开状态。
    /// </summary>
    public void SyncLanDiscovery()
    {
        if (Services.GetService(typeof(Bond.Services.Lan.LanDiscoveryService)) is not Bond.Services.Lan.LanDiscoveryService lan)
            return;

        var api = Services.GetRequiredService<Bond.Services.Api.BondApiClient>();
        var shouldRun = api.IsLoggedIn && LocalPrefs.Current.AllowLanDiscovery;

        if (shouldRun && !lan.IsRunning) lan.Start();
        else if (!shouldRun && lan.IsRunning) lan.Stop();
    }

    public void ReturnToLogin()
    {
        // 退出登录 / 登录态失效都会走到这里；连续多次 AuthExpired 时只处理一次，
        // 否则会反复重置 store、反复显示登录窗（并在清 token 后触发二次请求风暴）。
        if (_login is not null && _main is null) return;

        var api = Services.GetRequiredService<Bond.Services.Api.BondApiClient>();

        // task-160：登录态失效后立即停掉局域网发现（同账号才能互相发现，未登录不该继续广播）。
        if (Services.GetService(typeof(Bond.Services.Lan.LanDiscoveryService)) is Bond.Services.Lan.LanDiscoveryService lan)
            lan.Stop();

        _main?.Hide();
        _ball?.Hide();
        _main = null;
        _ball = null;

        // task-140：退出登录必须把 store 里上一个账号的会话缓存清干净，
        // 否则重新登录会复用旧账户名/头像（BootstrapAsync 命中缓存，/api/auth/me 不会重跑）。
        if (Services.GetService(typeof(Bond.Services.HttpBondStore)) is Bond.Services.HttpBondStore store)
        {
            store.ResetSessionState();
        }

        if (_login is null)
        {
            _login = new LoginWindow(api);
            _login.Authenticated += (_, _) =>
            {
                if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
                    ShowShell(desktop);
            };
        }

        _login.Show();
        _login.Activate();
    }

    private void ShowShell(IClassicDesktopStyleApplicationLifetime desktop)
    {
        if (_main is not null)
        {
            // 已存在主窗口（例如刚换账号登录）：直接刷新会话显示
            if (Services.GetService(typeof(MainWindowViewModel)) is MainWindowViewModel existing)
                existing.RefreshSession();
            _main.Show();
            _ball?.Show();
            _login?.Close();
            _login = null;

            // task-160 A：换账号重新登录后按当前账号重启局域网发现。
            SyncLanDiscovery();
            return;
        }

        var mainVm = Services.GetRequiredService<MainWindowViewModel>();
        var ballVm = Services.GetRequiredService<FloatingBallViewModel>();

        // task-35：设备注册 / 心跳都挂在同一个 store 上
        var store = Services.GetRequiredService<HttpBondStore>();

        _main = new MainWindow(mainVm);
        _ball = new FloatingBallWindow(ballVm);

        _coordinator!.Attach((IMainHost)_main);
        _coordinator.Attach((IBallHost)_ball);

        desktop.MainWindow = _main;
        ShellTopLevel = _main;

        // task-97：首屏同步推迟到主窗口真正 Opened 之后。
        // 启动阶段（Show 之后、控件模板与绑定尚未完成）就把后台线程的第一批数据
        // 灌进 Recent/Spaces/Uploads 这类绑定集合，会在 Avalonia 弱事件派发途中改写集合而崩溃。
        void OnShellOpened(object? sender, EventArgs e)
        {
            _main!.Opened -= OnShellOpened;

            _ = Task.Run(async () =>
            {
                try
                {
                    var ok = await store.BootstrapAsync().ConfigureAwait(false);
                    Console.WriteLine(ok
                        ? "[Bond] 已连接后端，数据已加载"
                        : "[Bond] 未登录或后端不可达，界面为空数据");

                    // task-140：重新登录时 store 已是干净状态，/api/auth/me 回来后要把
                    // 右上角账户区（昵称/头像）通知给界面，否则会一直停在首字母占位。
                    if (ok)
                    {
                        Avalonia.Threading.Dispatcher.UIThread.Post(mainVm.RefreshSession);
                    }

                    // 首屏同步失败时给出页面内可见的提示（主窗口 Toast），不要静默空白
                    if (!ok)
                    {
                        var notify = Services.GetRequiredService<INotificationCenter>();
                        Avalonia.Threading.Dispatcher.UIThread.Post(() => notify.Notify(
                            "未能从服务器加载数据：请检查网络或登录状态，稍后可重试",
                            NoticeKind.Warning));
                    }
                }
                catch (Exception ex)
                {
                    Console.WriteLine("[Bond] 启动同步失败: " + ex.Message);
                }
            });
        }

        _main.Opened += OnShellOpened;

        _main.Show();
        _ball.Show();

        // task-160 A：首屏窗口出来后按设置开关启动局域网发现（未登录/开关关闭时保持停止）。
        SyncLanDiscovery();

        // task-35：回到前台时再试一次心跳——心跳 401 静默停过的话靠这个恢复（不弹框）
        // task-160：同时补一次发现状态同步（设置是另一个窗口改的，前台激活是最稳的汇合点）。
        _main.Activated += (_, _) =>
        {
            store.OnAppActivated();
            SyncLanDiscovery();
        };

        _login?.Close();
        _login = null;

        base.OnFrameworkInitializationCompleted();
    }

    private void CreateTrayIcon(IClassicDesktopStyleApplicationLifetime desktop)
    {
        try
        {
            var icon = new WindowIcon(AssetLoader.Open(new Uri("avares://Bond/Assets/logo.png")));

            var openMain = new NativeMenuItem("打开主界面");
            openMain.Click += (_, _) => _coordinator?.ShowMain();

            var toggleBall = new NativeMenuItem("显示/隐藏悬浮球");
            toggleBall.Click += (_, _) =>
            {
                if (_ball is null) return;
                if (_ball.IsVisible) _ball.Hide();
                else _ball.Show();
            };

            var exit = new NativeMenuItem("退出 Bond");
            exit.Click += (_, _) => _coordinator?.Quit();

            _tray = new TrayIcon
            {
                Icon = icon,
                ToolTipText = "Bond",
                Menu = new NativeMenu { Items = { openMain, toggleBall, new NativeMenuItemSeparator(), exit } }
            };
            _tray.Clicked += (_, _) => _coordinator?.ToggleMain();

            TrayIcon.SetIcons(this, new TrayIcons { _tray });
        }
        catch
        {
            // 托盘不可用（无桌面环境/资源缺失）时不应阻断启动，悬浮球仍可用。
        }

        _ = desktop;
    }
}
