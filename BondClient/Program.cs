using System;
using System.Runtime.InteropServices;
using Avalonia;

namespace Bond;

internal static class Program
{
    // Windows 的“不受信任的重定向”缓解会拦截经 PATH 中符号链接加载的 DLL。
    // 把默认 DLL 搜索范围限制为应用目录 + System32，避免去 PATH（例如 nvm 的
    // C:\nvm4w\nodejs 符号链接）里找 DWriteCore.dll 之类的系统组件。
    private const uint LOAD_LIBRARY_SEARCH_APPLICATION_DIR = 0x00000200;
    private const uint LOAD_LIBRARY_SEARCH_SYSTEM32 = 0x00000800;

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetDefaultDllDirectories(uint directoryFlags);

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
            .WithInterFont()
            .LogToTrace();
}
