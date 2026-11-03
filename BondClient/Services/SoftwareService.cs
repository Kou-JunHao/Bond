using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
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

        var expected = NormalizeSha256(release.Sha256);
        if (expected.Length != 64)
        {
            return SoftwareDownloadResult.Failed("服务端未提供有效的 SHA-256 校验值，已取消下载。请稍后重新检查更新。");
        }

        var directory = UpdatesDirectory;
        try
        {
            Directory.CreateDirectory(directory);
        }
        catch (Exception ex)
        {
            return SoftwareDownloadResult.Failed($"无法创建下载目录：{ex.Message}");
        }

        var fileName = SanitizeFileName(release.FileName);
        var target = Path.Combine(directory, fileName);
        var part = target + ".part";

        if (!HasEnoughDiskSpace(directory, release.FileSize, out var spaceError))
        {
            return SoftwareDownloadResult.Failed(spaceError);
        }

        // 已经下载过且校验通过：直接复用，避免重复下载 80MB。
        if (File.Exists(target))
        {
            try
            {
                var existing = await ComputeSha256Async(target, ct).ConfigureAwait(false);
                if (string.Equals(existing, expected, StringComparison.Ordinal))
                {
                    progress?.Report(100);
                    CleanupOldInstallers(directory, target);
                    return SoftwareDownloadResult.Ok(target, release.Version, existing,
                        "安装包已存在且 SHA-256 校验通过，无需重复下载");
                }
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch
            {
                // 旧文件读不了就删掉重下。
            }

            TryDelete(target);
        }

        TryDelete(part);

        string? lastError = null;
        for (var attempt = 1; attempt <= 2; attempt++)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                var result = await DownloadAttemptAsync(release, expected, part, target, progress, ct)
                    .ConfigureAwait(false);
                if (result.Success)
                {
                    CleanupOldInstallers(directory, target);
                }

                return result;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                TryDelete(part);
                return SoftwareDownloadResult.Failed("下载已取消。");
            }
            catch (Exception ex)
            {
                lastError = ex.Message;
                TryDelete(part);
                if (attempt < 2)
                {
                    try
                    {
                        await Task.Delay(TimeSpan.FromMilliseconds(800), ct).ConfigureAwait(false);
                    }
                    catch (OperationCanceledException)
                    {
                        return SoftwareDownloadResult.Failed("下载已取消。");
                    }
                }
            }
        }

        return SoftwareDownloadResult.Failed($"下载失败：{lastError ?? "未知错误"}");
    }

    private async Task<SoftwareDownloadResult> DownloadAttemptAsync(
        SoftwareReleaseInfo release, string expected, string part, string target,
        IProgress<double>? progress, CancellationToken ct)
    {
        using var http = CreateDownloadHttp();
        using var request = new HttpRequestMessage(HttpMethod.Get,
            $"/api/software/releases/{release.Id}/download");
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct)
            .ConfigureAwait(false);

        var status = (int)response.StatusCode;
        if (!response.IsSuccessStatusCode)
        {
            if (status == 401) return SoftwareDownloadResult.Failed("登录状态已失效，请重新登录后再下载更新。");
            if (status == 403) return SoftwareDownloadResult.Failed("当前账号无权下载该安装包。");
            if (status == 404) return SoftwareDownloadResult.Failed("安装包不存在或已下架，请重新检查更新。");
            if (status == 416) return SoftwareDownloadResult.Failed("服务器不支持该下载范围，请重新下载。");
            if (status >= 500)
            {
                throw new HttpRequestException($"更新服务器暂时不可用（HTTP {status}）。");
            }

            return SoftwareDownloadResult.Failed($"下载失败（HTTP {status}）。");
        }

        var total = response.Content.Headers.ContentLength is { } len && len > 0 ? len : release.FileSize;
        if (release.FileSize > 0 && total > 0 && total != release.FileSize)
        {
            return SoftwareDownloadResult.Failed(
                $"下载失败：服务端文件大小（{total} 字节）与发布信息（{release.FileSize} 字节）不一致。");
        }

        if (total < 0) total = 0;

        long received = 0;
        var lastReport = 0.0;
        string actual;

        using (var source = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false))
        using (var destination = new FileStream(part, FileMode.Create, FileAccess.Write, FileShare.None,
                   128 * 1024, useAsync: true))
        using (var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
        {
            var buffer = new byte[128 * 1024];
            while (true)
            {
                ct.ThrowIfCancellationRequested();

                int read;
                using (var readCts = CancellationTokenSource.CreateLinkedTokenSource(ct))
                {
                    // 单次读取 60 秒没有数据就判定为卡死；总时长不设上限，慢链路也能下完。
                    readCts.CancelAfter(TimeSpan.FromSeconds(60));
                    try
                    {
                        read = await source.ReadAsync(buffer.AsMemory(0, buffer.Length), readCts.Token)
                            .ConfigureAwait(false);
                    }
                    catch (OperationCanceledException) when (!ct.IsCancellationRequested)
                    {
                        throw new IOException("下载超时：60 秒没有收到数据，请检查网络后重试。");
                    }
                }

                if (read <= 0) break;

                await destination.WriteAsync(buffer.AsMemory(0, read), ct).ConfigureAwait(false);
                sha.AppendData(buffer, 0, read);
                received += read;

                if (total > 0)
                {
                    var value = Math.Clamp(received * 100.0 / total, 0, 100);
                    if (value - lastReport >= 0.5 || received == total)
                    {
                        lastReport = value;
                        progress?.Report(value);
                    }
                }
            }

            actual = Convert.ToHexString(sha.GetHashAndReset()).ToLowerInvariant();
        }

        if (received <= 0)
        {
            throw new IOException("下载内容为空，请稍后重试。");
        }

        if (total > 0 && received != total)
        {
            throw new IOException($"下载不完整（已接收 {received} / {total} 字节），请重试。");
        }

        progress?.Report(100);

        if (!string.Equals(actual, expected, StringComparison.Ordinal))
        {
            TryDelete(part);
            return SoftwareDownloadResult.VerifyFailed(actual);
        }

        try
        {
            File.Move(part, target, overwrite: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            TryDelete(part);
            return SoftwareDownloadResult.Failed(
                $"无法保存安装包：{ex.Message}。请关闭正在运行的安装程序后重试。");
        }

        return SoftwareDownloadResult.Ok(target, release.Version, actual);
    }

    private HttpClient CreateDownloadHttp()
    {
        var http = new HttpClient
        {
            BaseAddress = new Uri(api.BaseUrl),
            // 下载大文件不设总超时；读取阶段用 60 秒无数据判定卡死。
            Timeout = Timeout.InfiniteTimeSpan,
        };

        if (!string.IsNullOrWhiteSpace(api.AccessToken))
        {
            http.DefaultRequestHeaders.Authorization =
                new AuthenticationHeaderValue("Bearer", api.AccessToken);
        }

        http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/octet-stream"));
        return http;
    }

    private static bool HasEnoughDiskSpace(string directory, long fileSize, out string error)
    {
        error = "";
        if (fileSize <= 0) return true;

        try
        {
            var root = Path.GetPathRoot(Path.GetFullPath(directory));
            if (string.IsNullOrWhiteSpace(root)) return true;

            var drive = new DriveInfo(root);
            if (!drive.IsReady) return true;

            // 安装包本身 + 64MB 余量（解压/临时文件/日志）。
            var required = fileSize + 64L * 1024 * 1024;
            if (drive.AvailableFreeSpace >= required) return true;

            error = $"磁盘空间不足：安装包需要 {FormatSize(fileSize)}，当前可用 {FormatSize(drive.AvailableFreeSpace)}。";
            return false;
        }
        catch
        {
            return true;
        }
    }

    private static void CleanupOldInstallers(string directory, string keep)
    {
        try
        {
            foreach (var file in Directory.EnumerateFiles(directory, "*.exe"))
            {
                if (string.Equals(file, keep, StringComparison.OrdinalIgnoreCase)) continue;
                var name = Path.GetFileName(file);
                if (name.StartsWith("Bond-", StringComparison.OrdinalIgnoreCase)
                    && name.Contains("-setup", StringComparison.OrdinalIgnoreCase))
                {
                    TryDelete(file);
                }
            }

            foreach (var part in Directory.EnumerateFiles(directory, "*.part"))
            {
                TryDelete(part);
            }
        }
        catch
        {
            // 清理失败不影响本次下载。
        }
    }

    private static string NormalizeSha256(string? value) =>
        (value ?? "").Trim().ToLowerInvariant();

    private static string FormatSize(long bytes)
    {
        if (bytes <= 0) return "—";
        string[] units = ["B", "KB", "MB", "GB"];
        double value = bytes;
        var unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }

        return unit == 0 ? $"{value:0} B" : $"{value:0.0} {units[unit]}";
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
