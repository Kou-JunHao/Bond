using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using BondClient.Models;

namespace BondClient.Services;

public class DiscoveryService : IDisposable
{
    private const int BroadcastPort = 19851;
    private const int MulticastPort = 19851;
    private static readonly IPAddress MulticastAddr = IPAddress.Parse("224.0.0.251");

    private const int FastIntervalMs = 500;    // First 10s: aggressive polling
    private const int NormalIntervalMs = 3000;  // After 10s: steady state
    private const int FastPhaseMs = 10000;
    private const int DeviceTimeoutSec = 15;    // Tolerate 5 missed broadcasts
    private const int RescanNetworkIntervalMs = 30000; // Re-scan interfaces every 30s

    private readonly PasswordManager _password;
    private UdpClient? _udpBroadcast;
    private UdpClient? _udpMulticast;
    private CancellationTokenSource? _cts;
    private readonly ConcurrentDictionary<string, DeviceInfo> _devices = new();

    private List<IPEndPoint> _cachedBroadcastEndpoints = [];
    private DateTime _lastNetworkScan = DateTime.MinValue;

    public event Action<DeviceInfo>? DeviceFound;
    public event Action<string>? DeviceLost;

    public DiscoveryService(PasswordManager password)
    {
        _password = password;
    }

    public void Start()
    {
        _cts = new CancellationTokenSource();

        // Broadcast socket
        _udpBroadcast = new UdpClient();
        _udpBroadcast.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _udpBroadcast.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ExclusiveAddressUse, false);
        _udpBroadcast.Client.Bind(new IPEndPoint(IPAddress.Any, BroadcastPort));
        _udpBroadcast.EnableBroadcast = true;

        // Multicast socket — separate socket for multicast receive
        try
        {
            _udpMulticast = new UdpClient(BroadcastPort + 1);
            _udpMulticast.JoinMulticastGroup(MulticastAddr);
        }
        catch
        {
            // Multicast not supported on this network — fallback to broadcast only
            _udpMulticast?.Dispose();
            _udpMulticast = null;
        }

        _ = Task.Run(() => ReceiveLoop(_cts.Token));
        _ = Task.Run(() => BroadcastLoop(_cts.Token));
        _ = Task.Run(() => CleanupLoop(_cts.Token));
    }

    public void Stop()
    {
        _cts?.Cancel();
        _udpBroadcast?.Close();
        _udpBroadcast = null;
        _udpMulticast?.Close();
        _udpMulticast = null;
    }

    public DeviceInfo[] GetDevices()
    {
        var cutoff = DateTimeOffset.UtcNow.ToUnixTimeSeconds() - DeviceTimeoutSec;
        foreach (var kv in _devices)
        {
            if (kv.Value.Timestamp < cutoff)
                _devices.TryRemove(kv.Key, out _);
        }
        return [.. _devices.Values];
    }

    private async Task ReceiveLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                UdpReceiveResult result;

                if (_udpMulticast != null)
                {
                    // Wait on either broadcast or multicast socket
                    var broadcastTask = _udpBroadcast!.ReceiveAsync(ct).AsTask();
                    var multicastTask = _udpMulticast.ReceiveAsync(ct).AsTask();

                    var completed = await Task.WhenAny(broadcastTask, multicastTask);
                    result = completed == broadcastTask ? await broadcastTask : await multicastTask;
                }
                else
                {
                    result = await _udpBroadcast!.ReceiveAsync(ct);
                }

                var json = Encoding.UTF8.GetString(result.Buffer);
                var device = JsonSerializer.Deserialize<DeviceInfo>(json);
                if (device != null && device.Id != _password.DeviceId)
                {
                    device.Ip = result.RemoteEndPoint.Address.ToString();
                    var isNew = !_devices.ContainsKey(device.Id);
                    _devices[device.Id] = device;

                    if (isNew)
                    {
                        DeviceFound?.Invoke(device);
                        // New device found — immediately announce ourselves so they see us too
                        _ = Task.Run(() => AnnounceOnce(ct));
                    }
                }
            }
            catch (OperationCanceledException) { break; }
            catch { }
        }
    }

    private async Task BroadcastLoop(CancellationToken ct)
    {
        var sw = Stopwatch.StartNew();

        while (!ct.IsCancellationRequested)
        {
            try
            {
                await AnnounceOnce(ct);
            }
            catch { }

            // Fast phase: 500ms intervals for first 10s (e.g. app startup)
            // Normal phase: 3s intervals after that
            var interval = sw.ElapsedMilliseconds < FastPhaseMs ? FastIntervalMs : NormalIntervalMs;
            await Task.Delay(interval, ct);
        }
    }

    public async Task AnnounceOnce(CancellationToken ct)
    {
        var info = new DeviceInfo
        {
            Id = _password.DeviceId,
            Name = _password.DeviceName,
            Port = _password.TransferPort,
            HasPassword = _password.HasPassword
        };
        var json = JsonSerializer.Serialize(info);
        var data = Encoding.UTF8.GetBytes(json);

        // Refresh broadcast endpoints if stale
        if ((DateTime.Now - _lastNetworkScan).TotalMilliseconds > RescanNetworkIntervalMs)
        {
            _cachedBroadcastEndpoints = GetBroadcastEndpoints();
            _lastNetworkScan = DateTime.Now;
        }

        // Send to all subnet broadcast addresses
        foreach (var ep in _cachedBroadcastEndpoints)
        {
            try { await _udpBroadcast!.SendAsync(data, data.Length, ep); } catch { }
        }

        // Fallback: global broadcast
        if (_cachedBroadcastEndpoints.Count == 0)
        {
            try { await _udpBroadcast!.SendAsync(data, data.Length, new IPEndPoint(IPAddress.Broadcast, BroadcastPort)); } catch { }
        }

        // Send to multicast group
        if (_udpMulticast != null)
        {
            try { await _udpBroadcast!.SendAsync(data, data.Length, new IPEndPoint(MulticastAddr, BroadcastPort)); } catch { }
        }
    }

    private static List<IPEndPoint> GetBroadcastEndpoints()
    {
        var endpoints = new List<IPEndPoint>();

        foreach (var iface in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (iface.OperationalStatus != OperationalStatus.Up) continue;
            if (iface.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;

            foreach (var addr in iface.GetIPProperties().UnicastAddresses)
            {
                if (addr.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                try
                {
                    var mask = addr.IPv4Mask;
                    if (mask == null) continue;
                    var ip = addr.Address.GetAddressBytes();
                    var maskBytes = mask.GetAddressBytes();
                    var bcast = new byte[4];
                    for (int j = 0; j < 4; j++)
                        bcast[j] = (byte)(ip[j] | ~maskBytes[j]);
                    endpoints.Add(new IPEndPoint(new IPAddress(bcast), BroadcastPort));
                }
                catch { }
            }
        }

        return endpoints;
    }

    private async Task CleanupLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            var cutoff = DateTimeOffset.UtcNow.ToUnixTimeSeconds() - DeviceTimeoutSec;
            foreach (var kv in _devices)
            {
                if (kv.Value.Timestamp < cutoff && _devices.TryRemove(kv.Key, out _))
                    DeviceLost?.Invoke(kv.Key);
            }
            await Task.Delay(2000, ct);
        }
    }

    public void Dispose()
    {
        Stop();
        _udpBroadcast?.Dispose();
        _udpMulticast?.Dispose();
        _cts?.Dispose();
    }

    public List<SubnetInfo> GetAvailableSubnets()
    {
        var subnets = new List<SubnetInfo>();

        foreach (var iface in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (iface.OperationalStatus != OperationalStatus.Up) continue;
            if (iface.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;

            foreach (var addr in iface.GetIPProperties().UnicastAddresses)
            {
                if (addr.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                try
                {
                    var mask = addr.IPv4Mask;
                    if (mask == null) continue;
                    var ip = addr.Address.GetAddressBytes();
                    var maskBytes = mask.GetAddressBytes();
                    var bcast = new byte[4];
                    for (int j = 0; j < 4; j++)
                        bcast[j] = (byte)(ip[j] | ~maskBytes[j]);

                    var cidr = 0;
                    foreach (var b in maskBytes)
                        cidr += System.Numerics.BitOperations.PopCount(b);

                    subnets.Add(new SubnetInfo
                    {
                        Name = iface.Name,
                        Description = iface.NetworkInterfaceType.ToString(),
                        LocalIP = addr.Address.ToString(),
                        SubnetMask = mask.ToString(),
                        BroadcastAddress = new IPAddress(bcast).ToString(),
                        Cidr = cidr,
                        BroadcastEndpoint = new IPEndPoint(new IPAddress(bcast), BroadcastPort)
                    });
                }
                catch { }
            }
        }

        return subnets;
    }

    public async Task ScanSubnet(IPEndPoint broadcastEndpoint, CancellationToken ct = default)
    {
        var info = new DeviceInfo
        {
            Id = _password.DeviceId,
            Name = _password.DeviceName,
            Port = _password.TransferPort,
            HasPassword = _password.HasPassword
        };
        var json = JsonSerializer.Serialize(info);
        var data = Encoding.UTF8.GetBytes(json);

        try { await _udpBroadcast!.SendAsync(data, data.Length, broadcastEndpoint); } catch { }

        // Also send to the .1-.254 range as unicast probe for devices that miss broadcast
        var baseBytes = broadcastEndpoint.Address.GetAddressBytes();
        for (int i = 1; i <= 254; i++)
        {
            var probeIp = new byte[] { baseBytes[0], baseBytes[1], baseBytes[2], (byte)i };
            try { await _udpBroadcast!.SendAsync(data, data.Length, new IPEndPoint(new IPAddress(probeIp), BroadcastPort)); } catch { }
        }
    }
}

public class SubnetInfo
{
    public string Name { get; set; } = "";
    public string Description { get; set; } = "";
    public string LocalIP { get; set; } = "";
    public string SubnetMask { get; set; } = "";
    public string BroadcastAddress { get; set; } = "";
    public int Cidr { get; set; }
    public IPEndPoint? BroadcastEndpoint { get; set; }

    public string DisplayText => $"{LocalIP}/{Cidr} ({Description})";
}
