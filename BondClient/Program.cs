using System;
using System.Reflection;
using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Rendering;

namespace Bond;

internal static class Program
{
    // Windows 的“不受信任的重定向”缓解会拦截经 PATH 中符号链接加载的 DLL。
    // 把默认 DLL 搜索范围限制为应用目录 + System32，避免去 PATH（例如 nvm 的
    // C:\nvm4w\nodejs 符号链接）里找 DWriteCore.dll 之类的系统组件。
    private const uint LOAD_LIBRARY_SEARCH_APPLICATION_DIR = 0x00000200;
    private const uint LOAD_LIBRARY_SEARCH_SYSTEM32 = 0x00000800;

    private const int VREFRESH = 116;

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetDefaultDllDirectories(uint directoryFlags);

    [DllImport("user32.dll")]
    private static extern IntPtr GetDC(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);

    [DllImport("gdi32.dll")]
    private static extern int GetDeviceCaps(IntPtr hdc, int nIndex);

    [DllImport("winmm.dll", EntryPoint = "timeBeginPeriod")]
    private static extern uint TimeBeginPeriod(uint uPeriod);

    /// <summary>
    /// task-141：启动参数（目前只用于识别 <c>--auto-update</c>）。
    /// Avalonia 的 <c>StartWithClassicDesktopLifetime</c> 会过滤掉部分参数，
    /// 因此在这里先留存一份原始参数快照，供自动更新入口判断。
    /// </summary>
    public static string[] StartupArgs { get; private set; } = [];

    [STAThread]
    public static void Main(string[] args)
    {
        StartupArgs = args ?? [];
        TryHardenDllSearchPath();
        BuildAvaloniaApp().StartWithClassicDesktopLifetime(StartupArgs);
    }

    private static void TryHardenDllSearchPath()
    {
        try
        {
            SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_APPLICATION_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
        }
        catch
        {
            // 旧系统或受限环境不支持时保持默认行为，不影响启动。
        }
    }

    public static AppBuilder BuildAvaloniaApp()
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .With(new Win32PlatformOptions
            {
                // WinUI Composition 会用自己的 WinUiCompositorConnection 覆盖 IRenderLoop，
                // 且替换其 timer 会导致黑屏；DirectComposition 可配合窗口创建后的 timer 替换。
                CompositionMode = new[]
                {
                    Win32CompositionMode.DirectComposition,
                    Win32CompositionMode.RedirectionSurface,
                },
            })
            .WithInterFont()
            .LogToTrace();

    /// <summary>
    /// Avalonia 的 Win32 平台默认把渲染循环固定为 60fps（DefaultRenderTimer(60)），
    /// 在高刷新率屏幕上会导致整个应用被限制在 60fps。平台服务初始化完成后，
    /// 用当前显示器刷新率重新绑定 IRenderLoop，让渲染节奏跟随屏幕。
    /// </summary>
    internal static void TryUseDisplayRefreshRate()
    {
        try
        {
            var refreshRate = GetDisplayRefreshRate();
            // System.Threading.Timer 默认受 Windows 定时器精度限制（约 15.6ms），
            // 高刷新率下会退化成 ~60-125fps；把定时器精度提到 1ms 再创建渲染定时器。
            TimeBeginPeriod(1);
            var baseAssembly = typeof(AvaloniaLocator).Assembly;
            var timerType = baseAssembly.GetType("Avalonia.Rendering.DefaultRenderTimer");
            var renderLoopType = baseAssembly.GetType("Avalonia.Rendering.RenderLoop");
            if (timerType is null || renderLoopType is null)
            {
                return;
            }

            var timer = Activator.CreateInstance(timerType, refreshRate);
            var fromTimer = renderLoopType.GetMethod("FromTimer", BindingFlags.Public | BindingFlags.Static);
            var renderLoop = fromTimer?.Invoke(null, new[] { timer });
            if (renderLoop is null)
            {
                return;
            }



            // Avalonia 的参考程序集把 AvaloniaLocator.CurrentMutable / Bind 标成 internal，
            // 但运行时实现是 public；这里用反射完成重新绑定，避免依赖参考程序集的可见性。
            var locatorType = typeof(AvaloniaLocator);
            var currentMutable = locatorType
                .GetProperty("CurrentMutable", BindingFlags.Public | BindingFlags.Static)
                ?.GetValue(null);
            var bind = locatorType
                .GetMethod("Bind", BindingFlags.Public | BindingFlags.Instance)
                ?.MakeGenericMethod(typeof(IRenderLoop));
            var registration = bind?.Invoke(currentMutable, null);
            var toConstant = registration
                ?.GetType()
                .GetMethod("ToConstant", BindingFlags.Public | BindingFlags.Instance)
                ?.MakeGenericMethod(typeof(IRenderLoop));
            toConstant?.Invoke(registration, new[] { renderLoop });
        }
        catch
        {
            // 查询失败或 API 变化时保持 Avalonia 默认 60fps，不影响启动。
        }
    }

    internal static int GetDisplayRefreshRate()
    {
        var hdc = GetDC(IntPtr.Zero);
        if (hdc == IntPtr.Zero)
        {
            return 60;
        }

        try
        {
            var hz = GetDeviceCaps(hdc, VREFRESH);
            return hz is >= 60 and <= 360 ? hz : 60;
        }
        finally
        {
            ReleaseDC(IntPtr.Zero, hdc);
        }
    }
}
