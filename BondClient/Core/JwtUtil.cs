using System;
using System.Text;
using System.Text.Json;

namespace Bond.Core;

/// <summary>
/// 客户端侧 JWT 解析：只读 payload 里的 exp 与 sub，用于决定"是否需要重新登录"。
/// 注意：这里不验签（签名校验在服务端），仅作 UI 层的登录态判断。
/// </summary>
public static class JwtUtil
{
    public static bool TryGetPayload(string? token, out JsonElement payload)
    {
        payload = default;
        if (string.IsNullOrWhiteSpace(token)) return false;

        var parts = token.Split('.');
        if (parts.Length < 2) return false;

        try
        {
            var json = Base64UrlDecode(parts[1]);
            using var doc = JsonDocument.Parse(json);
            payload = doc.RootElement.Clone();
            return true;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>token 是否仍在有效期内（留 30 秒余量，避免边界抖动）。</summary>
    public static bool IsValid(string? token)
    {
        if (!TryGetPayload(token, out var payload)) return false;

        if (!payload.TryGetProperty("exp", out var exp) || exp.ValueKind != JsonValueKind.Number)
            return true; // 没有 exp 视为不过期，交由服务端判定

        var expiresAt = DateTimeOffset.FromUnixTimeSeconds(exp.GetInt64());
        return expiresAt > DateTimeOffset.UtcNow.AddSeconds(30);
    }

    /// <summary>从 token 里取 username（用于界面立即显示当前账号，无需等 /me 返回）。</summary>
    public static string? GetUsername(string? token)
    {
        if (!TryGetPayload(token, out var payload)) return null;
        if (!payload.TryGetProperty("username", out var name)) return null;
        var text = name.ValueKind == JsonValueKind.String ? name.GetString() : name.GetRawText();
        return string.IsNullOrWhiteSpace(text) ? null : text;
    }

    public static long? GetUserId(string? token)
    {
        if (!TryGetPayload(token, out var payload)) return null;
        if (!payload.TryGetProperty("sub", out var sub)) return null;

        var text = sub.ValueKind == JsonValueKind.Number ? sub.GetRawText() : sub.GetString();
        return long.TryParse(text, out var id) ? id : null;
    }

    private static string Base64UrlDecode(string value)
    {
        var s = value.Replace('-', '+').Replace('_', '/');
        s = (s.Length % 4) switch
        {
            2 => s + "==",
            3 => s + "=",
            _ => s
        };
        return Encoding.UTF8.GetString(Convert.FromBase64String(s));
    }
}
