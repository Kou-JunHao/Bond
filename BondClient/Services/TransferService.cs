using System;
using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using BondClient.Models;

namespace BondClient.Services;

public class TransferService : IDisposable
{
    private readonly PasswordManager _password;
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;

    private const int DataBufferSize = 1048576; // 1MB
    private const int TcpSocketBufferSize = 2097152; // 2MB TCP window
    private const int ConnectTimeoutMs = 5000;
    private const int ProgressIntervalMs = 200;

    // Parallel connection limits
    private const int MaxParallelConnections = 4;
    private const int MinFilesForParallel = 3;
    private const long MinSizeForParallel = 20 * 1024 * 1024; // 20MB

    public event Action<TransferRequest, TcpClient>? IncomingRequest;
    public event Action<string>? TransferComplete;
    public event Action<string>? ReceiveComplete;
    public event Action<string>? TransferFailed;
    public event Action<TransferProgress>? ProgressUpdated;
    public event Action? TransferStarted;
    public event Action? TransferEnded;

    public TransferService(PasswordManager password)
    {
        _password = password;
    }

    public void Start()
    {
        _cts = new CancellationTokenSource();
        _listener = new TcpListener(IPAddress.Any, _password.TransferPort);
        _listener.Start();
        _ = Task.Run(() => AcceptLoop(_cts.Token));
    }

    public void Stop()
    {
        _cts?.Cancel();
        _listener?.Stop();
    }

    public void CancelActiveTransfer()
    {
        _activeTransferCts?.Cancel();
    }

    private CancellationTokenSource? _activeTransferCts;

    private async Task AcceptLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var client = await _listener!.AcceptTcpClientAsync(ct);
                _ = Task.Run(() => HandleClient(client, ct));
            }
            catch (OperationCanceledException) { break; }
            catch { }
        }
    }

    private async Task HandleClient(TcpClient client, CancellationToken ct)
    {
        try
        {
            ConfigureSocketForBulk(client);
            using var stream = client.GetStream();

            var msgType = await ReadByte(stream, ct);

            switch (msgType)
            {
                case 0x01:
                    await HandleTransferRequest(stream, client, ct);
                    break;
            }
        }
        catch { }
        finally
        {
            client.Dispose();
        }
    }

    private static void ConfigureSocketForBulk(TcpClient client)
    {
        client.NoDelay = false;
        client.SendBufferSize = TcpSocketBufferSize;
        client.ReceiveBufferSize = TcpSocketBufferSize;
        client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
    }

    private static void ConfigureSocketForHandshake(TcpClient client)
    {
        client.NoDelay = true;
        client.SendBufferSize = TcpSocketBufferSize;
        client.ReceiveBufferSize = TcpSocketBufferSize;
    }

    private async Task HandleTransferRequest(NetworkStream stream, TcpClient client, CancellationToken ct)
    {
        var jsonLen = BinaryPrimitives.ReadInt32BigEndian(await ReadBytes(stream, 4, ct));
        var json = Encoding.UTF8.GetString(await ReadBytes(stream, jsonLen, ct));
        var request = JsonSerializer.Deserialize(json, BondJsonContext.Default.TransferRequest);

        if (request == null) { await WriteResponse(stream, TransferResponse.Rejected); return; }

        if (!_password.HasPassword)
        {
            await WriteResponse(stream, TransferResponse.Approved);
            ConfigureSocketForBulk(client);
            await ReceiveFiles(stream, request.Files, request.TotalSize, ct);
            return;
        }

        if (!string.IsNullOrEmpty(request.Password) && _password.Verify(request.Password))
        {
            await WriteResponse(stream, TransferResponse.Approved);
            ConfigureSocketForBulk(client);
            await ReceiveFiles(stream, request.Files, request.TotalSize, ct);
            return;
        }

        if (!string.IsNullOrEmpty(request.Password) && !_password.Verify(request.Password))
        {
            await WriteResponse(stream, TransferResponse.WrongPassword);
            return;
        }

        if (_password.AutoApprove)
        {
            await WriteResponse(stream, TransferResponse.Approved);
            ConfigureSocketForBulk(client);
            await ReceiveFiles(stream, request.Files, request.TotalSize, ct);
            return;
        }

        var tcs = new TaskCompletionSource<TransferResponse>();
        _pendingResponses[request.FromId] = tcs;
        IncomingRequest?.Invoke(request, client);

        var response = await tcs.Task;
        _pendingResponses.TryRemove(request.FromId, out _);

        await WriteResponse(stream, response);

        if (response == TransferResponse.Approved)
        {
            ConfigureSocketForBulk(client);
            await ReceiveFiles(stream, request.Files, request.TotalSize, ct);
        }
    }

    private static async Task WriteResponse(NetworkStream stream, TransferResponse response)
    {
        stream.WriteByte((byte)response);
        await stream.FlushAsync();
    }

    public void RespondToRequest(string fromId, TransferResponse response)
    {
        if (_pendingResponses.TryGetValue(fromId, out var tcs))
            tcs.TrySetResult(response);
    }

    private readonly ConcurrentDictionary<string, TaskCompletionSource<TransferResponse>>
        _pendingResponses = new();

    // ???????????????????????????????????????????????????
    //  Receive ? unchanged, already concurrent-safe
    // ???????????????????????????????????????????????????

    private async Task ReceiveFiles(NetworkStream stream, List<FileEntry> files, long totalSize, CancellationToken ct)
    {
        var downloadDir = _password.DownloadPath;
        if (!Directory.Exists(downloadDir)) Directory.CreateDirectory(downloadDir);

        var dataFiles = files.Where(f => !f.IsDirectory).ToList();

        var progress = new TransferProgress
        {
            TotalFiles = dataFiles.Count,
            TotalBytes = totalSize,
            IsTransferring = true
        };
        TransferStarted?.Invoke();

        var speedWatch = Stopwatch.StartNew();
        long speedBytes = 0;

        var bufA = new byte[DataBufferSize];
        var bufB = new byte[DataBufferSize];
        var readBuf = bufA;
        var writeBuf = bufB;
        int bytesRead = 0;

        try
        {
            foreach (var entry in files.Where(f => f.IsDirectory))
            {
                var dirPath = Path.Combine(downloadDir, entry.RelativePath);
                if (!Directory.Exists(dirPath)) Directory.CreateDirectory(dirPath);
            }

            for (int i = 0; i < dataFiles.Count; i++)
            {
                var entry = dataFiles[i];
                var fullPath = Path.Combine(downloadDir, entry.RelativePath);
                var dir = Path.GetDirectoryName(fullPath)!;
                if (!Directory.Exists(dir)) Directory.CreateDirectory(dir);

                var fileLen = entry.Size;
                long received = 0;

                progress.FileName = entry.RelativePath;
                progress.FileTotalBytes = fileLen;
                progress.FileBytesTransferred = 0;
                progress.FilesCompleted = i;

                await using var fs = new FileStream(fullPath, FileMode.Create, FileAccess.Write, FileShare.None, DataBufferSize);

                var toRead = (int)Math.Min(readBuf.Length, fileLen - received);
                bytesRead = await stream.ReadAsync(readBuf.AsMemory(0, toRead), ct);
                if (bytesRead == 0) throw new IOException("Connection closed");
                received += bytesRead;
                speedBytes += bytesRead;

                while (received < fileLen)
                {
                    ct.ThrowIfCancellationRequested();
                    (readBuf, writeBuf) = (writeBuf, readBuf);
                    toRead = (int)Math.Min(readBuf.Length, fileLen - received);
                    var readTask = stream.ReadAsync(readBuf.AsMemory(0, toRead), ct);
                    await fs.WriteAsync(writeBuf.AsMemory(0, bytesRead), ct);
                    bytesRead = await readTask;
                    if (bytesRead == 0) throw new IOException("Connection closed");
                    received += bytesRead;
                    speedBytes += bytesRead;

                    progress.BytesTransferred += bytesRead;
                    progress.FileBytesTransferred = received;

                    if (speedWatch.ElapsedMilliseconds >= ProgressIntervalMs)
                    {
                        progress.Speed = speedBytes / (speedWatch.ElapsedMilliseconds / 1000.0);
                        speedBytes = 0;
                        speedWatch.Restart();
                        ProgressUpdated?.Invoke(progress);
                    }
                }

                await fs.WriteAsync(readBuf.AsMemory(0, bytesRead), ct);
                progress.FilesCompleted = i + 1;
                TransferComplete?.Invoke(entry.RelativePath);
                ReceiveComplete?.Invoke(entry.RelativePath);
            }

            progress.IsTransferring = false;
            progress.Speed = 0;
            ProgressUpdated?.Invoke(progress);
            TransferEnded?.Invoke();
        }
        catch
        {
            try
            {
                var idx = Math.Min(progress.FilesCompleted, dataFiles.Count - 1);
                if (idx >= 0)
                {
                    var f = Path.Combine(downloadDir, dataFiles[idx].RelativePath);
                    if (File.Exists(f)) File.Delete(f);
                }
            }
            catch { }
            throw;
        }
    }

    // ???????????????????????????????????????????????????
    //  Send ? parallel connections
    // ???????????????????????????????????????????????????

    public async Task<TransferResponse> SendFiles(DeviceInfo target, string[] localPaths, string? password, CancellationToken ct)
    {
        _activeTransferCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var linkedCt = _activeTransferCts.Token;

        try
        {
            // Collect all file entries
            var entries = new List<FileEntry>();
            long totalSize = 0;
            foreach (var path in localPaths)
            {
                if (File.Exists(path))
                {
                    var fi = new FileInfo(path);
                    entries.Add(new FileEntry { RelativePath = fi.Name, Size = fi.Length, IsDirectory = false });
                    totalSize += fi.Length;
                }
                else if (Directory.Exists(path))
                {
                    var di = new DirectoryInfo(path);
                    CollectEntries(di, di.FullName, entries, ref totalSize);
                }
            }

            var dataEntries = entries.Where(e => !e.IsDirectory).ToList();

            // Determine parallelism
            int connCount = CalcConnectionCount(dataEntries.Count, totalSize);

            // Shared state for progress aggregation
            var shared = new TransferSharedState();
            shared.TotalFiles = dataEntries.Count;
            shared.TotalBytes = totalSize;
            shared.SpeedWatch = Stopwatch.StartNew();

            var progress = new TransferProgress
            {
                TotalFiles = dataEntries.Count,
                TotalBytes = totalSize,
                IsTransferring = true
            };
            TransferStarted?.Invoke();

            // Split files into groups
            var groups = SplitIntoGroups(dataEntries, connCount);

            // Build request template
            var requestTemplate = new TransferRequest
            {
                FromId = _password.DeviceId,
                FromName = _password.DeviceName,
                Files = entries,
                TotalSize = totalSize,
                Password = password
            };

            // Launch parallel connections
            var tasks = new List<Task<bool>>();
            for (int g = 0; g < groups.Count; g++)
            {
                var group = groups[g];
                tasks.Add(Task.Run(() =>
                    SendGroup(target, requestTemplate, group, localPaths,
                        progress, shared, linkedCt), linkedCt));
            }

            var results = await Task.WhenAll(tasks);
            var allOk = results.All(r => r);

            progress.IsTransferring = false;
            progress.Speed = 0;
            ProgressUpdated?.Invoke(progress);
            TransferEnded?.Invoke();

            return allOk ? TransferResponse.Approved : TransferResponse.Rejected;
        }
        catch (OperationCanceledException)
        {
            ProgressUpdated?.Invoke(new TransferProgress { IsTransferring = false, IsCancelled = true });
            TransferEnded?.Invoke();
            return TransferResponse.Rejected;
        }
        catch (Exception ex)
        {
            TransferFailed?.Invoke(ex.Message);
            ProgressUpdated?.Invoke(new TransferProgress { IsTransferring = false });
            TransferEnded?.Invoke();
            return TransferResponse.Rejected;
        }
        finally
        {
            _activeTransferCts?.Dispose();
            _activeTransferCts = null;
        }
    }

    private async Task<bool> SendGroup(
        DeviceInfo target,
        TransferRequest requestTemplate,
        List<FileEntry> groupFiles,
        string[] localPaths,
        TransferProgress sharedProgress,
        TransferSharedState shared,
        CancellationToken ct)
    {
        try
        {
            using var client = new TcpClient();
            ConfigureSocketForHandshake(client);

            var connectTask = client.ConnectAsync(target.Ip, target.Port, ct).AsTask();
            if (await Task.WhenAny(connectTask, Task.Delay(ConnectTimeoutMs, ct)) != connectTask)
                throw new TimeoutException($"?? {target.Name}({target.Ip}) ??");

            await connectTask;
            using var stream = client.GetStream();

            // Send handshake with this group's subset
            var groupRequest = new TransferRequest
            {
                FromId = requestTemplate.FromId,
                FromName = requestTemplate.FromName,
                Files = requestTemplate.Files, // full file list for receiver to know all files
                TotalSize = requestTemplate.TotalSize,
                Password = requestTemplate.Password
            };

            stream.WriteByte(0x01);
            var json = JsonSerializer.Serialize(groupRequest, BondJsonContext.Default.TransferRequest);
            var jsonBytes = Encoding.UTF8.GetBytes(json);
            var lenBuf = new byte[4];
            BinaryPrimitives.WriteInt32BigEndian(lenBuf, jsonBytes.Length);
            await stream.WriteAsync(lenBuf, ct);
            await stream.WriteAsync(jsonBytes, ct);
            await stream.FlushAsync(ct);

            var response = (TransferResponse)await ReadByte(stream, ct);
            if (response != TransferResponse.Approved)
                return false;

            ConfigureSocketForBulk(client);

            var dataBuf = new byte[DataBufferSize];

            for (int i = 0; i < groupFiles.Count; i++)
            {
                var entry = groupFiles[i];
                var localPath = FindLocalPath(localPaths, entry.RelativePath);
                if (localPath == null) continue;

                var fi = new FileInfo(localPath);
                await using var fs = new FileStream(localPath, FileMode.Open, FileAccess.Read, FileShare.Read, DataBufferSize);
                long sent = 0;

                while (sent < fi.Length)
                {
                    ct.ThrowIfCancellationRequested();
                    var toRead = (int)Math.Min(dataBuf.Length, fi.Length - sent);
                    var read = await fs.ReadAsync(dataBuf.AsMemory(0, toRead), ct);
                    if (read == 0) break;
                    await stream.WriteAsync(dataBuf.AsMemory(0, read), ct);
                    sent += read;

                    Interlocked.Add(ref shared.GlobalBytesSent, read);
                    Interlocked.Add(ref shared.SpeedBytes, read);

                    sharedProgress.BytesTransferred = Interlocked.Read(ref shared.GlobalBytesSent);
                    sharedProgress.FileBytesTransferred = sent;

                    if (shared.SpeedWatch.ElapsedMilliseconds >= ProgressIntervalMs)
                    {
                        var sb = Interlocked.Exchange(ref shared.SpeedBytes, 0);
                        sharedProgress.Speed = sb / (shared.SpeedWatch.ElapsedMilliseconds / 1000.0);
                        shared.SpeedWatch.Restart();
                        ProgressUpdated?.Invoke(sharedProgress);
                    }
                }

                var completed = Interlocked.Increment(ref shared.GlobalFilesCompleted);
                sharedProgress.FilesCompleted = completed;
                sharedProgress.FileName = entry.RelativePath;
                TransferComplete?.Invoke(entry.RelativePath);
            }

            await stream.FlushAsync(ct);
            return true;
        }
        catch (Exception ex)
        {
            TransferFailed?.Invoke(ex.Message);
            return false;
        }
    }

    private static int CalcConnectionCount(int fileCount, long totalSize)
    {
        if (fileCount < MinFilesForParallel && totalSize < MinSizeForParallel)
            return 1;

        // Heuristic: 1 connection per ~100MB or ~20 files, capped at MaxParallelConnections
        int bySize = (int)Math.Ceiling(totalSize / (100.0 * 1024 * 1024));
        int byCount = (int)Math.Ceiling(fileCount / 20.0);
        return Math.Clamp(Math.Max(bySize, byCount), 1, MaxParallelConnections);
    }

    private static List<List<FileEntry>> SplitIntoGroups(List<FileEntry> files, int groupCount)
    {
        var groups = new List<List<FileEntry>>();
        for (int i = 0; i < groupCount; i++)
            groups.Add(new List<FileEntry>());

        // Distribute files round-robin (preserves ordering for progress display)
        for (int i = 0; i < files.Count; i++)
            groups[i % groupCount].Add(files[i]);

        return groups;
    }

    // ?? Helpers ??

    private static void CollectEntries(DirectoryInfo root, string basePath, List<FileEntry> entries, ref long totalSize)
    {
        entries.Add(new FileEntry
        {
            RelativePath = Path.GetRelativePath(basePath, root.FullName),
            IsDirectory = true
        });

        foreach (var file in root.GetFiles())
        {
            entries.Add(new FileEntry
            {
                RelativePath = Path.GetRelativePath(basePath, file.FullName),
                Size = file.Length,
                IsDirectory = false
            });
            totalSize += file.Length;
        }

        foreach (var dir in root.GetDirectories())
            CollectEntries(dir, basePath, entries, ref totalSize);
    }

    private static string? FindLocalPath(string[] roots, string relativePath)
    {
        foreach (var root in roots)
        {
            if (File.Exists(root) && Path.GetFileName(root) == relativePath)
                return root;
            if (Directory.Exists(root))
            {
                var full = Path.Combine(root, relativePath);
                if (File.Exists(full)) return full;
            }
        }
        return null;
    }

    private static async Task<byte> ReadByte(NetworkStream stream, CancellationToken ct)
    {
        var buf = new byte[1];
        int read = 0;
        while (read < 1)
        {
            var r = await stream.ReadAsync(buf.AsMemory(read, 1 - read), ct);
            if (r == 0) throw new IOException("Connection closed");
            read += r;
        }
        return buf[0];
    }

    private static async Task<byte[]> ReadBytes(NetworkStream stream, int count, CancellationToken ct)
    {
        var buf = new byte[count];
        int read = 0;
        while (read < count)
        {
            var r = await stream.ReadAsync(buf.AsMemory(read, count - read), ct);
            if (r == 0) throw new IOException("Connection closed");
            read += r;
        }
        return buf;
    }

    public void Dispose()
    {
        Stop();
        _listener?.Stop();
        _cts?.Dispose();
        _activeTransferCts?.Dispose();
    }

    private class TransferSharedState
    {
        public long GlobalBytesSent;
        public int GlobalFilesCompleted;
        public long SpeedBytes;
        public int TotalFiles;
        public long TotalBytes;
        public Stopwatch SpeedWatch = null!;
    }
}
