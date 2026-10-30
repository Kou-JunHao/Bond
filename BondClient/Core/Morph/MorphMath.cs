using System;

namespace Bond.Core.Morph;

/// <summary>圆角插值的两个分支：从球体出发，或从 Pill 出发。</summary>
public enum CornerBlendMode
{
    /// <summary>球 ↔ 面板：按 minDim / 球径 判断"脱离圆形"的程度。</summary>
    FromBall,

    /// <summary>Pill ↔ 面板：按绝对尺寸判断。</summary>
    FromPill
}

/// <summary>
/// 形变动画的纯几何计算。<b>没有任何 UI 依赖</b>，因此可以被单元测试与
/// <c>MorphCheck</c> 工具直接验证。
/// </summary>
public static class MorphMath
{
    /// <summary>
    /// 形变过程中的圆角。
    /// <para>
    /// 这是整个动画的"视觉灵魂"：圆角并不简单地线性插值，而是把
    /// <b>「正圆半径 = minDim / 2」</b> 与 <b>「目标圆角直线插值」</b> 按
    /// <c>SmoothStep</c> 混合。因此球体变大时会先保持接近全圆，
    /// 到一定尺寸后才平滑地"变方"，肉眼看上去像是被四角缓缓拉平。
    /// </para>
    /// </summary>
    public static double CornerRadius(
        double width,
        double height,
        double fromCornerRadius,
        double toCornerRadius,
        double morphProgress,
        CornerBlendMode mode,
        double referenceSize)
    {
        var minDim = Math.Min(width, height);
        var circular = minDim / 2.0;
        var linear = SpringCurve.Lerp(fromCornerRadius, toCornerRadius, morphProgress);

        var blend = mode == CornerBlendMode.FromBall
            ? SpringCurve.Clamp01((minDim / referenceSize - 1.0) / 3.0)
            : SpringCurve.Clamp01((minDim - 20.0) / 60.0);

        return circular + (linear - circular) * SpringCurve.SmoothStep(blend);
    }

    /// <summary>窗口左上角 → 窗口中心（物理像素）。</summary>
    public static (int Cx, int Cy) CenterFromTopLeft(int left, int top, int windowW, int windowH)
        => (left + windowW / 2, top + windowH / 2);

    /// <summary>窗口中心 → 窗口左上角（物理像素）。</summary>
    public static (int Left, int Top) TopLeftFromCenter(int centerX, int centerY, int windowW, int windowH)
        => (centerX - windowW / 2, centerY - windowH / 2);

    public static int DipToPx(double dip, double dpi) => (int)Math.Round(dip * dpi);

    /// <summary>
    /// 面板内容的透明度与水平滑入量。
    /// <para>
    /// 展开时内容在 morph 进度 40% 之后才开始出现（弹簧淡入 + 8px 左滑）；
    /// 收起时则在前 25% 内快速淡出，避免和缩小的窗口互相干扰。
    /// </para>
    /// </summary>
    public static double ContentOpacity(
        bool fadingIn,
        double rawProgress,
        out double slideX)
    {
        if (fadingIn)
        {
            var contentRaw = rawProgress < MorphSpec.ContentFadeStart
                ? 0.0
                : SpringCurve.Clamp01((rawProgress - MorphSpec.ContentFadeStart) /
                                      (1.0 - MorphSpec.ContentFadeStart));

            var spec = MorphSpec.Content;
            var t = SpringCurve.Clamp01(
                SpringCurve.Normalized(contentRaw, spec.Zeta, spec.Omega, spec.EndValue));

            slideX = (1.0 - t) * -MorphSpec.ContentSlide;
            return t;
        }
        else
        {
            var t = rawProgress < MorphSpec.ContentFadeOutEnd
                ? 1.0 - SpringCurve.EaseOutCubic(rawProgress / MorphSpec.ContentFadeOutEnd)
                : 0.0;

            slideX = (1.0 - t) * -MorphSpec.ContentSlide;
            return t;
        }
    }

    /// <summary>球体状态下图标相对面板头部的落点偏移。</summary>
    public static (double Tx, double Ty) IconTargetOffset()
    {
        var tx = MorphSpec.IconTargetCenterX - MorphSpec.IconBaseCenter;
        var ty = MorphSpec.IconTargetCenterY - MorphSpec.IconBaseCenter;
        return (tx, ty);
    }

    /// <summary>图标缩放：从球内 28px 到面板头部 18px。</summary>
    public static double IconTargetScale => MorphSpec.IconTargetSize / MorphSpec.IconSize;
}
