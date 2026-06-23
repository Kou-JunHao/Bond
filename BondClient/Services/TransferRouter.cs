using BondClient.Models;

namespace BondClient.Services;

public enum TransferMode
{
    LanDirect,
    CloudRelay,
    OfflineRelay
}

public class TransferRouter
{
    private readonly DiscoveryService _discovery;

    public TransferRouter(DiscoveryService discovery)
    {
        _discovery = discovery;
    }

    public TransferMode DetermineTransferMode(DeviceInfo target)
    {
        if (!string.IsNullOrEmpty(target.Ip) && IsSameSubnet(target.Ip))
            return TransferMode.LanDirect;

        return TransferMode.CloudRelay;
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
