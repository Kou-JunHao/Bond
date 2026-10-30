using System;
using System.Threading;
using System.Threading.Tasks;
using Bond.Models;

namespace Bond.Services.Updates;

/// <summary>
/// task-141：<c>--auto-update</c> / <c>BOND_AUTO_UPDATE=1</c> 的自动更新入口（默认不启用）。
/// <para>
/// 只在进程被显式带上参数或环境变量时执行一次：查更新 → 有新版本则下载并校验 SHA-256 →
/// 校验通过后启动安装程序并退出。不写自启项、不常驻轮询；与界面「立即检查」共用
/// <see cref="IUpdateService"/>，因此校验逻辑只有一份。
/// </para>
/// </summary>
public static class AutoUpdateRunner
{
    public const string Flag = "--auto-update";
    public const string EnvironmentVariable = "BOND_AUTO_UPDATE";

    public static bool IsRequested(string[]? args)
    {
        if (args is { Length: > 0 })
        {
            foreach (var arg in args)
            {
                if (string.Equals(arg, Flag, StringComparison.OrdinalIgnoreCase)) return true;
            }
        }

        var env = Environment.GetEnvironmentVariable(EnvironmentVariable);
        return string.Equals(env, "1", StringComparison.OrdinalIgnoreCase)
            || string.Equals(env, "true", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// 执行自动更新。退出码：0 = 无需更新或已启动安装程序；1 = 检查失败；2 = 下载或校验失败。
    /// </summary>
    public static async Task<int> RunAsync(
        IUpdateService update, IUpdatePolicy policy, CancellationToken ct = default)
    {
        var check = await update.CheckAsync(policy.Channel, ct).ConfigureAwait(false);
        if (check.State == UpdateState.UpToDate || check.State == UpdateState.NoPackage)
        {
            return 0;
        }

        if (check.State != UpdateState.Available) return 1;

        var download = await update.DownloadAsync(null, ct).ConfigureAwait(false);
        if (!download.Success || !download.Verified) return 2;

        var install = update.Install(silent: true);
        return install.Started ? 0 : 2;
    }
}
