using System;

namespace Bond.Models;

/// <summary>关于与更新的界面状态。</summary>
public enum UpdateState
{
    Idle,
    Checking,
    UpToDate,
    NoPackage,
    Available,
    Downloading,
    Downloaded,
    VerifyFailed,
    Ignored,
    Error,
}

/// <summary>
/// task-141：检查更新的频率。默认 <see cref="Never"/>，由用户在设置里显式选择后才生效；
/// 不做后台定时轮询，只在应用启动时按此策略决定要不要查一次。
/// </summary>
public enum UpdateFrequency
{
    Never,
    OnLaunch,
    Daily,
    Weekly,
}

/// <summary>task-141：更新通道（先只实现 stable，beta 仅预留枚举值）。</summary>
public enum UpdateChannel
{
    Stable,
    Beta,
}

/// <summary>语义化版本：只比较 major.minor.patch，忽略 +commit 与 -preview 后缀。</summary>
public sealed class SoftwareVersion : IComparable<SoftwareVersion>
{
    public static readonly SoftwareVersion Zero = new(0, 0, 0);

    public int Major { get; }
    public int Minor { get; }
    public int Patch { get; }

    public SoftwareVersion(int major, int minor, int patch)
    {
        Major = major;
        Minor = minor;
        Patch = patch;
    }

    public static SoftwareVersion? ParseOrNull(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;

        var value = text.Trim();
        if (value.StartsWith('v') || value.StartsWith('V')) value = value[1..];

        var cut = value.IndexOfAny(['+', '-', ' ']);
        if (cut >= 0) value = value[..cut];

        var parts = value.Split('.');
        if (parts.Length is < 1 or > 4) return null;

        var numbers = new int[4];
        for (var i = 0; i < parts.Length; i++)
        {
            if (!int.TryParse(parts[i], out numbers[i]) || numbers[i] < 0) return null;
        }

        return new SoftwareVersion(numbers[0], numbers[1], numbers[2]);
    }

    public int CompareTo(SoftwareVersion? other)
    {
        if (other is null) return 1;
        if (Major != other.Major) return Major.CompareTo(other.Major);
        if (Minor != other.Minor) return Minor.CompareTo(other.Minor);
        return Patch.CompareTo(other.Patch);
    }

    public bool IsNewerThan(SoftwareVersion other) => CompareTo(other) > 0;

    public override string ToString() => $"{Major}.{Minor}.{Patch}";
}

/// <summary>一条可下载的发行物。</summary>
public sealed class SoftwareReleaseInfo
{
    public long Id { get; set; }
    public string Version { get; set; } = "";
    public string Channel { get; set; } = "stable";
    public string FileName { get; set; } = "";
    public long FileSize { get; set; }
    public string Sha256 { get; set; } = "";
    public string ReleaseNotes { get; set; } = "";
    public long PublishedAt { get; set; }
    public string PackageName { get; set; } = "Bond";
}

/// <summary>检查更新的结果。</summary>
public sealed class SoftwareCheckResult
{
    public UpdateState State { get; init; }
    public string Message { get; init; } = "";
    public string CurrentVersion { get; init; } = "";
    public string LatestVersion { get; init; } = "";
    public SoftwareReleaseInfo? Release { get; init; }

    /// <summary>
    /// task-141：强制更新判定。服务端当前契约（/api/software/packages）尚未返回
    /// forceUpdate / minSupportedVersion 字段，DTO 先保留这两个位置；等后端补齐后
    /// 由 <c>BondApi.Software</c> 的映射填充，客户端判定逻辑（UpdateService）无需改动。
    /// </summary>
    public bool ForceUpdate { get; set; }

    /// <summary>task-141：服务端声明的最低可运行版本（预留，语义同 <see cref="ForceUpdate"/>）。</summary>
    public string? MinSupportedVersion { get; set; }

    public static SoftwareCheckResult Failed(string message) =>
        new() { State = UpdateState.Error, Message = message };

    public static SoftwareCheckResult NoPackage(string message) =>
        new() { State = UpdateState.NoPackage, Message = message };

    public static SoftwareCheckResult UpToDate(string current, SoftwareReleaseInfo? release) => new()
    {
        State = UpdateState.UpToDate,
        Message = "已是最新版本",
        CurrentVersion = current,
        LatestVersion = release?.Version ?? current,
        Release = release,
    };

    public static SoftwareCheckResult Available(SoftwareReleaseInfo release) => new()
    {
        State = UpdateState.Available,
        Message = $"发现新版本 {release.Version}，可下载安装包更新",
        LatestVersion = release.Version,
        Release = release,
    };
}

/// <summary>下载安装包的结果。</summary>
public sealed class SoftwareDownloadResult
{
    public bool Success { get; init; }
    public bool Verified { get; init; }
    public string? Path { get; init; }
    public string Message { get; init; } = "";
    public string? ActualSha256 { get; init; }
    public string Version { get; init; } = "";

    public static SoftwareDownloadResult Ok(string path, string version, string actualSha256) => new()
    {
        Success = true,
        Verified = true,
        Path = path,
        Version = version,
        ActualSha256 = actualSha256,
        Message = "安装包已下载并通过 SHA-256 校验",
    };

    public static SoftwareDownloadResult VerifyFailed(string actualSha256) => new()
    {
        Success = false,
        Verified = false,
        ActualSha256 = actualSha256,
        Message = "安装包校验失败（SHA-256 不匹配），已删除损坏文件，请重新下载。",
    };

    public static SoftwareDownloadResult Failed(string message) => new()
    {
        Success = false,
        Message = message,
    };
}
