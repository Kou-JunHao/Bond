using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace BondClient.Models;

public class TransferProgress : INotifyPropertyChanged
{
    private string _fileName = "";
    private long _bytesTransferred;
    private long _totalBytes;
    private long _fileBytesTransferred;
    private long _fileTotalBytes;
    private int _filesCompleted;
    private int _totalFiles;
    private double _speed;
    private bool _isTransferring;
    private bool _isCancelled;

    public string FileName { get => _fileName; set { _fileName = value; OnPropertyChanged(); } }
    public long BytesTransferred { get => _bytesTransferred; set { _bytesTransferred = value; OnPropertyChanged(); OnPropertyChanged(nameof(Percent)); OnPropertyChanged(nameof(ETA)); } }
    public long TotalBytes { get => _totalBytes; set { _totalBytes = value; OnPropertyChanged(); OnPropertyChanged(nameof(Percent)); } }
    public long FileBytesTransferred { get => _fileBytesTransferred; set { _fileBytesTransferred = value; OnPropertyChanged(); OnPropertyChanged(nameof(FilePercent)); } }
    public long FileTotalBytes { get => _fileTotalBytes; set { _fileTotalBytes = value; OnPropertyChanged(); OnPropertyChanged(nameof(FilePercent)); } }
    public int FilesCompleted { get => _filesCompleted; set { _filesCompleted = value; OnPropertyChanged(); OnPropertyChanged(nameof(ProgressLabel)); } }
    public int TotalFiles { get => _totalFiles; set { _totalFiles = value; OnPropertyChanged(); OnPropertyChanged(nameof(ProgressLabel)); } }
    public double Speed { get => _speed; set { _speed = value; OnPropertyChanged(); OnPropertyChanged(nameof(SpeedText)); OnPropertyChanged(nameof(ETA)); } }
    public bool IsTransferring { get => _isTransferring; set { _isTransferring = value; OnPropertyChanged(); } }
    public bool IsCancelled { get => _isCancelled; set { _isCancelled = value; OnPropertyChanged(); } }

    public double Percent => TotalBytes > 0 ? (double)BytesTransferred / TotalBytes : 0;
    public double FilePercent => FileTotalBytes > 0 ? (double)FileBytesTransferred / FileTotalBytes : 0;
    public string ProgressLabel => TotalFiles > 1 ? $"{FilesCompleted + 1}/{TotalFiles} 文件" : "";
    public string SpeedText => Speed > 0 ? FormatSpeed(Speed) : "";
    public string ETA => Speed > 0 && TotalBytes > BytesTransferred
        ? FormatETA((TotalBytes - BytesTransferred) / Speed)
        : "";

    public void Reset()
    {
        FileName = "";
        BytesTransferred = 0;
        TotalBytes = 0;
        FileBytesTransferred = 0;
        FileTotalBytes = 0;
        FilesCompleted = 0;
        TotalFiles = 0;
        Speed = 0;
        IsTransferring = false;
        IsCancelled = false;
    }

    private static string FormatSpeed(double bytesPerSec)
    {
        string[] units = ["B/s", "KB/s", "MB/s", "GB/s"];
        double size = bytesPerSec;
        int unit = 0;
        while (size >= 1024 && unit < units.Length - 1) { size /= 1024; unit++; }
        return $"{size:F1} {units[unit]}";
    }

    private static string FormatETA(double seconds)
    {
        if (seconds < 60) return $"{(int)seconds}s";
        if (seconds < 3600) return $"{(int)(seconds / 60)}m {(int)(seconds % 60)}s";
        return $"{(int)(seconds / 3600)}h {(int)((seconds % 3600) / 60)}m";
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
