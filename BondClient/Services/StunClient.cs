using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;

namespace BondClient.Services;

public class StunClient
{
    private const int StunPort = 3478;
    private const int MagicCookie = 0x2112A442;
    private static readonly byte[] MagicCookieBytes = [0x21, 0x12, 0xA4, 0x42];

    public record StunResult(string PublicIP, int PublicPort, bool IsOpen);

    public static async Task<StunResult?> GetPublicEndpointAsync(string stunServer, int timeoutMs = 3000)
    {
        try
        {
            var parts = stunServer.Split(':');
            var host = parts[0];
            var port = parts.Length > 1 && int.TryParse(parts[1], out var p) ? p : StunPort;

            using var udp = new UdpClient();
            udp.Client.ReceiveTimeout = timeoutMs;
            udp.Client.SendTimeout = timeoutMs;

            var serverEP = new IPEndPoint(Dns.GetHostAddresses(host).First(), port);
            var request = CreateBindingRequest();
            await udp.SendAsync(request, request.Length, serverEP);

            var result = await udp.ReceiveAsync();
            return ParseBindingResponse(result.Buffer);
        }
        catch
        {
            return null;
        }
    }

    private static byte[] CreateBindingRequest()
    {
        var transactionId = new byte[12];
        RandomNumberGenerator.Fill(transactionId);

        var msg = new byte[20];
        msg[0] = 0x00; msg[1] = 0x01; // Binding Request
        msg[2] = 0x00; msg[3] = 0x00; // Length = 0
        Array.Copy(MagicCookieBytes, 0, msg, 4, 4);
        Array.Copy(transactionId, 0, msg, 8, 12);
        return msg;
    }

    private static StunResult? ParseBindingResponse(byte[] data)
    {
        if (data.Length < 20) return null;

        var msgType = (data[0] << 8) | data[1];
        if (msgType != 0x0101) return null; // Not Binding Response

        var magicCookie = (data[4] << 24) | (data[5] << 16) | (data[6] << 8) | data[7];
        if (magicCookie != MagicCookie) return null;

        var msgLength = (data[2] << 8) | data[3];
        var offset = 20;

        while (offset + 4 <= 20 + msgLength)
        {
            var attrType = (data[offset] << 8) | data[offset + 1];
            var attrLength = (data[offset + 2] << 8) | data[offset + 3];
            offset += 4;

            if (attrType == 0x0001 || attrType == 0x0020) // MAPPED-ADDRESS or XOR-MAPPED-ADDRESS
            {
                if (attrLength < 8) { offset += attrLength; continue; }

                var family = data[offset + 1];
                if (family != 0x01) { offset += attrLength; continue; } // Only IPv4

                int port;
                if (attrType == 0x0020) // XOR-MAPPED-ADDRESS
                {
                    port = ((data[offset + 2] ^ MagicCookieBytes[0]) << 8) | (data[offset + 3] ^ MagicCookieBytes[1]);
                    var ip = new byte[4];
                    for (int i = 0; i < 4; i++)
                        ip[i] = (byte)(data[offset + 4 + i] ^ MagicCookieBytes[i]);
                    var publicIP = new IPAddress(ip).ToString();
                    return new StunResult(publicIP, port, true);
                }
                else
                {
                    port = (data[offset + 2] << 8) | data[offset + 3];
                    var ip = new byte[4];
                    Array.Copy(data, offset + 4, ip, 0, 4);
                    var publicIP = new IPAddress(ip).ToString();
                    return new StunResult(publicIP, port, true);
                }
            }

            offset += attrLength;
            if (attrLength % 4 != 0) offset += 4 - (attrLength % 4); // Padding
        }

        return null;
    }

    public static bool IsOnSameSubnet(string ip1, string ip2, string mask)
    {
        try
        {
            var addr1 = IPAddress.Parse(ip1).GetAddressBytes();
            var addr2 = IPAddress.Parse(ip2).GetAddressBytes();
            var maskBytes = IPAddress.Parse(mask).GetAddressBytes();

            for (int i = 0; i < 4; i++)
            {
                if ((addr1[i] & maskBytes[i]) != (addr2[i] & maskBytes[i]))
                    return false;
            }
            return true;
        }
        catch { return false; }
    }
}
