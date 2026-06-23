using System.Text.Json;
using BondClient.Models;

namespace BondClient.Services;

public class TransferHistoryService
{
    private static readonly string HistoryDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BondClient");
    private static readonly string HistoryFile = Path.Combine(HistoryDir, "history.json");
    private const int MaxEntries = 50;

    private List<TransferHistoryEntry> _entries = [];

    public IReadOnlyList<TransferHistoryEntry> Entries => _entries;

    public void Load()
    {
        try
        {
            if (!File.Exists(HistoryFile)) { _entries = []; return; }
            var json = File.ReadAllText(HistoryFile);
            _entries = JsonSerializer.Deserialize(json, BondJsonContext.Default.ListTransferHistoryEntry) ?? [];
        }
        catch
        {
            _entries = [];
        }
    }

    public void Save()
    {
        try
        {
            if (!Directory.Exists(HistoryDir)) Directory.CreateDirectory(HistoryDir);
            var json = JsonSerializer.Serialize(_entries, BondJsonContext.Default.ListTransferHistoryEntry);
            File.WriteAllText(HistoryFile, json);
        }
        catch { }
    }

    public void AddEntry(string fileName, long fileSize, TransferDirection direction, string deviceName)
    {
        _entries.Insert(0, new TransferHistoryEntry
        {
            FileName = fileName,
            FileSize = fileSize,
            Direction = direction,
            Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            DeviceName = deviceName
        });
        while (_entries.Count > MaxEntries)
            _entries.RemoveAt(_entries.Count - 1);
        Save();
    }
}
