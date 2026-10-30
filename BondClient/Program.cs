using System;
using Avalonia;

namespace Bond;

internal static class Program
{
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
        BuildAvaloniaApp().StartWithClassicDesktopLifetime(StartupArgs);
    }

    public static AppBuilder BuildAvaloniaApp()
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .WithInterFont()
            .LogToTrace();
}
