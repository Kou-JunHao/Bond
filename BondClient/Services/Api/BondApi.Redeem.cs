using System.Text.Json.Serialization;
using System.Threading.Tasks;

namespace Bond.Services.Api;

/// <summary>
/// 兑换结果（<c>POST /api/redeem</c>，bond-transfer）。
/// <para>兑换码由管理后台批量生成（<c>POST /api/admin/redeem-codes</c>），形如 <c>BOND-XXXXXXXXXXXXXXXX</c>；
/// 一次性凭据：服务端以 <c>redeem_codes</c> 的原子状态更新 + <c>orders.provider_txn_id</c> 唯一约束双保险，
/// 兑换成功后档位、到期时间、订阅与配额同步刷新。</para>
/// </summary>
public class RedeemResultDto
{
    /// <summary>兑换后生效的档位（free / plus / team）。</summary>
    [JsonPropertyName("planCode")] public string? PlanCode { get; set; }

    /// <summary>
    /// 兑换后的档位到期时间。服务端是 <c>OffsetDateTime.toString()</c> 的 ISO-8601（如
    /// <c>2026-01-01T12:00:00+08:00</c>），这里按字符串接收再自行解析——解析失败只影响这一行展示，
    /// 不会让整条响应反序列化失败（否则会被上层误报成「无法连接服务器」）。
    /// </summary>
    [JsonPropertyName("expiresAt")] public string? ExpiresAt { get; set; }

    /// <summary>本次兑换累加的时长（天）。</summary>
    [JsonPropertyName("durationDays")] public int DurationDays { get; set; }
}

public sealed partial class BondApiClient
{
    /// <summary>
    /// 兑换码兑换（<c>POST /api/redeem</c>，body <c>{code}</c>）。
    /// <para>成功：data 为 <see cref="RedeemResultDto"/>；失败：后端返回 <c>code=400</c> +
    /// 「兑换码无效、已使用或已过期」等 message 原文，调用方直接展示，不要自造文案。</para>
    /// </summary>
    public Task<ApiResult<RedeemResultDto>?> RedeemAsync(string code)
        => PostAsync<RedeemResultDto>("/api/redeem", new { code });
}
