using System;
using System.Globalization;
using Bond.Models;
using Bond.ViewModels;

namespace Bond.Services.Updates;

/// <summary>
/// task-141：检查更新的策略（频率 / 自动下载 / 通道 / 忽略的版本）。
/// 所有取值都落在 <c>prefs.json</c>；默认「从不」，应用不写自启项、不做后台轮询。
/// </summary>
public interface IUpdatePolicy
{
    UpdateFrequency Frequency { get; }

    bool AutoDownload { get; }

    UpdateChannel Channel { get; }

    /// <summary>用户选择「忽略此版本」的版本号；为空表示没有忽略。</summary>
    string? IgnoredVersion { get; }

    /// <summary>上次检查时间（本地时间）；从未检查为 null。</summary>
    DateTimeOffset? LastCheckedAt { get; }

    /// <summary>按频率判断此刻是否该检查（OnLaunch 永远为真，Never 永远为假）。</summary>
    bool IsDue(DateTimeOffset now);

    /// <summary>记录一次成功的检查时间。</summary>
    void RecordChecked(DateTimeOffset now);

    void SetFrequency(UpdateFrequency frequency);

    void SetAutoDownload(bool value);

    void SetChannel(UpdateChannel channel);

    void IgnoreVersion(string? version);
}

/// <summary>基于 <see cref="LocalPrefs"/> 的默认实现。</summary>
public sealed class UpdatePolicy : IUpdatePolicy
{
    public UpdateFrequency Frequency => ParseFrequency(LocalPrefs.Current.UpdateFrequency);

    public bool AutoDownload => LocalPrefs.Current.UpdateAutoDownload;

    public UpdateChannel Channel => ParseChannel(LocalPrefs.Current.UpdateChannel);

    public string? IgnoredVersion
    {
        get
        {
            var value = LocalPrefs.Current.IgnoredUpdateVersion;
            return string.IsNullOrWhiteSpace(value) ? null : value;
        }
    }

    public DateTimeOffset? LastCheckedAt
    {
        get
        {
            var raw = LocalPrefs.Current.UpdateLastCheckedAt;
            if (string.IsNullOrWhiteSpace(raw)) return null;
            return DateTimeOffset.TryParse(raw, CultureInfo.InvariantCulture, DateTimeStyles.None, out var parsed)
                ? parsed
                : null;
        }
    }

    public bool IsDue(DateTimeOffset now) => Frequency switch
    {
        UpdateFrequency.Never => false,
        UpdateFrequency.OnLaunch => true,
        UpdateFrequency.Daily => LastCheckedAt is not { } last || now - last >= TimeSpan.FromDays(1),
        UpdateFrequency.Weekly => LastCheckedAt is not { } last || now - last >= TimeSpan.FromDays(7),
        _ => false,
    };

    public void RecordChecked(DateTimeOffset now)
    {
        var prefs = LocalPrefs.Current;
        prefs.UpdateLastCheckedAt = now.ToString("O", CultureInfo.InvariantCulture);
        prefs.Save();
    }

    public void SetFrequency(UpdateFrequency frequency)
    {
        var prefs = LocalPrefs.Current;
        prefs.UpdateFrequency = ToStorageValue(frequency);
        if (frequency == UpdateFrequency.OnLaunch) prefs.UpdateLastCheckedAt = "";
        prefs.Save();
    }

    public void SetAutoDownload(bool value)
    {
        var prefs = LocalPrefs.Current;
        prefs.UpdateAutoDownload = value;
        prefs.Save();
    }

    public void SetChannel(UpdateChannel channel)
    {
        var prefs = LocalPrefs.Current;
        prefs.UpdateChannel = channel == UpdateChannel.Beta ? "beta" : "stable";
        prefs.Save();
    }

    public void IgnoreVersion(string? version)
    {
        var prefs = LocalPrefs.Current;
        prefs.IgnoredUpdateVersion = string.IsNullOrWhiteSpace(version) ? "" : version.Trim();
        prefs.Save();
    }

    public static string ToStorageValue(UpdateFrequency frequency) => frequency switch
    {
        UpdateFrequency.OnLaunch => "launch",
        UpdateFrequency.Daily => "daily",
        UpdateFrequency.Weekly => "weekly",
        _ => "never",
    };

    public static UpdateFrequency ParseFrequency(string? value) => value?.Trim().ToLowerInvariant() switch
    {
        "launch" => UpdateFrequency.OnLaunch,
        "daily" => UpdateFrequency.Daily,
        "weekly" => UpdateFrequency.Weekly,
        _ => UpdateFrequency.Never,
    };

    private static UpdateChannel ParseChannel(string? value) =>
        string.Equals(value?.Trim(), "beta", StringComparison.OrdinalIgnoreCase)
            ? UpdateChannel.Beta
            : UpdateChannel.Stable;
}
