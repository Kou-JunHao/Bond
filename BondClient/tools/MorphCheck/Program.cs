using System;
using Bond.Core.Morph;

namespace Bond.Tools.MorphCheck;

/// <summary>
/// 形变动画的数值回归校验。
///
/// 目的：重构后必须证明"弹簧曲线与圆角混合公式没有任何漂移"。
/// 这个工具不启动 UI，直接对 SpringCurve / MorphMath 取样，输出：
///   1. 曲线端点是否精确落在 0 / 1
///   2. 圆角是否在球体尺寸处等于半径（正圆），在面板尺寸处等于目标圆角
///   3. 整条 morph 轨迹的宽高/圆角是否连续（无 NaN、无跳变）
///
/// 用法：dotnet run --project tools/MorphCheck
/// 退出码非 0 表示回归失败。
/// </summary>
internal static class Program
{
    private static int _failures;

    private static int Main()
    {
        Console.WriteLine("Bond morph regression check");
        Console.WriteLine("===========================");

        CheckSpringEndpoints();
        CheckCornerRadius();
        CheckContentOpacity();
        CheckTrajectoryContinuity();

        Console.WriteLine();
        if (_failures == 0)
        {
            Console.WriteLine("ALL CHECKS PASSED");
            return 0;
        }

        Console.WriteLine($"FAILED: {_failures} check(s)");
        return 1;
    }

    // ─────────────────────────────────────────────────────────────
    private static void CheckSpringEndpoints()
    {
        Console.WriteLine("\n[1] 弹簧曲线端点");

        AssertClose("Border  归一化在 t=1 收敛到 1",
            SpringCurve.Normalized(1.0, MorphSpec.Border.Zeta, MorphSpec.Border.Omega,
                MorphSpec.Border.EndValue), 1.0, 0.001);

        AssertClose("Icon    归一化在 t=1 收敛到 1",
            SpringCurve.Normalized(1.0, MorphSpec.Icon.Zeta, MorphSpec.Icon.Omega,
                MorphSpec.Icon.EndValue), 1.0, 0.001);

        AssertClose("Pill    归一化在 t=1 收敛到 1",
            SpringCurve.Normalized(1.0, MorphSpec.PillSquash.Zeta, MorphSpec.PillSquash.Omega,
                MorphSpec.PillSquash.EndValue), 1.0, 0.001);

        AssertClose("t=0 位移为 0", SpringCurve.Normalized(0.0, 0.92, 4.5), 0.0, 1e-9);

        // 主体弹簧(zeta=0.92)接近临界阻尼：设计上"不过冲"，即宽高平滑落位、没有回弹。
        // 这一点很关键——旧版观感是"顺滑地长成大卡片"，而不是"弹出来"。
        var borderPeak = Peak(MorphSpec.Border);
        AssertTrue($"Border(zeta=0.92) 不过冲（峰值 {borderPeak:0.00000} <= 1.001）", borderPeak <= 1.001);

        // 回弹感由 Pill 挤压(0.32) 与 Hover(0.6) 提供。
        var pillPeak = Peak(MorphSpec.PillSquash);
        AssertTrue($"PillSquash(zeta=0.32) 明显过冲（峰值 {pillPeak:0.0000} > 1.05）", pillPeak > 1.05);

        var hoverPeak = Peak(MorphSpec.Hover);
        AssertTrue($"Hover(zeta=0.6) 轻微过冲（峰值 {hoverPeak:0.0000} > 1.01）", hoverPeak > 1.01);

        // 临界阻尼分支必须单调不减
        var prev = -1.0;
        var monotonic = true;
        for (var t = 0.0; t <= 1.0; t += 0.01)
        {
            var v = SpringCurve.Normalized(t, 1.0, 6.0);
            if (v < prev - 1e-9) monotonic = false;
            prev = v;
        }

        AssertTrue("临界阻尼(zeta=1)分支单调不减", monotonic);
    }

    // ─────────────────────────────────────────────────────────────
    private static void CheckCornerRadius()
    {
        Console.WriteLine("\n[2] 圆角混合");

        // 球体尺寸：圆角必须正好是半径，否则球不圆
        var ballCr = MorphMath.CornerRadius(
            MorphSpec.BallSize, MorphSpec.BallSize,
            MorphSpec.BallCornerRadius, MorphSpec.PanelCornerRadius,
            0.0, CornerBlendMode.FromBall, MorphSpec.BallSize);

        AssertClose("球体尺寸时圆角 = 半径", ballCr, MorphSpec.BallSize / 2.0, 0.6);

        // 面板尺寸（远大于球）：圆角必须收敛到面板圆角
        var panelCr = MorphMath.CornerRadius(
            400, 400,
            MorphSpec.BallCornerRadius, MorphSpec.PanelCornerRadius,
            1.0, CornerBlendMode.FromBall, MorphSpec.BallSize);

        AssertClose("远大于球体时圆角 = 面板圆角", panelCr, MorphSpec.PanelCornerRadius, 1.5);

        // Pill 分支同样应能在末端收敛
        var pillCr = MorphMath.CornerRadius(
            320, 380, MorphSpec.PillCornerRadius, MorphSpec.PanelCornerRadius,
            1.0, CornerBlendMode.FromPill, MorphSpec.BallSize);

        AssertClose("Pill→面板 末端圆角 = 面板圆角", pillCr, MorphSpec.PanelCornerRadius, 1.5);
    }

    // ─────────────────────────────────────────────────────────────
    private static void CheckContentOpacity()
    {
        Console.WriteLine("\n[3] 内容淡入时序");

        var o0 = MorphMath.ContentOpacity(true, 0.0, out var s0);
        var oMid = MorphMath.ContentOpacity(true, MorphSpec.ContentFadeStart + 0.001, out var sMid);
        var o1 = MorphMath.ContentOpacity(true, 1.0, out var s1);

        AssertClose("morph 起始时内容完全透明", o0, 0.0, 1e-6);
        AssertClose("淡入起点处仍为 0", oMid, 0.0, 1e-3);
        AssertClose("morph 结束时内容完全可见", o1, 1.0, 0.002);
        AssertClose("结束时滑入量归零", s1, 0.0, 0.01);
        AssertTrue("淡入过程有左滑位移", Math.Abs(s0) > 0.1);

        var fOut0 = MorphMath.ContentOpacity(false, 0.0, out _);
        var fOut1 = MorphMath.ContentOpacity(false, 1.0, out _);
        AssertClose("收起开始时内容仍可见", fOut0, 1.0, 1e-6);
        AssertClose("收起结束时内容已隐藏", fOut1, 0.0, 1e-6);
    }

    // ─────────────────────────────────────────────────────────────
    private static void CheckTrajectoryContinuity()
    {
        Console.WriteLine("\n[4] 展开轨迹连续性（球 56×56 → 面板 320×380）");

        const double fromW = MorphSpec.BallSize, fromH = MorphSpec.BallSize;
        const double toW = 320, toH = 380;
        const double fromCr = MorphSpec.BallCornerRadius, toCr = MorphSpec.PanelCornerRadius;

        var border = MorphSpec.Border;
        double prevW = fromW, prevCr = fromCr;
        var maxJumpW = 0.0;
        var maxJumpCr = 0.0;

        for (var i = 1; i <= 200; i++)
        {
            var raw = i / 200.0;
            var bt = SpringCurve.Clamp01(
                SpringCurve.Normalized(raw, border.Zeta, border.Omega, border.EndValue));

            var w = SpringCurve.Lerp(fromW, toW, bt);
            var h = SpringCurve.Lerp(fromH, toH, bt);
            var cr = MorphMath.CornerRadius(w, h, fromCr, toCr, bt, CornerBlendMode.FromBall, MorphSpec.BallSize);

            if (double.IsNaN(w) || double.IsNaN(h) || double.IsNaN(cr))
            {
                AssertTrue($"t={raw:0.000} 出现 NaN", false);
                return;
            }

            maxJumpW = Math.Max(maxJumpW, Math.Abs(w - prevW));
            maxJumpCr = Math.Max(maxJumpCr, Math.Abs(cr - prevCr));
            prevW = w;
            prevCr = cr;
        }

        // 65% 时长、200 次采样：单步宽高跳变不应超过总跨度的 6%
        AssertTrue($"宽度单步跳变 {maxJumpW:0.000} 合理（< 20px）", maxJumpW < 20.0);
        AssertTrue($"圆角单步跳变 {maxJumpCr:0.000} 合理（< 4px）", maxJumpCr < 4.0);

        Console.WriteLine($"    采样 200 步：最大宽高跳变 {maxJumpW:0.00}px，最大圆角跳变 {maxJumpCr:0.00}px");
    }

    // ─────────────────────────────────────────────────────────────
    private static double Peak(SpringSpec spec)
    {
        var peak = 0.0;
        for (var t = 0.0; t <= 1.0; t += 0.002)
        {
            var v = SpringCurve.Normalized(t, spec.Zeta, spec.Omega, spec.EndValue);
            if (v > peak) peak = v;
        }

        return peak;
    }

    private static void AssertClose(string name, double actual, double expected, double tolerance)
    {
        var ok = Math.Abs(actual - expected) <= tolerance;
        Report(name, ok, $"actual={actual:0.0000} expected={expected:0.0000} tol={tolerance}");
    }

    private static void AssertTrue(string name, bool condition)
    {
        Report(name, condition, condition ? "" : "condition failed");
    }

    private static void Report(string name, bool ok, string detail)
    {
        if (ok)
        {
            Console.WriteLine($"    PASS  {name}");
            return;
        }

        _failures++;
        Console.WriteLine($"    FAIL  {name}   {detail}");
    }
}
