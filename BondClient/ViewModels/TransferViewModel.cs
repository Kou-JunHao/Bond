using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Threading;
using BondClient.Models;
using BondClient.Services;

namespace BondClient.ViewModels;

public class TransferViewModel
{
    private readonly PasswordManager _password;
    private readonly DiscoveryService _discovery;
    private readonly TransferService _transfer;
    private readonly TransferRouter? _router;
    private readonly CloudTransferService? _cloudTransfer;
    private readonly ApiClient? _api;
    private readonly TransferHistoryService? _history;

    public ObservableCollection<DeviceItem> Devices { get; } = [];
    public ObservableCollection<PendingRequest> PendingRequests { get; } = [];
    public ObservableCollection<RecentFile> RecentFiles { get; } = [];
    public ObservableCollection<string> StatusMessages { get; } = [];
    public TransferProgress Progress { get; } = new();

    public event Action? RequestReceived;
    public event Action? DevicesUpdated;
    public event Action<string>? TransferDone;
    public event Action<string>? TransferError;
    public event Action? TransferStarted;
    public event Action? TransferEnded;

    public string[]? QueuedFiles { get; set; }

    public TransferViewModel(PasswordManager password, DiscoveryService discovery, TransferService transfer,
        TransferRouter? router = null, CloudTransferService? cloudTransfer = null, ApiClient? api = null,
        TransferHistoryService? history = null)
    {
        _password = password;
        _discovery = discovery;
        _transfer = transfer;
        _router = router;
        _cloudTransfer = cloudTransfer;
        _api = api;
        _history = history;

        _discovery.DeviceFound += OnDeviceFound;
        _discovery.DeviceLost += OnDeviceLost;
        _transfer.IncomingRequest += OnIncomingRequest;
        _transfer.ReceiveComplete += OnReceiveComplete;
        _transfer.TransferComplete += OnTransferComplete;
        _transfer.TransferFailed += OnTransferFailed;
        _transfer.ProgressUpdated += OnProgressUpdated;
        _transfer.TransferStarted += () => Dispatcher.UIThread.Post(() => TransferStarted?.Invoke());
        _transfer.TransferEnded += () => Dispatcher.UIThread.Post(() => TransferEnded?.Invoke());

        if (_cloudTransfer != null)
        {
            _cloudTransfer.TransferComplete += OnCloudTransferComplete;
        }
    }

    private void OnDeviceFound(DeviceInfo device)
    {
        Dispatcher.UIThread.Post(() =>
        {
            var existing = Devices.FirstOrDefault(d => d.Device.Id == device.Id);
            if (existing != null)
                existing.Device = device;
            else
                Devices.Add(new DeviceItem(device));
            DevicesUpdated?.Invoke();
        });
    }

    private void OnDeviceLost(string id)
    {
        Dispatcher.UIThread.Post(() =>
        {
            var item = Devices.FirstOrDefault(d => d.Device.Id == id);
            if (item != null) Devices.Remove(item);
            DevicesUpdated?.Invoke();
        });
    }

    private void OnIncomingRequest(TransferRequest request, System.Net.Sockets.TcpClient client)
    {
        Dispatcher.UIThread.Post(() =>
        {
            var ip = "";
            try { ip = (client.Client.RemoteEndPoint as System.Net.IPEndPoint)?.Address?.ToString() ?? ""; } catch { }

            var fileList = string.Join(", ", request.Files
                .Where(f => !f.IsDirectory)
                .Take(3)
                .Select(f => System.IO.Path.GetFileName(f.RelativePath)));
            if (request.Files.Count(f => !f.IsDirectory) > 3)
                fileList += "...";

            PendingRequests.Add(new PendingRequest
            {
                Request = request,
                FromName = request.FromName,
                FromIp = ip,
                FileCount = Enumerable.Count(request.Files, f => !f.IsDirectory),
                TotalSize = FormatSize(request.TotalSize),
                FileList = fileList
            });
            RequestReceived?.Invoke();
        });
    }

    private void OnProgressUpdated(TransferProgress progress)
    {
        Dispatcher.UIThread.Post(() =>
        {
            Progress.FileName = progress.FileName;
            Progress.BytesTransferred = progress.BytesTransferred;
            Progress.TotalBytes = progress.TotalBytes;
            Progress.FileBytesTransferred = progress.FileBytesTransferred;
            Progress.FileTotalBytes = progress.FileTotalBytes;
            Progress.FilesCompleted = progress.FilesCompleted;
            Progress.TotalFiles = progress.TotalFiles;
            Progress.Speed = progress.Speed;
            Progress.IsTransferring = progress.IsTransferring;
            Progress.IsCancelled = progress.IsCancelled;
        });
    }

    private void OnTransferComplete(string fileName)
    {
        Dispatcher.UIThread.Post(() =>
        {
            TransferDone?.Invoke(fileName);
            var fileInfo = new System.IO.FileInfo(fileName);
            var fileSize = fileInfo.Exists ? fileInfo.Length : 0;
            _history?.AddEntry(System.IO.Path.GetFileName(fileName), fileSize, TransferDirection.Sent, "LAN");
        });
    }

    private void OnReceiveComplete(string fileName)
    {
        Dispatcher.UIThread.Post(() =>
        {
            RecentFiles.Insert(0, new RecentFile
            {
                FileName = System.IO.Path.GetFileName(fileName),
                ReceivedAt = DateTime.Now
            });
            while (RecentFiles.Count > 50) RecentFiles.RemoveAt(RecentFiles.Count - 1);

            var fileInfo = new System.IO.FileInfo(fileName);
            var fileSize = fileInfo.Exists ? fileInfo.Length : 0;
            _history?.AddEntry(System.IO.Path.GetFileName(fileName), fileSize, TransferDirection.Received, "LAN");
        });
    }

    private void OnCloudTransferComplete(string fileName)
    {
        Dispatcher.UIThread.Post(() =>
        {
            var fileInfo = new System.IO.FileInfo(fileName);
            var fileSize = fileInfo.Exists ? fileInfo.Length : 0;
            _history?.AddEntry(System.IO.Path.GetFileName(fileName), fileSize, TransferDirection.Sent, "云端");
        });
    }

    private void OnTransferFailed(string error)
    {
        Dispatcher.UIThread.Post(() =>
        {
            StatusMessages.Add($"传输失败: {error}");
            TransferError?.Invoke($"传输失败: {error}");
        });
    }

    public void ApproveRequest(PendingRequest req)
    {
        _transfer.RespondToRequest(req.Request.FromId, TransferResponse.Approved);
        PendingRequests.Remove(req);
    }

    public void RejectRequest(PendingRequest req)
    {
        _transfer.RespondToRequest(req.Request.FromId, TransferResponse.Rejected);
        PendingRequests.Remove(req);
    }

    public void CancelTransfer()
    {
        _transfer.CancelActiveTransfer();
    }

    public async Task SendToSelected(List<DeviceItem> targets, CancellationToken ct = default)
    {
        if (QueuedFiles == null || QueuedFiles.Length == 0) return;

        foreach (var target in targets)
        {
            string? password = null;
            if (target.Device.HasPassword && !string.IsNullOrEmpty(target.EnteredPassword))
                password = target.EnteredPassword;

            var mode = _router?.DetermineTransferMode(target.Device) ?? TransferMode.LanDirect;

            if (mode == TransferMode.CloudRelay && _cloudTransfer != null && _api?.IsLoggedIn == true)
            {
                StatusMessages.Add($"正在通过云端发送到 {target.Device.Name}...");
                foreach (var file in QueuedFiles)
                {
                    var receiverId = long.TryParse(target.Device.Id, out var rid) ? rid : 0;
                    if (receiverId == 0)
                    {
                        StatusMessages.Add($"发送到 {target.Device.Name} 失败: 无法获取设备ID");
                        continue;
                    }
                    await _cloudTransfer.SendFileAsync(file, receiverId, null, null);
                    var fileInfo = new System.IO.FileInfo(file);
                    var fileSize = fileInfo.Exists ? fileInfo.Length : 0;
                    _history?.AddEntry(System.IO.Path.GetFileName(file), fileSize, TransferDirection.Sent, target.Device.Name);
                }
                StatusMessages.Add($"已通过云端发送到 {target.Device.Name}");
            }
            else
            {
                StatusMessages.Add($"正在发送到 {target.Device.Name}...");
                var response = await _transfer.SendFiles(target.Device, QueuedFiles, password, ct);

                switch (response)
                {
                    case TransferResponse.Approved:
                        StatusMessages.Add($"已发送到 {target.Device.Name}");
                        break;
                    case TransferResponse.WrongPassword:
                        var errPwd = $"发送到 {target.Device.Name} 失败: 密码错误";
                        StatusMessages.Add(errPwd);
                        TransferError?.Invoke(errPwd);
                        break;
                    case TransferResponse.Rejected:
                        var errRej = $"发送到 {target.Device.Name} 失败: 对方拒绝";
                        StatusMessages.Add(errRej);
                        TransferError?.Invoke(errRej);
                        break;
                    case TransferResponse.NeedPassword:
                        var errNeed = $"发送到 {target.Device.Name} 失败: 需要密码";
                        StatusMessages.Add(errNeed);
                        TransferError?.Invoke(errNeed);
                        break;
                }
            }
        }

        QueuedFiles = null;
    }

    public void RefreshDevices()
    {
        Devices.Clear();
        foreach (var d in _discovery.GetDevices())
            Devices.Add(new DeviceItem(d));
        DevicesUpdated?.Invoke();
    }

    public static string FormatSize(long bytes)
    {
        string[] units = ["B", "KB", "MB", "GB"];
        double size = bytes;
        int unit = 0;
        while (size >= 1024 && unit < units.Length - 1) { size /= 1024; unit++; }
        return $"{size:F1} {units[unit]}";
    }
}

public class DeviceItem : System.ComponentModel.INotifyPropertyChanged
{
    private DeviceInfo _device;
    private string _enteredPassword = "";
    private bool _isSelected;

    public DeviceItem(DeviceInfo device) => _device = device;

    public DeviceInfo Device
    {
        get => _device;
        set { _device = value; OnPropertyChanged(); OnPropertyChanged(nameof(PasswordVisible)); }
    }

    public string EnteredPassword
    {
        get => _enteredPassword;
        set { _enteredPassword = value; OnPropertyChanged(); }
    }

    public bool IsSelected
    {
        get => _isSelected;
        set { _isSelected = value; OnPropertyChanged(); }
    }

    public bool PasswordVisible => _device.HasPassword;

    public event System.ComponentModel.PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([System.Runtime.CompilerServices.CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new System.ComponentModel.PropertyChangedEventArgs(name));
}

public class PendingRequest
{
    public TransferRequest Request { get; set; } = new();
    public string FromName { get; set; } = "";
    public string FromIp { get; set; } = "";
    public int FileCount { get; set; }
    public string TotalSize { get; set; } = "";
    public string FileList { get; set; } = "";
    public int RemainingSeconds { get; set; } = 30;
    public string CountdownText => $"{RemainingSeconds}s";
}

public class RecentFile
{
    public string FileName { get; set; } = "";
    public DateTime ReceivedAt { get; set; }
    public string TimeAgo
    {
        get
        {
            var span = DateTime.Now - ReceivedAt;
            if (span.TotalSeconds < 60) return "刚刚";
            if (span.TotalMinutes < 60) return $"{(int)span.TotalMinutes} 分钟前";
            if (span.TotalHours < 24) return $"{(int)span.TotalHours} 小时前";
            return ReceivedAt.ToString("MM-dd HH:mm");
        }
    }
}
