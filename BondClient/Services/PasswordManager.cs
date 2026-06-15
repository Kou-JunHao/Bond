using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace BondClient.Services;

public class PasswordManager
{
    private static readonly string ConfigDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BondClient");
    private static readonly string ConfigFile = Path.Combine(ConfigDir, "config.json");

    public string DeviceId { get; private set; } = "";
    public string DeviceName { get; private set; } = Environment.MachineName;
    public string? Password { get; private set; }
    public bool HasPassword => !string.IsNullOrEmpty(Password);
    public int TransferPort { get; private set; } = 19850;
    public bool AutoApprove { get; private set; }
    public string DownloadPath { get; private set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "BondClient");

    public event Action? PasswordChanged;

    public void Load()
    {
        try
        {
            if (File.Exists(ConfigFile))
            {
                var json = File.ReadAllText(ConfigFile);
                var cfg = JsonSerializer.Deserialize<ConfigData>(json);
                if (cfg != null)
                {
                    DeviceId = cfg.DeviceId ?? "";
                    DeviceName = cfg.DeviceName ?? Environment.MachineName;
                    Password = cfg.Password;
                    TransferPort = cfg.TransferPort > 0 ? cfg.TransferPort : 19850;
                    AutoApprove = cfg.AutoApprove;
                    if (!string.IsNullOrEmpty(cfg.DownloadPath))
                        DownloadPath = cfg.DownloadPath;
                }
            }
        }
        catch { }

        if (string.IsNullOrEmpty(DeviceId))
            DeviceId = Guid.NewGuid().ToString("N")[..8];

        if (Password == null)
            Password = GeneratePassword();

        Save();
    }

    public void Save()
    {
        try
        {
            if (!Directory.Exists(ConfigDir)) Directory.CreateDirectory(ConfigDir);
            var json = JsonSerializer.Serialize(new ConfigData
            {
                DeviceId = DeviceId,
                DeviceName = DeviceName,
                Password = Password,
                TransferPort = TransferPort,
                AutoApprove = AutoApprove,
                DownloadPath = DownloadPath
            });
            File.WriteAllText(ConfigFile, json);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[Bond] Config save FAILED: {ex.Message}");
        }
    }

    public string GeneratePassword()
    {
        var rng = RandomNumberGenerator.Create();
        var bytes = new byte[4];
        rng.GetBytes(bytes);
        var num = BitConverter.ToUInt32(bytes, 0) % 1000000;
        return num.ToString("D6");
    }

    public void SetDeviceName(string name)
    {
        DeviceName = name;
        Save();
    }

    public void SetPassword(string? password)
    {
        Password = string.IsNullOrEmpty(password) ? null : password;
        Save();
        PasswordChanged?.Invoke();
    }

    public void RegeneratePassword()
    {
        Password = GeneratePassword();
        Save();
        PasswordChanged?.Invoke();
    }

    public void DisablePassword()
    {
        Password = null;
        Save();
        PasswordChanged?.Invoke();
    }

    public void SetAutoApprove(bool value)
    {
        AutoApprove = value;
        Save();
    }

    public void SetDownloadPath(string path)
    {
        DownloadPath = path;
        Save();
    }

    public void SetTransferPort(int port)
    {
        if (port is > 0 and <= 65535)
            TransferPort = port;
        Save();
    }

    public bool Verify(string input) => string.IsNullOrEmpty(Password) || Password == input;

    private class ConfigData
    {
        public string? DeviceId { get; set; }
        public string? DeviceName { get; set; }
        public string? Password { get; set; }
        public int TransferPort { get; set; }
        public bool AutoApprove { get; set; }
        public string? DownloadPath { get; set; }
    }
}
