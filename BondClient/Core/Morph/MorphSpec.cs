namespace Bond.Core.Morph;

/// <summary>单个弹簧的两个参数：阻尼比 ζ 与角频率 ω。</summary>
public readonly record struct SpringSpec(double Zeta, double Omega)
{
    public double EndValue => SpringCurve.EndValue(Zeta, Omega);
}

/// <summary>
/// 形变动画的全部"魔法数字"。
/// <para>
/// 这些值来自 <c>BondClient/Views/FloatingBallWindow.axaml.cs</c> 的实测调参结果，
/// 逐条搬迁过来。改任何一个都会改变手感，改动前请先跑 <c>MorphCheck</c> 看曲线。
/// </para>
/// </summary>
public static class MorphSpec
{
    // ── 弹簧参数 ──────────────────────────────────────────────
    /// <summary>主体宽高：几乎临界阻尼，末端只有极轻微的回弹。</summary>
    public static readonly SpringSpec Border = new(0.92, 4.5);

    /// <summary>图标位移/缩放：比主体略"活"，形成层次感。</summary>
    public static readonly SpringSpec Icon = new(0.85, 5.5);

    /// <summary>Pill 宽度方向：更软，产生挤压感。</summary>
    public static readonly SpringSpec PillWidthSpring = new(0.78, 5.0);

    /// <summary>Pill 高度方向：与宽度方向错开，形成 squash &amp; stretch。</summary>
    public static readonly SpringSpec PillHeightSpring = new(0.82, 4.2);

    /// <summary>窗口中心点位移：独立弹簧，避免与尺寸变化同频。</summary>
    public static readonly SpringSpec Position = new(0.85, 5.0);

    /// <summary>内容淡入：偏软，让文字在形变末期"浮"出来。</summary>
    public static readonly SpringSpec Content = new(0.70, 6.0);

    /// <summary>悬停放大：高频、快速收尾。</summary>
    public static readonly SpringSpec Hover = new(0.60, 12.0);

    /// <summary>Pill 挤压/回弹：低阻尼，产生明显的 squash &amp; stretch。</summary>
    public static readonly SpringSpec PillSquash = new(0.32, 5.5);

    // ── 时长（秒）──────────────────────────────────────────────
    public const double MorphDuration = 0.65;
    public const double PillDuration = 0.60;
    public const double HoverDuration = 0.30;
    public const double ResizeDuration = 0.42;
    public const int SnapSlideDurationMs = 300;

    // ── 版本 1 尺寸（DIP）──────────────────────────────────────
    public const double BallSize = 56.0;
    public const double BallPadding = 20.0;
    public const double BallCornerRadius = 28.0;
    public const double PanelCornerRadius = 16.0;
    public const double PillWidth = 14.0;
    public const double PillHeight = 72.0;
    public const double PillCornerRadius = 7.0;
    public const double IconSize = 32.0;
    public const double IconTargetSize = 18.0;

    /// <summary>球内图标的基座中心（美术对齐：Canvas.Left/Top = 13，尺寸 28）。</summary>
    public const double IconBaseCenter = 28.0;   // 球心：Canvas.Left=12 + 32/2

    /// <summary>展开面板头部图标的落点中心。</summary>
    public const double IconTargetCenterX = 16.0 + IconTargetSize / 2.0;
    public const double IconTargetCenterY = 28.0;

    // ── 交互阈值（DIP）────────────────────────────────────────
    public const double DragThreshold = 5.0;
    public const double EdgeSnapThreshold = 30.0;
    public const int DoubleClickMs = 350;

    // ── 内容淡入时序 ──────────────────────────────────────────
    /// <summary>morph 进度到这个比例之前，内容保持完全透明。</summary>
    public const double ContentFadeStart = 0.40;

    /// <summary>反向（收起）时，内容在此比例内淡出完毕。</summary>
    public const double ContentFadeOutEnd = 0.25;

    /// <summary>淡入时的水平滑入距离（DIP，向左）。</summary>
    public const double ContentSlide = 8.0;

    /// <summary>位置插值的加速倍率（位置比尺寸更早到位）。</summary>
    public const double PositionLeadFactor = 1.20;

    /// <summary>Pill 分支的位置加速倍率。</summary>
    public const double PillPositionLeadFactor = 1.15;
}
