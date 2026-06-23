using System.Security.Cryptography;
using System.Text.Json;
using BondClient.Models;

namespace BondClient.Services;

public class ChunkManager : IDisposable
{
    private readonly string _resumeDir;
    private static readonly BondJsonContext Ctx = BondJsonContext.Default;

    public int ChunkSize { get; set; } = 5 * 1024 * 1024;

    public ChunkManager()
    {
        _resumeDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "BondClient", "resume");
        Directory.CreateDirectory(_resumeDir);
    }

    public int CalculateChunkCount(long fileSize)
    {
        return (int)Math.Ceiling((double)fileSize / ChunkSize);
    }

    public int GetChunkSize(long fileSize)
    {
        if (fileSize > 1024L * 1024 * 1024) return 10 * 1024 * 1024;
        return ChunkSize;
    }

    public async Task<byte[]> ReadChunkAsync(string filePath, int chunkIndex, int chunkSize)
    {
        using var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read);
        fs.Seek((long)chunkIndex * chunkSize, SeekOrigin.Begin);
        var buffer = new byte[chunkSize];
        int totalRead = 0;
        while (totalRead < chunkSize)
        {
            int read = await fs.ReadAsync(buffer, totalRead, chunkSize - totalRead);
            if (read == 0) break;
            totalRead += read;
        }
        if (totalRead < chunkSize)
        {
            var trimmed = new byte[totalRead];
            Array.Copy(buffer, trimmed, totalRead);
            return trimmed;
        }
        return buffer;
    }

    public async Task<byte[]> ReadAndEncryptChunkAsync(string filePath, int chunkIndex, int chunkSize, byte[] aesKey)
    {
        var plainData = await ReadChunkAsync(filePath, chunkIndex, chunkSize);
        var (ciphertext, nonce, tag) = E2ECryptoService.EncryptAesGcm(aesKey, plainData);
        var combined = new byte[12 + 16 + ciphertext.Length];
        Array.Copy(nonce, 0, combined, 0, 12);
        Array.Copy(tag, 0, combined, 12, 16);
        Array.Copy(ciphertext, 0, combined, 28, ciphertext.Length);
        return combined;
    }

    public byte[] DecryptChunk(byte[] aesKey, byte[] encryptedData)
    {
        var nonce = new byte[12];
        var tag = new byte[16];
        var ciphertext = new byte[encryptedData.Length - 28];
        Array.Copy(encryptedData, 0, nonce, 0, 12);
        Array.Copy(encryptedData, 12, tag, 0, 16);
        Array.Copy(encryptedData, 28, ciphertext, 0, ciphertext.Length);
        return E2ECryptoService.DecryptAesGcm(aesKey, ciphertext, nonce, tag);
    }

    public async Task WriteChunkAsync(string filePath, int chunkIndex, int chunkSize, byte[] data)
    {
        using var fs = new FileStream(filePath, FileMode.OpenOrCreate, FileAccess.Write, FileShare.None);
        fs.Seek((long)chunkIndex * chunkSize, SeekOrigin.Begin);
        await fs.WriteAsync(data);
    }

    public string ComputeFileHash(string filePath)
    {
        using var sha = SHA256.Create();
        using var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read);
        var hash = sha.ComputeHash(fs);
        return Convert.ToBase64String(hash);
    }

    public void SaveResumeState(ResumeState state)
    {
        var path = Path.Combine(_resumeDir, $"{state.TaskId}.json");
        var tempPath = path + ".tmp";
        var json = JsonSerializer.Serialize(state, Ctx.ResumeState);
        File.WriteAllText(tempPath, json);
        if (File.Exists(path)) File.Delete(path);
        File.Move(tempPath, path);
    }

    public ResumeState? LoadResumeState(string taskId)
    {
        var path = Path.Combine(_resumeDir, $"{taskId}.json");
        if (!File.Exists(path)) return null;
        try
        {
            var json = File.ReadAllText(path);
            return JsonSerializer.Deserialize(json, Ctx.ResumeState);
        }
        catch { return null; }
    }

    public void DeleteResumeState(string taskId)
    {
        var path = Path.Combine(_resumeDir, $"{taskId}.json");
        if (File.Exists(path)) File.Delete(path);
    }

    public void Dispose() { }
}
