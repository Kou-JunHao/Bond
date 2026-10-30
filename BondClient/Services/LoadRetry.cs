using System;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace Bond.Services;

/// <summary>
/// task-43：列表三态（加载中 / 失败 / 空）与首次加载自动重试的公共口径。
/// <para>task-151：退避参数与失败文案统一收敛到 <see cref="RetryPolicy"/> 与 <see cref="Humanize(string?,string)"/>，
/// 各页面不再自己写一套重试循环。</para>
/// <para>首次加载失败按 2s / 4s / 8s 指数退避自动重试（最多 3 次，共 4 次尝试），成功后由调用方清错误。</para>
/// </summary>
public static class LoadRetry
{
    /// <summary>取消等待/重试时抛出，便于调用方区分「用户取消」与「加载失败」。</summary>
    public static TimeSpan FirstLoadDelay(int attemptIndex)
        => RetryPolicy.DelayFor(RetryScenario.FirstLoad, attemptIndex);

    /// <summary>
    /// 首次加载：执行 <paramref name="attempt"/>，返回人类可读错误（null 表示成功）。
    /// 失败后按退避自动重试；最后一次仍失败则返回最终错误文案。
    /// </summary>
    public static async Task<string?> FirstLoadAsync(
        Func<Task<string?>> attempt,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(attempt);

        var rule = RetryPolicy.For(RetryScenario.FirstLoad);
        var error = await attempt().ConfigureAwait(false);

        for (var retry = 0; retry < rule.Delays.Length && error is not null; retry++)
        {
            await Task.Delay(rule.Delays[retry], ct).ConfigureAwait(false);
            ct.ThrowIfCancellationRequested();

            error = await attempt().ConfigureAwait(false);
        }

        return error;
    }

    /// <summary>网络层错误（断网 / DNS / 超时 / 连接被拒）的统一话术。</summary>
    public const string NetworkMessage = "无法连接到服务器，请检查网络后重试";

    /// <summary>任务-151：把底层错误折成一句人话。断网 / 超时 / DNS 统一话术；HTTP 状态给出全应用同一句中文；其余透传服务端 message。</summary>
    public static string Humanize(string? raw, string fallback = "加载失败，请稍后重试")
    {
        if (string.IsNullOrWhiteSpace(raw)) return fallback;

        // 优先识别 HTTP 状态：BondApiClient.LastError 形如「请求失败（HTTP 500）。」「登录状态已失效（HTTP 401），请重新登录。」
        var status = TryReadHttpStatus(raw);
        if (status > 0)
        {
            return Humanize(status, raw, fallback);
        }

        if (IsNetworkLike(raw)) return NetworkMessage;

        return raw;
    }

    /// <summary>
    /// task-151：按 HTTP 状态码统一文案（全应用同一类错误同一句中文）。
    /// <paramref name="raw"/> 仅在 4xx 业务错误时作为服务端原文透传，其余情况一律用标准话术。
    /// </summary>
    public static string Humanize(int httpStatus, string? raw, string fallback = "操作失败，请稍后重试")
    {
        if (httpStatus <= 0) return Humanize(raw, fallback);

        return httpStatus switch
        {
            400 or 422 => ServerMessageOr(raw, fallback),
            401 => "登录状态已失效，请重新登录",
            403 => "没有权限执行此操作",
            404 => ServerMessageOr(raw, "请求的内容不存在或已被删除"),
            408 => "请求超时，请检查网络后重试",
            409 => "操作冲突，请刷新后重试",
            413 => "内容太大，服务器拒绝接收",
            429 => "操作太频繁，请稍后再试",
            >= 500 and <= 599 => "服务器暂时不可用，请稍后重试",
            _ => ServerMessageOr(raw, fallback)
        };
    }

    /// <summary>服务端 message 若是「请求失败（HTTP xxx）」这类框架兜底串则丢弃，改用调用方 fallback。</summary>
    private static string ServerMessageOr(string? raw, string fallback)
    {
        if (string.IsNullOrWhiteSpace(raw)) return fallback;
        if (TryReadHttpStatus(raw) > 0) return fallback; // 「请求失败（HTTP 500）。」这类框架串没有信息量
        return raw;
    }

    private static readonly Regex HttpStatusRegex =
        new(@"HTTP\s*(\d{3})", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);

    private static int TryReadHttpStatus(string raw)
    {
        var match = HttpStatusRegex.Match(raw);
        return match.Success && int.TryParse(match.Groups[1].Value, out var status) ? status : 0;
    }

    private static bool IsNetworkLike(string raw)
        => raw.Contains("timed out", StringComparison.OrdinalIgnoreCase)
           || raw.Contains("timeout", StringComparison.OrdinalIgnoreCase)
           || raw.Contains("actively refused", StringComparison.OrdinalIgnoreCase)
           || raw.Contains("connection refused", StringComparison.OrdinalIgnoreCase)
           || raw.Contains("resolve", StringComparison.OrdinalIgnoreCase)
           || raw.Contains("network", StringComparison.OrdinalIgnoreCase)
           || raw.Contains("No such host", StringComparison.OrdinalIgnoreCase)
           || raw.Contains("Unable to connect", StringComparison.OrdinalIgnoreCase)
           || raw.Contains("无法连接", StringComparison.OrdinalIgnoreCase)
           || raw.Contains("网络请求失败", StringComparison.OrdinalIgnoreCase);
}
