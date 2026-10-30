using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using Bond.Models;
using Bond.Services.Api;

namespace Bond.Services;

/// <summary>
/// 软件分发（自更新）客户端服务：查询生产发布、下载发行物并做 SHA-256 校验。
/// 只查询与下载，不执行自动安装（安装由用户手动完成）。
/// </summary>
public sealed class SoftwareService(BondApiClient api) : ISoftwareService
{
    /// <summary>
    /// task-151：生产包名是 <c>bond-windows</c>（此前写成 <c>bond-app</c>，靠 <c>FirstOrDefault()</c> 兜底才碰巧命中，
    /// 一旦服务端出现第二个包就会取错）。这里改成真实包名；兜底只用于找不到同名包时的旧服务端兼容。
    /// </summary>
    public const string PackageCode = "bond-windows";

    public string CurrentVersion { get; } = ResolveCurrentVersion();

    public async Task<SoftwareCheckResult> CheckForUpdatesAsync(CancellationToken ct = default)
    {
        ApiResult<List<ApiSoftwarePackageDto>>? packages;
        try
        {
            packages = await api.GetAsync<List<ApiSoftwarePackageDto>>("/api/software/packages");
        }
        catch (Exception ex)
        {
            return SoftwareCheckResult.Failed($"检查更新失败：{ex.Message}");
        }

        if (packages is null)
        {
            return SoftwareCheckResult.Failed(DescribeFailure());
        }

        if (packages.Code != 200)
        {
            return SoftwareCheckResult.Failed(string.IsNullOrWhiteSpace(packages.Message)
                ? $"检查更新失败（{packages.Code}）。"
                : packages.Message);
        }

        // task-151：优先精确匹配生产包名 bond-windows；仅当服务端还没这个包时才退回第一个包（兼容旧部署）。
        var package = packages.Data?.FirstOrDefault(p =>
            string.Equals(p.Code, PackageCode, StringComparison.OrdinalIgnoreCase));

        if (package is null)
        {
            package = packages.Data?.FirstOrDefault(p => SupportsCurrentPlatform(p.Platform));
        }

        package ??= packages.Data?.FirstOrDefault();

        if (package is null || string.IsNullOrWhiteSpace(package.Code))
        {
            return SoftwareCheckResult.NoPackage("暂无可用安装包（服务端还没有发布任何软件包）");
        }

        if (!SupportsCurrentPlatform(package.Platform))
        {
            return SoftwareCheckResult.NoPackage("当前系统暂无可用安装包（暂无适用于当前平台的版本）");
        }

        ApiResult<List<ApiSoftwareReleaseDto>>? releases;
        try
        {
            releases = await api.GetAsync<List<ApiSoftwareReleaseDto>>(
                $"/api/software/packages/{Uri.EscapeDataString(package.Code)}/releases");
        }
        catch (Exception ex)
        {
            return SoftwareCheckResult.Failed($"检查更新失败：{ex.Message}");
        }

        if (releases is null)
        {
            return SoftwareCheckResult.Failed(DescribeFailure());
        }

        if (releases.Code != 200)
        {
            return SoftwareCheckResult.Failed(string.IsNullOrWhiteSpace(releases.Message)
                ? $"检查更新失败（{releases.Code}）。"
                : releases.Message);
        }

        var list = (releases.Data ?? [])
            .Where(r => r is not null && r.Id > 0)
            .OrderByDescending(r => SoftwareVersion.ParseOrNull(r.Version) ?? SoftwareVersion.Zero)
            .ThenByDescending(r => r.Id)
            .ToList();

        if (list.Count == 0)
        {
            return SoftwareCheckResult.NoPackage("暂无可用安装包（该软件包还没有发布可用版本）");
        }

        // 服务端已经把最高版本算好了（latestVersion）——优先用它，别自己按发布日期/字符串排序。
        var targetVersion = package.LatestVersion?.Trim();
        ApiSoftwareReleaseDto? release = null;
        if (!string.IsNullOrWhiteSpace(targetVersion))
        {
            release = list.FirstOrDefault(r =>
                string.Equals(r.Version?.Trim(), targetVersion, StringComparison.OrdinalIgnoreCase));
        }

        // 优先稳定通道；没有则取任意通道的最新一条。
        release ??= list.FirstOrDefault(r => string.Equals(r.Channel, "stable", StringComparison.OrdinalIgnoreCase))
                    ?? list[0];

        var latest = SoftwareVersion.ParseOrNull(targetVersion)
                     ?? SoftwareVersion.ParseOrNull(release.Version)
                     ?? SoftwareVersion.Zero;
        var current = SoftwareVersion.ParseOrNull(CurrentVersion) ?? SoftwareVersion.Zero;

        var info = new SoftwareReleaseInfo
        {
            Id = release.Id,
            Version = release.Version?.Trim() ?? "",
            Channel = string.IsNullOrWhiteSpace(release.Channel) ? "stable" : release.Channel,
            FileName = string.IsNullOrWhiteSpace(release.FileName) ? $"bond-setup-{release.Version}.exe" : release.FileName,
            FileSize = release.FileSize,
            Sha256 = release.Sha256 ?? "",
            ReleaseNotes = release.ReleaseNotes ?? "",
            // DTO 用 DateTimeOffset? 接 ISO-8601，展示层仍用毫秒时间戳。
            PublishedAt = release.PublishedAt?.ToUnixTimeMilliseconds() ?? 0,
            PackageName = package.Name ?? "Bond",
        };

        return latest is not null && latest.CompareTo(current) > 0
            ? SoftwareCheckResult.Available(info)
            : SoftwareCheckResult.UpToDate(CurrentVersion, info);
    }

    public async Task<SoftwareDownloadResult> DownloadAsync(SoftwareReleaseInfo release, IProgress<double>? progress,
        CancellationToken ct = default)
    {
        if (release is null || release.Id <= 0)
        {
            return SoftwareDownloadResult.Failed("发行物信息不完整，请重新检查更新。");
        }

        var directory = UpdatesDirectory;
        Directory.CreateDirectory(directory);

        var fileName = SanitizeFileName(release.FileName);
        var target = Path.Combine(directory, fileName);
        var part = target + ".part";

        try
        {
            using var response = await api.GetStreamAsync($"/api/software/releases/{release.Id}/download");
            if (response is null)
            {
                return SoftwareDownloadResult.Failed("下载失败：无法连接服务器。");
            }

            if (!response.IsSuccessStatusCode)
            {
                return SoftwareDownloadResult.Failed($"下载失败（HTTP {(int)response.StatusCode}）。");
            }

            var total = response.Content.Headers.ContentLength is { } len && len > 0 ? len : release.FileSize;
            if (total <= 0) total = 1;

            long received = 0;
            var lastReport = 0.0;
            string actual;

            using (var source = await response.Content.ReadAsStreamAsync(ct))
            using (var destination = new FileStream(part, FileMode.Create, FileAccess.Write, FileShare.None,
                       64 * 1024, useAsync: true))
            using (var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
            {
                var buffer = new byte[64 * 1024];
                int read;
                while ((read = await source.ReadAsync(buffer, ct)) > 0)
                {
                    await destination.WriteAsync(buffer.AsMemory(0, read), ct);
                    sha.AppendData(buffer, 0, read);
                    received += read;

                    var value = Math.Clamp(received * 100.0 / total, 0, 100);
                    if (value - lastReport >= 0.5 || received == total)
                    {
                        lastReport = value;
                        progress?.Report(value);
                    }
                }

                actual = Convert.ToHexString(sha.GetHashAndReset()).ToLowerInvariant();
            }

            progress?.Report(100);

            var expected = (release.Sha256 ?? "").Trim().ToLowerInvariant();
            if (expected.Length > 0 && !string.Equals(actual, expected, StringComparison.Ordinal))
            {
                TryDelete(part);
                return SoftwareDownloadResult.VerifyFailed(actual);
            }

            File.Move(part, target, overwrite: true);
            return SoftwareDownloadResult.Ok(target, release.Version, actual);
        }
        catch (OperationCanceledException)
        {
            TryDelete(part);
            return SoftwareDownloadResult.Failed("下载已取消。");
        }
        catch (Exception ex)
        {
            TryDelete(part);
            return SoftwareDownloadResult.Failed($"下载失败：{ex.Message}");
        }
    }

    /// <summary>
    /// 把最近一次失败翻译成人话：网络 / HTTP(401/403/5xx) / JSON 解析失败三类分开，
    /// 不再把所有失败都写成「无法连接更新服务器」。
    /// </summary>
    private string DescribeFailure()
    {
        var message = string.IsNullOrWhiteSpace(api.LastError) ? null : api.LastError;
        return api.LastFailure switch
        {
            ApiFailureKind.Json => message is null ? "更新服务响应解析失败，请稍后重试。" : $"更新服务响应解析失败：{message}",
            ApiFailureKind.Network => message is null ? "无法连接更新服务器，请检查网络后重试。" : $"无法连接更新服务器：{message}",
            ApiFailureKind.Http when api.LastHttpStatus == 401 => "登录状态已失效，请重新登录后再检查更新。",
            ApiFailureKind.Http when api.LastHttpStatus == 403 => "当前账号无权检查更新。",
            ApiFailureKind.Http when api.LastHttpStatus >= 500 =>
                $"更新服务器暂时不可用（HTTP {api.LastHttpStatus}），请稍后重试。",
            ApiFailureKind.Http => message ?? $"检查更新失败（HTTP {api.LastHttpStatus}）。",
            _ => message ?? "无法连接更新服务器，请检查网络后重试。"
        };
    }

    // task-150：应用下载目录（设置页「应用下载」分区）已随 UI 一起删除，相关服务代码不再保留。

    private static async Task<string> ComputeSha256Async(string path, CancellationToken ct)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read,
            64 * 1024, useAsync: true);
        using var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var buffer = new byte[64 * 1024];
        int read;
        while ((read = await stream.ReadAsync(buffer, ct)) > 0)
        {
            sha.AppendData(buffer, 0, read);
        }

        return Convert.ToHexString(sha.GetHashAndReset()).ToLowerInvariant();
    }

    public static string UpdatesDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Bond", "updates");

    private static string ResolveCurrentVersion()
    {
        try
        {
            var assembly = Assembly.GetEntryAssembly();
            var informational = assembly?.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
            if (!string.IsNullOrWhiteSpace(informational))
            {
                // InformationalVersion 可能带 +git-commit 后缀，去尾。
                var plus = informational.IndexOf('+');
                return (plus > 0 ? informational[..plus] : informational).Trim();
            }

            var version = assembly?.GetName().Version;
            return version is null ? "0.0.0" : $"{version.Major}.{version.Minor}.{Math.Max(0, version.Build)}";
        }
        catch
        {
            return "0.0.0";
        }
    }

    private static bool SupportsCurrentPlatform(string? platform)
    {
        if (string.IsNullOrWhiteSpace(platform)) return true;
        var value = platform.Trim().ToLowerInvariant();
        if (value is "any" or "all" or "universal") return true;

        if (OperatingSystem.IsWindows())
        {
            return value is "win" or "win32" or "win64" or "windows" or "windows-x64" or "win-x64"
                or "windows-x86" or "win-x86" or "windows-arm64" or "win-arm64";
        }

        if (OperatingSystem.IsMacOS())
        {
            return value.StartsWith("mac", StringComparison.Ordinal) || value is "darwin" or "osx";
        }

        return value.StartsWith("linux", StringComparison.Ordinal) || value is "unix";
    }

    private static string SanitizeFileName(string? fileName)
    {
        var name = Path.GetFileName(fileName ?? "");
        if (string.IsNullOrWhiteSpace(name)) return "bond-setup.exe";

        foreach (var ch in Path.GetInvalidFileNameChars())
        {
            name = name.Replace(ch, '_');
        }

        return name;
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
        }
        catch
        {
            // 忽略清理失败：残留 .part 文件不影响后续下载（写入用 Create 覆盖）。
        }
    }
}
