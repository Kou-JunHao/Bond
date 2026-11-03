using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using Bond.Models;

namespace Bond.Services.Updates;

/// <summary>
/// task-141：更新流程的稳定内核。界面、「立即检查」以及 <c>--auto-update</c> 共用同一条路径，
/// 保证 SHA-256 校验与安装包处理只有一份实现。
/// </summary>
public interface IUpdateService
{
    /// <summary>当前运行版本（来自程序集，绝不硬编码）。</summary>
    string CurrentVersion { get; }

    /// <summary>最近一次检查结果；下载 / 安装都基于它。</summary>
    SoftwareCheckResult? LastCheck { get; }

    /// <summary>待下载 / 待安装的发行物。</summary>
    SoftwareReleaseInfo? PendingRelease { get; }

    /// <summary>最近一次成功下载的安装包路径。</summary>
    string? DownloadedPath { get; }

    /// <summary>查询更新。channel 目前只实现 stable，beta 为预留参数。</summary>
    Task<SoftwareCheckResult> CheckAsync(UpdateChannel channel, CancellationToken ct = default);

    /// <summary>下载「最近一次检查结果」里的安装包，内部做 SHA-256 校验。</summary>
    Task<SoftwareDownloadResult> DownloadAsync(IProgress<double>? progress, CancellationToken ct = default);

    /// <summary>启动已下载的安装包；silent=true 时使用 Inno Setup 静默参数（自动更新入口）。</summary>
    UpdateInstallResult Install(bool silent = false);
}

/// <summary>启动安装程序的结果。</summary>
public sealed class UpdateInstallResult
{
    public bool Started { get; init; }
    public string? Path { get; init; }
    public string Message { get; init; } = "";

    public static UpdateInstallResult Ok(string path) => new()
    {
        Started = true,
        Path = path,
        Message = "安装程序已启动",
    };

    public static UpdateInstallResult Failed(string message) => new()
    {
        Started = false,
        Message = message,
    };
}

/// <summary>基于 <see cref="ISoftwareService"/> 的默认实现。</summary>
public sealed class UpdateService(ISoftwareService software) : IUpdateService
{
    public string CurrentVersion => software.CurrentVersion;

    public SoftwareCheckResult? LastCheck { get; private set; }

    public SoftwareReleaseInfo? PendingRelease => LastCheck?.Release;

    public string? DownloadedPath { get; private set; }

    public async Task<SoftwareCheckResult> CheckAsync(UpdateChannel channel, CancellationToken ct = default)
    {
        // task-141：服务端的 releases 端点支持 channel，但包列表与检查入口目前只暴露 stable。
        // beta 通道先原样走 stable 查询，等后端开放测试通道时把 channel 传进 SoftwareService 即可。
        _ = channel;

        var result = await software.CheckForUpdatesAsync(ct).ConfigureAwait(false);
        ApplyForceUpdate(result);
        LastCheck = result;
        return result;
    }

    public Task<SoftwareDownloadResult> DownloadAsync(IProgress<double>? progress, CancellationToken ct = default)
    {
        var release = PendingRelease;
        if (release is null)
        {
            return Task.FromResult(SoftwareDownloadResult.Failed("还没有可下载的更新，请先检查更新。")); 
        }

        return DownloadCoreAsync(release, progress, ct);
    }

    private async Task<SoftwareDownloadResult> DownloadCoreAsync(
        SoftwareReleaseInfo release, IProgress<double>? progress, CancellationToken ct)
    {
        var result = await software.DownloadAsync(release, progress, ct).ConfigureAwait(false);
        DownloadedPath = result.Success ? result.Path : null;
        return result;
    }

    public UpdateInstallResult Install(bool silent = false)
    {
        var path = DownloadedPath;
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path))
        {
            return UpdateInstallResult.Failed("安装包不存在，可能已被移动或删除，请重新下载。");
        }

        var file = new FileInfo(path);
        if (file.Length <= 0)
        {
            return UpdateInstallResult.Failed("安装包为空，请重新下载。");
        }

        if (!string.Equals(file.Extension, ".exe", StringComparison.OrdinalIgnoreCase))
        {
            return UpdateInstallResult.Failed("安装包格式不受支持，请重新下载。");
        }

        // 启动前再校验一次：防止下载完成后文件被替换/损坏。
        var expected = (PendingRelease?.Sha256 ?? "").Trim().ToLowerInvariant();
        if (expected.Length != 64)
        {
            return UpdateInstallResult.Failed("安装包缺少 SHA-256 校验值，无法确认完整性，请重新检查更新。");
        }

        try
        {
            using var stream = File.OpenRead(path);
            var actual = Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
            if (!string.Equals(actual, expected, StringComparison.Ordinal))
            {
                TryDelete(path);
                DownloadedPath = null;
                return UpdateInstallResult.Failed("安装包校验失败（SHA-256 不匹配），已删除文件，请重新下载。");
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or CryptographicException)
        {
            return UpdateInstallResult.Failed($"无法校验安装包：{ex.Message}");
        }

        try
        {
            var startInfo = new ProcessStartInfo(path) { UseShellExecute = true };
            if (silent)
            {
                // Inno Setup 的静默参数：显示进度但不弹向导，不自动重启。
                startInfo.Arguments = "/SILENT /SUPPRESSMSGBOXES /NORESTART";
            }

            Process.Start(startInfo);
            return UpdateInstallResult.Ok(path);
        }
        catch (Win32Exception ex) when (ex.NativeErrorCode == 1223)
        {
            return UpdateInstallResult.Failed("已取消安装程序启动（UAC 提示被取消）。");
        }
        catch (Exception ex)
        {
            return UpdateInstallResult.Failed($"无法启动安装程序：{ex.Message}");
        }
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
        }
        catch
        {
            // 删除失败不影响错误提示。
        }
    }

    /// <summary>
    /// task-141：强制更新判定。服务端契约里暂时没有 forceUpdate / minSupportedVersion，
    /// 客户端保留判定：任一字段出现且成立即视为强制更新（横幅不可关闭）。
    /// </summary>
    private void ApplyForceUpdate(SoftwareCheckResult result)
    {
        if (result.State != UpdateState.Available) return;

        if (result.ForceUpdate) return;

        var current = SoftwareVersion.ParseOrNull(CurrentVersion);
        var minimum = SoftwareVersion.ParseOrNull(result.MinSupportedVersion);
        if (current is null || minimum is null) return;

        if (minimum.IsNewerThan(current))
        {
            result.ForceUpdate = true;
        }
    }
}
