using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Threading;
using Bond.Core;
using Bond.Models;
using Bond.ViewModels;

namespace Bond.Services;

/// <summary>
/// task-35：本机设备注册 + 心跳（真实在线状态的数据来源）。
/// 服务端按 (user_id, device_uid) 维护唯一设备行：注册只是 upsert 同一行，
/// 心跳每 60s 把 lastSeenAt 前移，服务端 2 分钟收不到心跳就把设备标记离线。
/// 因此设备页的在线/离线一律读服务端 isOnline，本地不做时间推算。
/// </summary>
public sealed partial class HttpBondStore
{
    /// <summary>心跳间隔：服务端 2 分钟判定离线，60s 留一次失败容错。</summary>
    private static readonly TimeSpan HeartbeatInterval = TimeSpan.FromSeconds(60);

    /// <summary>刚心跳成功过就别每次切窗口都打接口。</summary>
    private static readonly TimeSpan HeartbeatFreshWindow = TimeSpan.FromSeconds(30);

    private readonly SemaphoreSlim _presenceGate = new(1, 1);
    private readonly CancellationTokenSource _presenceCts = new();

    private int _heartbeatRunning;
    private bool _heartbeatAuthFailed;
    private DateTime _lastHeartbeatOkUtc = DateTime.MinValue;

    /// <summary>本机在服务端的设备行 id；未注册 / 已被移除时为 null。</summary>
    public string? ServerDeviceId
    {
        get
        {
            var id = LocalPrefs.Current.ServerDeviceId;
            return string.IsNullOrWhiteSpace(id) ? null : id.Trim();
        }
    }

    // ── 注册 ──────────────────────────────────────────────────

    /// <summary>登录会话建立后调用：确保本机已登记（已有则跳过），然后启动心跳。</summary>
    private async Task EnsureDevicePresenceAsync()
    {
        if (await EnsureDeviceRegisteredAsync().ConfigureAwait(false))
            StartHeartbeat();
    }

    /// <summary>
    /// 没有本机登记行（或登记行属于别的账号）时才 POST /api/devices。
    /// 已有登记直接跳过——重启不新增设备行是硬验收。
    /// </summary>
    private async Task<bool> EnsureDeviceRegisteredAsync()
    {
        if (!_api.IsLoggedIn) return false;

        var userId = JwtUtil.GetUserId(_api.AccessToken);
        if (IsRegisteredFor(userId)) return true;

        // 启动流程与前台激活可能同时触发注册，串行化避免两次 POST
        await _presenceGate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (IsRegisteredFor(userId)) return true;

            var prefs = LocalPrefs.Current;
            var dto = await _api.RegisterDeviceAsync(DeviceName, "Desktop", prefs.DeviceId)
                .ConfigureAwait(false);
            if (dto is null) return false;

            prefs.ServerDeviceId = dto.Id.ToString(System.Globalization.CultureInfo.InvariantCulture);
            prefs.ServerDeviceUserId = userId ?? 0;
            prefs.Save();
            return true;
        }
        finally
        {
            _presenceGate.Release();
        }
    }

    private static bool IsRegisteredFor(long? userId)
    {
        var prefs = LocalPrefs.Current;
        if (string.IsNullOrWhiteSpace(prefs.ServerDeviceId)) return false;

        // token 里没有 uid 拆不出来时无法校验归属，沿用现有登记（避免每次启动都白打一次 POST）
        return userId is not > 0 || prefs.ServerDeviceUserId == userId;
    }

    // ── 心跳 ──────────────────────────────────────────────────

    /// <summary>启动 60s 心跳循环（幂等：已有循环在跑就直接返回）。</summary>
    private void StartHeartbeat()
    {
        if (Interlocked.CompareExchange(ref _heartbeatRunning, 1, 0) != 0) return;

        _ = Task.Run(async () =>
        {
            try
            {
                using var timer = new PeriodicTimer(HeartbeatInterval);
                while (await timer.WaitForNextTickAsync(_presenceCts.Token).ConfigureAwait(false))
                {
                    if (!await SendHeartbeatOnceAsync(_presenceCts.Token).ConfigureAwait(false)) break;
                }
            }
            catch (OperationCanceledException)
            {
                // 应用退出，正常收尾
            }
            finally
            {
                Interlocked.Exchange(ref _heartbeatRunning, 0);
            }
        });
    }

    /// <summary>
    /// 一次心跳。<returns>false</returns> 表示循环该停下（鉴权失效 / 设备行已不存在）；
    /// 网络抖动或 5xx 返回 true，静默等下一轮（不弹框、不写错误条）。
    /// </summary>
    private async Task<bool> SendHeartbeatOnceAsync(CancellationToken ct)
    {
        var rowId = ServerDeviceId;
        if (rowId is null) return false;

        var status = await _api.HeartbeatAsync(rowId, ct).ConfigureAwait(false);
        switch (status)
        {
            case 200:
                _heartbeatAuthFailed = false;
                _lastHeartbeatOkUtc = DateTime.UtcNow;
                return true;

            case 401:
                // 刷新后仍 401：静默停，等下次前台激活再试
                _heartbeatAuthFailed = true;
                return false;

            case 404:
                // 本机行在别处被移除（或换了账号）：清掉本地登记，下次前台激活重新注册
                ClearDeviceRegistration();
                return false;

            default:
                return true;
        }
    }

    private static void ClearDeviceRegistration()
    {
        var prefs = LocalPrefs.Current;
        prefs.ServerDeviceId = "";
        prefs.ServerDeviceUserId = 0;
        prefs.Save();
    }

    /// <summary>
    /// 主窗口回到前台时调用：心跳曾因 401 静默停、或本机行被移除时，借这次激活恢复。
    /// </summary>
    public void OnAppActivated()
    {
        if (!_api.IsLoggedIn) return;
        _ = Task.Run(ResumePresenceAsync);
    }

    private async Task ResumePresenceAsync()
    {
        if (!_api.IsLoggedIn) return;

        if (ServerDeviceId is null && !await EnsureDeviceRegisteredAsync().ConfigureAwait(false))
            return;

        var fresh = _lastHeartbeatOkUtc != DateTime.MinValue
                    && DateTime.UtcNow - _lastHeartbeatOkUtc < HeartbeatFreshWindow;
        if (fresh && Volatile.Read(ref _heartbeatRunning) == 1) return;

        await SendHeartbeatOnceAsync(CancellationToken.None).ConfigureAwait(false);

        if (ServerDeviceId is not null && !_heartbeatAuthFailed)
            StartHeartbeat();
    }

    // ── 设备集合同步 ──────────────────────────────────────────

    /// <summary>
    /// 按索引就地同步 <see cref="Peers"/>（Replace / RemoveAt / Add），不再 Clear+Add。
    /// 原因：Avalonia 12 的 ItemsControl 处理 Reset 通知会留下旧容器，设备页刷新/移除后
    /// 会出现重复卡片；逐项变更不会触发 Reset。集合只在 UI 线程改动。
    /// </summary>
    private async Task SyncPeersAsync(List<DevicePeer> fresh)
    {
        await Dispatcher.UIThread.InvokeAsync(() =>
        {
            var keep = Math.Min(_peers.Count, fresh.Count);
            for (var i = 0; i < keep; i++)
            {
                if (!SamePeer(_peers[i], fresh[i])) _peers[i] = fresh[i];
            }

            while (_peers.Count > fresh.Count) _peers.RemoveAt(_peers.Count - 1);
            for (var i = keep; i < fresh.Count; i++) _peers.Add(fresh[i]);
        });
    }

    private static bool SamePeer(DevicePeer a, DevicePeer b) =>
        a.Id == b.Id
        && a.Name == b.Name
        && a.IsOnline == b.IsOnline
        && a.IsCurrentDevice == b.IsCurrentDevice
        && a.LastSeenAt == b.LastSeenAt
        && a.Ip == b.Ip
        && a.Platform == b.Platform;

    // ── 移除设备 ──────────────────────────────────────────────

    /// <summary>移除一台设备；成功返回 null，失败返回可展示原因。本机行不允许移除。</summary>
    public async Task<string?> RemoveAsync(DevicePeer peer)
    {
        if (peer is null) return "设备不存在";
        if (peer.IsCurrentDevice || string.Equals(peer.Id, ServerDeviceId, StringComparison.Ordinal))
            return "当前设备不能移除";

        var result = await _api.DeleteAsync($"/api/devices/{peer.Id}").ConfigureAwait(false);
        if (result is null)
            return LoadRetry.Humanize(_api.LastError, "无法连接服务器，请检查网络后重试");

        if (result.Code is not (200 or 204))
            return string.IsNullOrWhiteSpace(result.Message) ? $"移除失败（HTTP {result.Code}）" : result.Message;

        await RefreshDevicesAsync().ConfigureAwait(false);
        Changed?.Invoke(this, EventArgs.Empty);
        return null;
    }
}
