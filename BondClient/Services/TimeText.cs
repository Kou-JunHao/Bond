using System;

namespace Bond.Services;

/// <summary>
/// 统一的本机时区时间格式化（task-111）。服务端时间一律是 UTC 或 Unix 毫秒，
/// UI 只展示本机时间；新增的时间显示一律走这里，别再写裸 ToString()。
/// </summary>
public static class TimeText
{
    public const string DateTimePattern = "yyyy-MM-dd HH:mm";

    /// <summary>DateTimeOffset → 本机时区 `yyyy-MM-dd HH:mm`。</summary>
    public static string Local(DateTimeOffset value) => value.ToLocalTime().ToString(DateTimePattern);

    /// <summary>Unix 毫秒 → 本机时区；&lt;=0 视为无效，返回 <paramref name="empty"/>。</summary>
    public static string Local(long unixMillis, string empty = "—") =>
        unixMillis <= 0 ? empty : Local(DateTimeOffset.FromUnixTimeMilliseconds(unixMillis));

    /// <summary>DateTimeOffset? → 本机时区；null 返回空串（适合拼进 Metadata 行）。</summary>
    public static string LocalOrEmpty(DateTimeOffset? value) => value is { } v ? Local(v) : string.Empty;
}
