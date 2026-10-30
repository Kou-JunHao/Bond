using System;

namespace Bond.Models;

/// <summary>
/// 用量百分比的统一文案（task-4）。
/// <para>
/// 用户反馈「空间详情已用 0%」：真实用量 0.4% 会被 <c>{0:0}%</c> 四舍五入成 <c>0%</c>，
/// 看起来像一点都没占用，容易被误读。规则：
/// </para>
/// <list type="bullet">
///   <item><description>真的 0 字节 → <c>0%</c>；</description></item>
///   <item><description>已用 &gt; 0 但不足 1% → <c>&lt;1%</c>；</description></item>
///   <item><description>其余按整数百分比（与原来一致，不引入小数噪声）。</description></item>
/// </list>
/// </summary>
public static class QuotaFormat
{
    /// <summary>已用 / 总量 → 展示用百分比文案。</summary>
    /// <param name="oneDecimal">悬浮球用：非零百分比保留 1 位小数（与改动前观感一致）。</param>
    public static string Percent(long usedBytes, long totalBytes, bool oneDecimal = false)
    {
        if (usedBytes <= 0) return "0%";
        if (totalBytes <= 0) return "<1%";

        var ratio = Math.Clamp((double)usedBytes / totalBytes, 0d, 1d);
        if (ratio * 100 < 1) return "<1%";
        return oneDecimal ? $"{ratio * 100:0.#}%" : $"{ratio * 100:0}%";
    }

    /// <summary>已用 / 总量 → 进度条比例（0..1）。</summary>
    public static double Ratio(long usedBytes, long totalBytes)
        => totalBytes <= 0 ? 0d : Math.Clamp((double)usedBytes / totalBytes, 0d, 1d);
}
