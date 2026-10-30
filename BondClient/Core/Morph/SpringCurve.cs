using System;

namespace Bond.Core.Morph;

/// <summary>
/// 阻尼谐振子（damped harmonic oscillator）弹簧曲线。
/// <para>
/// Bond 悬浮球的全部形变动画都由这套曲线驱动——它不是常见的 cubic ease，
/// 而是真实弹簧解的一阶归一化，所以才有"起步快、末端轻微回弹"的手感。
/// 与 <c>BondClient/Views/FloatingBallWindow.axaml.cs</c> 中的
/// <c>ComputeSpringEnd</c> / <c>SpringNorm</c> 逐行等价（保持动画零漂移）。
/// </para>
/// <para>
/// 时间在内部按 <c>[0,1]</c> 归一化：external 只负责把真实秒数映射进来，
/// 因此这个类是纯函数集合，可以在没有 UI 的情况下做数值验证。
/// </para>
/// </summary>
public static class SpringCurve
{
    /// <summary>
    /// 弹簧在 t=1 时刻的实际位移量。
    /// <para>
    /// 因为 1 秒处弹簧尚未完全静止，原始位移并不等于 1；用这个值做归一化，
    /// 才能让动画在 t=1 精确落在目标值上（否则会永远差一点点）。
    /// </para>
    /// </summary>
    public static double EndValue(double zeta, double omega)
    {
        if (zeta >= 1.0)
            return 1.0 - (1.0 + omega) * Math.Exp(-omega);

        var omegaD = omega * Math.Sqrt(1.0 - zeta * zeta);
        return 1.0 - Math.Exp(-zeta * omega)
                     * (Math.Cos(omegaD) + zeta * omega / omegaD * Math.Sin(omegaD));
    }

    /// <summary>归一化弹簧位移：正常为 0..1，欠阻尼时可能瞬时略超 1（这正是回弹感的来源）。</summary>
    public static double Normalized(double t, double zeta, double omega, double endValue)
    {
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        if (Math.Abs(endValue) < 0.001) return t;

        double raw;
        if (zeta >= 1.0)
        {
            raw = 1.0 - (1.0 + omega * t) * Math.Exp(-omega * t);
        }
        else
        {
            var omegaD = omega * Math.Sqrt(1.0 - zeta * zeta);
            raw = 1.0 - Math.Exp(-zeta * omega * t)
                        * (Math.Cos(omegaD * t) + zeta * omega / omegaD * Math.Sin(omegaD * t));
        }

        return raw / endValue;
    }

    /// <summary>便捷重载：内部自行计算归一化端点。</summary>
    public static double Normalized(double t, double zeta, double omega)
        => Normalized(t, zeta, omega, EndValue(zeta, omega));

    /// <summary>Ken Perlin 的 smootherstep：一阶与二阶导数在两端均为 0。</summary>
    public static double SmoothStep(double t) => t * t * t * (t * (6.0 * t - 15.0) + 10.0);

    /// <summary>Apple 风格减速：起步猛、尾巴长。</summary>
    public static double AppleDecelerate(double t) => 1.0 - Math.Pow(1.0 - t, 4.0);

    public static double EaseOutCubic(double t) => 1.0 - (1.0 - t) * (1.0 - t) * (1.0 - t);

    public static double Lerp(double a, double b, double t) => a + (b - a) * t;

    public static double Clamp01(double v) => v < 0.0 ? 0.0 : v > 1.0 ? 1.0 : v;

    public static int ClampInt(double v, int min, int max)
        => v < min ? min : v > max ? max : (int)Math.Round(v);
}
