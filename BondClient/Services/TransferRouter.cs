using BondClient.Models;

namespace BondClient.Services;

public enum TransferMode
{
    LanDirect,
    CloudRelay,
    NatDirect,
    OfflineRelay
}

public class TransferRouter
{
    private readonly DiscoveryService _discovery;
    private readonly ApiClient? _api;
    private string? _publicIP;
    private bool _natChecked;

    public TransferRouter(DiscoveryService discovery, ApiClient? api = null)
    {
        _discovery = discovery;
        _api = api;
    }

    public async Task<TransferMode> DetermineTransferModeAsync(DeviceInfo target)
    {
        if (!string.IsNullOrEmpty(target.Ip) && IsSameSubnet(target.Ip))
            return TransferMode.LanDirect;

        if (_api?.IsLoggedIn == true)
            return TransferMode.CloudRelay;

        return TransferMode.OfflineRelay;
    }

    public TransferMode DetermineTransferMode(DeviceInfo target)
    {
        if (!string.IsNullOrEmpty(target.Ip) && IsSameSubnet(target.Ip))
            return TransferMode.LanDirect;

        return TransferMode.CloudRelay;
    }

    public async Task<string?> GetPublicIPAsync(string stunServer = "stun.l.google.com:19302")
    {
        if (_natChecked) return _publicIP;
        _natChecked = true;

        var result = await StunClient.GetPublicEndpointAsync(stunServer);
        _publicIP = result?.PublicIP;
        return _publicIP;
    }

    private bool IsSameSubnet(string targetIp)
    {
        try
        {
            var subnets = _discovery.GetAvailableSubnets();
            var targetAddr = System.Net.IPAddress.Parse(targetIp);
            foreach (var subnet in subnets)
            {
                var localAddr = System.Net.IPAddress.Parse(subnet.LocalIP);
                var maskAddr = System.Net.IPAddress.Parse(subnet.SubnetMask);
                var localBytes = localAddr.GetAddressBytes();
                var maskBytes = maskAddr.GetAddressBytes();
                var targetBytes = targetAddr.GetAddressBytes();

                if (localBytes.Length != targetBytes.Length || localBytes.Length != maskBytes.Length)
                    continue;

                bool match = true;
                for (int i = 0; i < localBytes.Length; i++)
                {
                    if ((localBytes[i] & maskBytes[i]) != (targetBytes[i] & maskBytes[i]))
                    {
                        match = false;
                        break;
                    }
                }
                if (match) return true;
            }
        }
        catch { }
        return false;
    }
}
