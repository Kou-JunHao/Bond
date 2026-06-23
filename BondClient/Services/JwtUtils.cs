using System.Text;
using System.Text.Json;

namespace BondClient.Services;

public static class JwtUtils
{
    public static bool IsTokenValid(string? token)
    {
        if (string.IsNullOrEmpty(token)) return false;
        try
        {
            var payload = DecodePayload(token);
            if (payload == null) return false;
            if (payload.TryGetValue("exp", out var expObj))
            {
                long exp = 0;
                if (expObj is JsonElement je) exp = je.GetInt64();
                else if (expObj is long l) exp = l;
                else long.TryParse(expObj.ToString(), out exp);
                return exp > DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            }
            return false;
        }
        catch { return false; }
    }

    public static long? GetUserId(string token)
    {
        try
        {
            var payload = DecodePayload(token);
            if (payload != null && payload.TryGetValue("sub", out var subObj))
            {
                if (subObj is JsonElement je) return long.Parse(je.GetString()!);
                return long.Parse(subObj.ToString()!);
            }
        }
        catch { }
        return null;
    }

    public static string? GetUsername(string token)
    {
        try
        {
            var payload = DecodePayload(token);
            if (payload != null && payload.TryGetValue("username", out var uObj))
            {
                if (uObj is JsonElement je) return je.GetString();
                return uObj.ToString();
            }
        }
        catch { }
        return null;
    }

    private static Dictionary<string, object>? DecodePayload(string token)
    {
        var parts = token.Split('.');
        if (parts.Length < 2) return null;
        var payload = parts[1];
        payload = payload.Replace('-', '+').Replace('_', '/');
        switch (payload.Length % 4)
        {
            case 2: payload += "=="; break;
            case 3: payload += "="; break;
        }
        var bytes = Convert.FromBase64String(payload);
        var json = Encoding.UTF8.GetString(bytes);
        return JsonSerializer.Deserialize<Dictionary<string, object>>(json);
    }
}
