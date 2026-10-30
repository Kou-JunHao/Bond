using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Threading;

namespace Bond.Services.Lan;

/// <summary>本机在局域网里公开的身份（每次广播时实时读取，账号/设备名变化能立刻生效）。</summary>
public sealed record LanIdentity(
    string DeviceId,
    string DeviceName,
    long UserId,
    string Nickname,
    int TransferPort);

/// <summary>一台在局域网里被发现的设备。</summary>
public sealed record LanDevice(
    string DeviceId,
    string DeviceName,
    string Nickname,
    long UserId,
    string Ip,
    int TransferPort,
    string Version,
    DateTimeOffset LastSeenUtc)
{
    public string Initial => string.IsNullOrEmpty(DeviceName) ? "?" : DeviceName[..1].ToUpperInvariant();

    /// <summary>距离上次心跳的秒数（界面显示「最近心跳 Ns 前」用）。</summary>
    public int AgeSeconds => Math.Max(0, (int)(DateTimeOffset.UtcNow - LastSeenUtc).TotalSeconds);
}

/// <summary>
/// task-160 A：局域网设备发现（UDP 组播 + 广播，多网卡）。
/// <para>
/// 语义与旧版 BondClient/Services/DiscoveryService.cs 一致：每块启用中的 IPv4 网卡各开一个
/// UDP socket 加入组播组 224.0.0.167，按固定端口收发；报文里带 deviceId / deviceName /
/// userId / nickname / endpoint(port) / version / ts。只认「同一账号」的设备（好友授权放 B 之后）。
/// </para>
/// <para>
/// 约束：不写任何日志文件、不动磁盘；无网卡/端口占用/防火墙拦截一律静默降级，绝不弹错。
/// </para>
/// </summary>
public sealed class LanDiscoveryService : IDisposable
{
    /// <summary>发现通道固定端口（可在 prefs.json 用 LanDiscoveryPort 覆盖）。</summary>
    public const int DefaultDiscoveryPort = 45231;

    /// <summary>局域网直连传输端口（B 阶段使用；A 阶段随报文发布，便于对方提前知道通道）。</summary>
    public const int DefaultTransferPort = 45232;

    private const string Protocol = "bond-lan-1";

    private static readonly IPAddress MulticastGroup = IPAddress.Parse("224.0.0.167");

    // 刚启动的几秒发得勤一点，之后保持 2s 一次，12s 收不到视为离线。
    private static readonly TimeSpan AnnounceFast = TimeSpan.FromMilliseconds(700);
    private static readonly TimeSpan AnnounceNormal = TimeSpan.FromSeconds(2);
    private static readonly TimeSpan FastPhase = TimeSpan.FromSeconds(6);
    private static readonly TimeSpan DeviceTimeout = TimeSpan.FromSeconds(12);

    /// <summary>报文上限：只接受小包，避免被大包/扫描打爆。</summary>
    private const int MaxPacketBytes = 4096;

    private readonly Func<LanIdentity?> _identity;
    private readonly object _gate = new();
    private readonly Dictionary<string, LanDevice> _devices = new(StringComparer.Ordinal);
    private readonly List<UdpClient> _sockets = new();

    private CancellationTokenSource? _cts;
    private Task? _receiveTask;
    private Task? _announceTask;
    private Task? _cleanupTask;
    private int _discoveryPort = DefaultDiscoveryPort;
    private bool _running;

    public LanDiscoveryService(Func<LanIdentity?> identity) => _identity = identity;

    /// <summary>发现是否在跑（设置开关关闭 / 未登录时为 false）。</summary>
    public bool IsRunning => _running;

    /// <summary>设备集合/在线状态发生变化（UI 线程触发）。</summary>
    public event EventHandler? Changed;

    /// <summary>当前在线的局域网设备快照（已过滤本机、过期设备与其它账号）。</summary>
    public IReadOnlyList<LanDevice> Devices
    {
        get
        {
            var now = DateTimeOffset.UtcNow;
            lock (_gate)
            {
                // 只过滤、不在这里删除：删除动作交给 CleanupLoop 负责并发 Changed，
                // 若 getter 抢先删掉过期项，CleanupLoop 就看不到变化、界面也就收不到通知（task-160 修）。
                return _devices.Values
                    .Where(d => now - d.LastSeenUtc <= DeviceTimeout)
                    .OrderBy(d => d.DeviceName, StringComparer.CurrentCultureIgnoreCase)
                    .ToList();
            }
        }
    }

    // ── 生命周期 ─────────────────────────────────────────────

    /// <summary>开始监听与广播（幂等；重复调用不会重复开 socket）。</summary>
    public void Start()
    {
        if (_running) return;

        _discoveryPort = ResolveDiscoveryPort();
        _cts = new CancellationTokenSource();
        SetupSockets();
        _running = true;

        var token = _cts.Token;
        _receiveTask = Task.Run(() => ReceiveLoopAsync(token), token);
        _announceTask = Task.Run(() => AnnounceLoopAsync(token), token);
        _cleanupTask = Task.Run(() => CleanupLoopAsync(token), token);

        RaiseChanged();
    }

    /// <summary>停止监听与广播，并清空已发现设备（关开关 12s 内双方消失的语义由这里保证）。</summary>
    public void Stop()
    {
        if (!_running) return;
        _running = false;

        try { _cts?.Cancel(); } catch { /* 已释放 */ }

        lock (_gate)
        {
            foreach (var socket in _sockets)
            {
                try { socket.Dispose(); } catch { /* 已关闭 */ }
            }
            _sockets.Clear();
            _devices.Clear();
        }

        _cts?.Dispose();
        _cts = null;
        _receiveTask = null;
        _announceTask = null;
        _cleanupTask = null;

        RaiseChanged();
    }

    /// <summary>立即广播一次（设备页「刷新」或打开悬浮球时用，不等下一个 2s 周期）。</summary>
    public void AnnounceNow() => _ = SendAnnouncementAsync();

    public void Dispose() => Stop();

    // ── socket 建立（每网卡一个，失败静默跳过）─────────────────

    private void SetupSockets()
    {
        foreach (var iface in EnumerateInterfaces())
        {
            var address = GetIPv4Address(iface);
            if (address is null) continue;

            try
            {
                // 同一端口多实例：绑定 Any + ReuseAddress，组播报文会分发给所有已加入的 socket。
                var socket = new UdpClient(AddressFamily.InterNetwork);
                socket.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                socket.Client.Bind(new IPEndPoint(IPAddress.Any, _discoveryPort));
                socket.JoinMulticastGroup(MulticastGroup, address);
                socket.EnableBroadcast = true;
                socket.Client.SetSocketOption(
                    SocketOptionLevel.IP, SocketOptionName.MulticastLoopback, true);

                lock (_gate) { _sockets.Add(socket); }
            }
            catch
            {
                // 端口占用 / 网卡不支持组播 / 防火墙：静默降级，只是这台机器发现不到别人。
            }
        }

        // 一块网卡都没建成时的兜底：至少开一个 socket 尝试广播（仍可能被组播接收端收到）。
        if (_sockets.Count == 0)
        {
            try
            {
                var socket = new UdpClient(AddressFamily.InterNetwork);
                socket.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                socket.Client.Bind(new IPEndPoint(IPAddress.Any, _discoveryPort));
                socket.EnableBroadcast = true;
                lock (_gate) { _sockets.Add(socket); }
            }
            catch
            {
                // 完全没有网络能力：保持静默，界面显示空列表即可。
            }
        }
    }

    private static IEnumerable<NetworkInterface> EnumerateInterfaces()
    {
        NetworkInterface[] all;
        try { all = NetworkInterface.GetAllNetworkInterfaces(); }
        catch { return []; }

        return all.Where(i => i.OperationalStatus == OperationalStatus.Up)
                  .Where(i => i.NetworkInterfaceType is not NetworkInterfaceType.Loopback
                                                        and not NetworkInterfaceType.Tunnel);
    }

    private static IPAddress? GetIPv4Address(NetworkInterface iface)
    {
        try
        {
            return iface.GetIPProperties().UnicastAddresses
                .FirstOrDefault(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
                ?.Address;
        }
        catch
        {
            return null;
        }
    }

    private static int ResolveDiscoveryPort()
    {
        var port = Bond.ViewModels.LocalPrefs.Current.LanDiscoveryPort;
        return port is > 0 and < 65535 ? port : DefaultDiscoveryPort;
    }

    // ── 接收 ─────────────────────────────────────────────────

    private async Task ReceiveLoopAsync(CancellationToken ct)
    {
        UdpClient[] sockets;
        lock (_gate) { sockets = [.. _sockets]; }

        var loops = sockets.Select(socket => Task.Run(async () =>
        {
            while (!ct.IsCancellationRequested)
            {
                try
                {
                    var result = await socket.ReceiveAsync(ct).ConfigureAwait(false);
                    if (result.Buffer.Length is <= 0 or > MaxPacketBytes) continue;
                    HandleDatagram(result);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch
                {
                    // 单个 socket 出错（网卡断开等）不拖垮其它网卡；等下一次心跳自然恢复。
                    try { await Task.Delay(500, ct).ConfigureAwait(false); }
                    catch (OperationCanceledException) { break; }
                }
            }
        }, ct)).ToList();

        try { await Task.WhenAll(loops).ConfigureAwait(false); }
        catch (OperationCanceledException) { /* 退出 */ }
    }

    private void HandleDatagram(UdpReceiveResult result)
    {
        try
        {
            var payload = JsonSerializer.Deserialize<Announcement>(result.Buffer);
            if (payload is null) return;
            if (!string.Equals(payload.Version, Protocol, StringComparison.Ordinal)) return;
            if (string.IsNullOrWhiteSpace(payload.DeviceId)) return;

            var identity = _identity();
            if (identity is null) return;
            if (string.Equals(payload.DeviceId, identity.DeviceId, StringComparison.Ordinal)) return;

            // task-160 A：只发现同一账号的设备；好友设备需要服务端授权信息，放 B 之后另谈。
            if (identity.UserId > 0 && payload.UserId != identity.UserId) return;

            var remote = result.RemoteEndPoint;
            var device = new LanDevice(
                payload.DeviceId,
                string.IsNullOrWhiteSpace(payload.DeviceName) ? "未命名设备" : payload.DeviceName,
                payload.Nickname ?? string.Empty,
                payload.UserId,
                remote.Address.ToString(),
                payload.TransferPort > 0 ? payload.TransferPort : DefaultTransferPort,
                payload.Version,
                DateTimeOffset.UtcNow);

            var changed = false;
            lock (_gate)
            {
                // IP 可能随报文落到的网卡变化（多网卡机器上会来回跳）；只静默更新，不触发界面刷新，
                // 否则设备页/悬浮球会被「同一条设备换 IP」刷出十几次事件。
                changed = !_devices.TryGetValue(device.DeviceId, out var existing)
                          || existing.DeviceName != device.DeviceName
                          || existing.Nickname != device.Nickname;
                _devices[device.DeviceId] = device;
            }

            if (changed)
            {
                RaiseChanged();
            }
        }
        catch
        {
            // 非 JSON / 别人的报文 / 扫描包：忽略。
        }
    }

    // ── 广播 ─────────────────────────────────────────────────

    private async Task AnnounceLoopAsync(CancellationToken ct)
    {
        var started = DateTimeOffset.UtcNow;
        while (!ct.IsCancellationRequested)
        {
            try { await SendAnnouncementAsync().ConfigureAwait(false); } catch { /* 静默 */ }

            var fast = DateTimeOffset.UtcNow - started < FastPhase;
            try
            {
                await Task.Delay(fast ? AnnounceFast : AnnounceNormal, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private async Task SendAnnouncementAsync()
    {
        var identity = _identity();
        if (identity is null) return;

        var buffer = JsonSerializer.SerializeToUtf8Bytes(new Announcement(
            Protocol,
            identity.DeviceId,
            identity.DeviceName,
            identity.UserId,
            identity.Nickname,
            identity.TransferPort > 0 ? identity.TransferPort : DefaultTransferPort,
            DateTimeOffset.UtcNow.ToUnixTimeSeconds()));

        UdpClient[] sockets;
        lock (_gate) { sockets = [.. _sockets]; }

        foreach (var socket in sockets)
        {
            try
            {
                await socket.SendAsync(buffer, new IPEndPoint(MulticastGroup, _discoveryPort)).ConfigureAwait(false);
            }
            catch { /* 静默 */ }

            try
            {
                await socket.SendAsync(buffer, new IPEndPoint(IPAddress.Broadcast, _discoveryPort)).ConfigureAwait(false);
            }
            catch { /* 静默 */ }
        }

        // 组播/广播都被拦截的极端情况下用统一广播地址再试一次（很多交换机允许 255.255.255.255）。
        try
        {
            using var fallback = new UdpClient { EnableBroadcast = true };
            await fallback.SendAsync(buffer, new IPEndPoint(IPAddress.Broadcast, _discoveryPort)).ConfigureAwait(false);
        }
        catch { /* 静默 */ }
    }

    // ── 过期清理 ─────────────────────────────────────────────

    private async Task CleanupLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(1), ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }

            var changed = false;
            lock (_gate) { changed = PruneLocked(DateTimeOffset.UtcNow); }
            if (changed) RaiseChanged();
        }
    }

    private bool PruneLocked(DateTimeOffset now)
    {
        var cutoff = now - DeviceTimeout;
        var expired = _devices.Values.Where(d => d.LastSeenUtc < cutoff).Select(d => d.DeviceId).ToList();
        foreach (var id in expired) _devices.Remove(id);
        return expired.Count > 0;
    }

    private void RaiseChanged()
    {
        var handler = Changed;
        if (handler is null) return;

        try
        {
            if (Dispatcher.UIThread.CheckAccess()) handler(this, EventArgs.Empty);
            else Dispatcher.UIThread.Post(() => Changed?.Invoke(this, EventArgs.Empty));
        }
        catch
        {
            // UI 尚未初始化（极端关闭路径）：忽略。
        }
    }

    /// <summary>UDP 报文（保持字段短小的 JSON）。</summary>
    private sealed record Announcement(
        [property: System.Text.Json.Serialization.JsonPropertyName("v")] string Version,
        [property: System.Text.Json.Serialization.JsonPropertyName("deviceId")] string DeviceId,
        [property: System.Text.Json.Serialization.JsonPropertyName("deviceName")] string DeviceName,
        [property: System.Text.Json.Serialization.JsonPropertyName("userId")] long UserId,
        [property: System.Text.Json.Serialization.JsonPropertyName("nickname")] string? Nickname,
        [property: System.Text.Json.Serialization.JsonPropertyName("port")] int TransferPort,
        [property: System.Text.Json.Serialization.JsonPropertyName("ts")] long Timestamp);
}
