using System;
using System.Net;
using System.Threading;
using System.Threading.Tasks;

namespace Bond.Services.Api;

/// <summary>
/// task-35：设备注册 + 心跳。
/// 单独拆一个 partial 文件：心跳需要拿到原始 HTTP 状态码（200 / 401 / 404 / 网络失败要分开处理），
/// 不能复用通用 SendAsync（它会把 401 变成 AuthExpired 并把响应吞成 null，
/// 心跳失败时不该把用户踢回登录页）。
/// </summary>
public sealed partial class BondApiClient
{
    /// <summary>
    /// 注册（或更新）本机设备行。服务端按 (user_id, device_uid) 做幂等 upsert，
    /// 同一台机器重复注册只会更新同一行。成功返回设备行，失败（含网络）返回 null。
    /// </summary>
    public async Task<DeviceDto?> RegisterDeviceAsync(string? deviceName, string? deviceType, string? deviceId)
    {
        var result = await PostAsync<DeviceDto>("/api/devices", new
        {
            deviceName = deviceName ?? "",
            deviceType = deviceType ?? "Desktop",
            deviceId = deviceId ?? ""
        }).ConfigureAwait(false);

        return result is { IsOk: true } ? result.Data : null;
    }

    /// <summary>
    /// 发一次心跳，返回原始 HTTP 状态码：200 续在线成功；401 token 刷新后仍失效；
    /// 404 设备行已不存在；-1 网络失败 / 超时。调用方据此决定「静默停」还是「下一轮继续」。
    /// 401 时自己先试一次静默刷新，但绝不触发 AuthExpired 事件。
    /// </summary>
    public async Task<int> HeartbeatAsync(string deviceId, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(deviceId)) return 404;

        var path = $"/api/devices/{deviceId.Trim()}/heartbeat";
        try
        {
            using var resp = await _http.PostAsync(path, null, ct).ConfigureAwait(false);
            if (resp.StatusCode != HttpStatusCode.Unauthorized) return (int)resp.StatusCode;

            // 先静默刷新一次 token 再重试；刷新失败就返回 401，由调用方静默停
            if (!await TryRefreshAsync().ConfigureAwait(false)) return 401;

            using var retry = await _http.PostAsync(path, null, ct).ConfigureAwait(false);
            return (int)retry.StatusCode;
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            return -1;
        }
        catch (Exception)
        {
            return -1;
        }
    }
}
