using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading.Tasks;

#pragma warning disable CA1416 // Windows-only COM APIs

namespace BondClient.Services;

public static class FirewallHelper
{
    private const string RulePrefix = "BondClient";
    private const int UdpPort = 19851;
    private const int TcpPort = 19850;
    private const int MulticastPort = 19852;

    public static FirewallStatus CheckStatus()
    {
        try
        {
            var fwPolicy = Activator.CreateInstance(Type.GetTypeFromProgID("HNetCfg.FwPolicy2"));
            if (fwPolicy == null) return new FirewallStatus();

            var currentProfile = (int)fwPolicy.GetType().InvokeMember("CurrentProfileTypes",
                System.Reflection.BindingFlags.GetProperty, null, fwPolicy, null)!;

            bool isPrivate = (currentProfile & 1) != 0;  // NET_FW_PROFILE2_PRIVATE
            bool isPublic = (currentProfile & 2) != 0;   // NET_FW_PROFILE2_PUBLIC
            bool isDomain = (currentProfile & 4) != 0;   // NET_FW_PROFILE2_DOMAIN

            // Check if our rules exist and are enabled
            var rules = fwPolicy.GetType().InvokeMember("Rules",
                System.Reflection.BindingFlags.GetProperty, null, fwPolicy, null)!;
            var rulesCollection = (System.Collections.IEnumerable)rules;

            bool hasUdpRule = false;
            bool hasTcpRule = false;

            foreach (var rule in rulesCollection)
            {
                try
                {
                    var name = (string)rule.GetType().InvokeMember("Name",
                        System.Reflection.BindingFlags.GetProperty, null, rule, null)!;
                    if (name.StartsWith(RulePrefix))
                    {
                        var enabled = (bool)rule.GetType().InvokeMember("Enabled",
                            System.Reflection.BindingFlags.GetProperty, null, rule, null)!;
                        if (enabled && name.Contains("UDP")) hasUdpRule = true;
                        if (enabled && name.Contains("TCP")) hasTcpRule = true;
                    }
                }
                catch { }
            }

            return new FirewallStatus
            {
                IsPrivate = isPrivate,
                IsPublic = isPublic,
                IsDomain = isDomain,
                HasUdpRule = hasUdpRule,
                HasTcpRule = hasTcpRule,
                NeedsPublicApproval = isPublic && (!hasUdpRule || !hasTcpRule),
                IsChecked = true
            };
        }
        catch
        {
            return new FirewallStatus { IsChecked = false };
        }
    }

    public static bool TryAddRules()
    {
        try
        {
            var fwPolicy = Activator.CreateInstance(Type.GetTypeFromProgID("HNetCfg.FwPolicy2"));
            if (fwPolicy == null) return false;

            var rules = fwPolicy.GetType().InvokeMember("Rules",
                System.Reflection.BindingFlags.GetProperty, null, fwPolicy, null)!;

            AddRule(rules, $"{RulePrefix} UDP Discovery", "Allows Bond device discovery",
                UdpPort, "UDP", 2); // NET_FW_ACTION_ALLOW
            AddRule(rules, $"{RulePrefix} Multicast Discovery", "Allows Bond multicast discovery",
                MulticastPort, "UDP", 2);
            AddRule(rules, $"{RulePrefix} TCP Transfer", "Allows Bond file transfer",
                TcpPort, "TCP", 2);

            return true;
        }
        catch (COMException)
        {
            // No admin rights — can't add rules via COM
            return false;
        }
        catch
        {
            return false;
        }
    }

    private static void AddRule(object rules, string name, string description, int port, string protocol, int action)
    {
        var ruleType = Type.GetTypeFromProgID("HNetCfg.FWRule");
        if (ruleType == null) return;

        var rule = Activator.CreateInstance(ruleType);
        if (rule == null) return;

        rule.GetType().InvokeMember("Name", System.Reflection.BindingFlags.SetProperty, null, rule, new object[] { name });
        rule.GetType().InvokeMember("Description", System.Reflection.BindingFlags.SetProperty, null, rule, new object[] { description });
        rule.GetType().InvokeMember("Protocol", System.Reflection.BindingFlags.SetProperty, null, rule, new object[] { protocol == "TCP" ? 6 : 17 });
        rule.GetType().InvokeMember("LocalPorts", System.Reflection.BindingFlags.SetProperty, null, rule, new object[] { port.ToString() });
        rule.GetType().InvokeMember("Direction", System.Reflection.BindingFlags.SetProperty, null, rule, new object[] { 1 }); // NET_FW_RULE_DIR_IN
        rule.GetType().InvokeMember("Action", System.Reflection.BindingFlags.SetProperty, null, rule, new object[] { action });
        rule.GetType().InvokeMember("Enabled", System.Reflection.BindingFlags.SetProperty, null, rule, new object[] { true });
        rule.GetType().InvokeMember("Grouping", System.Reflection.BindingFlags.SetProperty, null, rule, new object[] { "Bond" });
        rule.GetType().InvokeMember("Profiles", System.Reflection.BindingFlags.SetProperty, null, rule, new object[] { 0x7FFFFFFF }); // All profiles

        rules.GetType().InvokeMember("Add", System.Reflection.BindingFlags.InvokeMethod, null, rules, new object[] { rule });
    }

    public static string CreateBatchScript()
    {
        var scriptPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "BondClient", "add_firewall_rules.bat");

        var content = $@"@echo off
echo ========================================
echo  Bond - Adding Firewall Rules
echo ========================================
echo.
netsh advfirewall firewall add rule name=""{RulePrefix} UDP Discovery"" dir=in action=allow protocol=UDP localport={UdpPort} profile=any enable=yes
netsh advfirewall firewall add rule name=""{RulePrefix} Multicast Discovery"" dir=in action=allow protocol=UDP localport={MulticastPort} profile=any enable=yes
netsh advfirewall firewall add rule name=""{RulePrefix} TCP Transfer"" dir=in action=allow protocol=TCP localport={TcpPort} profile=any enable=yes
echo.
echo Done! Bond can now discover devices on all network types.
echo You can delete this file.
pause
";
        Directory.CreateDirectory(Path.GetDirectoryName(scriptPath)!);
        File.WriteAllText(scriptPath, content);
        return scriptPath;
    }

    public static void TryRunAsAdmin(string scriptPath)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = scriptPath,
                UseShellExecute = true,
                Verb = "runas"
            };
            Process.Start(psi);
        }
        catch
        {
            // User declined UAC prompt
        }
    }
}

public class FirewallStatus
{
    public bool IsChecked { get; set; }
    public bool IsPrivate { get; set; }
    public bool IsPublic { get; set; }
    public bool IsDomain { get; set; }
    public bool HasUdpRule { get; set; }
    public bool HasTcpRule { get; set; }
    public bool NeedsPublicApproval { get; set; }

    public string ProfileName => IsDomain ? "域网络" : IsPrivate ? "专用网络" : IsPublic ? "公共网络" : "未知";
}
