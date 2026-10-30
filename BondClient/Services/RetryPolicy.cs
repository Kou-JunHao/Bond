using System;

namespace Bond.Services;

/// <summary>
/// task-151：客户端的「重试语义」唯一口径。
/// <para>此前退避参数散落在 <see cref="LoadRetry"/>、<c>ChatSocket</c>、分片上传、401 刷新与后台轮询里，
/// 文案与「是否给用户重试入口」也各写各的。这里把场景、次数、退避、是否自动、是否用户可见集中成一张表，
/// 调用点只引用本表，避免以后再出现第二套参数。</para>
/// <para>行为与改造前等价（参数逐项照抄原实现），只做集中与命名，不改变实际退避。</para>
/// </summary>
public enum RetryScenario
{
    /// <summary>页面首次加载：骨架屏期间静默自动重试，失败后进入可重试错误态。</summary>
    FirstLoad,

    /// <summary>用户显式操作（发送消息 / 上传 / 下载 / 保存等）：不自动重试，失败后由用户点「重试」。</summary>
    UserAction,

    /// <summary>后台轮询（实时通道断开时的消息轮询、传输进度等）：不单独重试，下一周期自然重试。</summary>
    BackgroundPoll,

    /// <summary>大文件分片上传：片内自动重试，用户只看到最终失败。</summary>
    ChunkUpload,

    /// <summary>401 刷新令牌：同一请求内刷新后重放一次，不向用户暴露。</summary>
    TokenRefresh,

    /// <summary>实时通道（WebSocket）断线重连：无限次自动重连，状态条对用户可见。</summary>
    SocketReconnect
}

/// <summary>单个场景的重试规则。延迟数组长度 = 最大自动重试次数。</summary>
public sealed record RetryRule(
    RetryScenario Scenario,
    string DisplayName,
    int MaxAttempts,
    TimeSpan[] Delays,
    bool IsAutomatic,
    bool IsUserVisible);

/// <summary>task-151：场景 → 次数/退避/是否自动/是否用户可见。</summary>
public static class RetryPolicy
{
    /// <summary>后台轮询周期（聊天实时通道断开时的 REST 兜底轮询）。</summary>
    public static readonly TimeSpan BackgroundPollInterval = TimeSpan.FromSeconds(5);

    /// <summary>实时通道心跳间隔。</summary>
    public static readonly TimeSpan SocketPingInterval = TimeSpan.FromSeconds(30);

    /// <summary>实时通道判死阈值：超过该时长没有任何入站帧即视为断线。</summary>
    public static readonly TimeSpan SocketIdleTimeout = TimeSpan.FromSeconds(60);

    /// <summary>实时通道连接超时。</summary>
    public static readonly TimeSpan SocketConnectTimeout = TimeSpan.FromSeconds(15);

    private static readonly RetryRule[] Rules =
    [
        new(RetryScenario.FirstLoad, "首次加载",
            MaxAttempts: 4, // 首次 + 3 次自动重试
            Delays: [TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(4), TimeSpan.FromSeconds(8)],
            IsAutomatic: true,
            IsUserVisible: true),

        new(RetryScenario.UserAction, "用户显式操作",
            MaxAttempts: 1,
            Delays: [],
            IsAutomatic: false,
            IsUserVisible: true),

        new(RetryScenario.BackgroundPoll, "后台轮询",
            MaxAttempts: 1,
            Delays: [],
            IsAutomatic: false,
            IsUserVisible: false),

        new(RetryScenario.ChunkUpload, "分片上传",
            MaxAttempts: 4,
            Delays:
            [
                TimeSpan.FromMilliseconds(400),
                TimeSpan.FromMilliseconds(800),
                TimeSpan.FromMilliseconds(1600)
            ],
            IsAutomatic: true,
            IsUserVisible: false),

        new(RetryScenario.TokenRefresh, "401 刷新",
            MaxAttempts: 2, // 原请求 + 刷新后重放一次
            Delays: [],
            IsAutomatic: true,
            IsUserVisible: false),

        new(RetryScenario.SocketReconnect, "实时通道重连",
            MaxAttempts: int.MaxValue, // 无限重连，靠指数退避封顶 30s
            Delays:
            [
                TimeSpan.FromSeconds(1),
                TimeSpan.FromSeconds(2),
                TimeSpan.FromSeconds(4),
                TimeSpan.FromSeconds(8),
                TimeSpan.FromSeconds(15),
                TimeSpan.FromSeconds(30)
            ],
            IsAutomatic: true,
            IsUserVisible: true)
    ];

    public static RetryRule For(RetryScenario scenario) => Rules[(int)scenario];

    /// <summary>
    /// 第 <paramref name="attemptIndex"/> 次自动重试前的等待（0 表示第一次重试）。
    /// 超出延迟表长度时取最后一档（指数退避封顶）。
    /// </summary>
    public static TimeSpan DelayFor(RetryScenario scenario, int attemptIndex)
    {
        var delays = For(scenario).Delays;
        if (delays.Length == 0 || attemptIndex < 0) return TimeSpan.Zero;
        return delays[Math.Min(attemptIndex, delays.Length - 1)];
    }

    public static int MaxAttempts(RetryScenario scenario) => For(scenario).MaxAttempts;

    public static bool IsAutomatic(RetryScenario scenario) => For(scenario).IsAutomatic;

    /// <summary>该场景失败后是否需要给用户一个「重试」入口。</summary>
    public static bool NeedsRetryEntry(RetryScenario scenario) => For(scenario).IsUserVisible;
}
