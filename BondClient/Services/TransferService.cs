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

    private CancellationTokenSource? _activeTransferCts;

    private const int DataBufferSize = 524288; // 512KB — max throughput for GbE LAN
    private const int TcpSocketBufferSize = 2097152; // 2MB TCP window
    private const int ConnectTimeoutMs = 5000;
    private const int ProgressIntervalMs = 200;
    private const int HeaderSize = 8; // int64 file length

    public event Action<TransferRequest, TcpClient>? IncomingRequest;
    public event Action<string>? TransferComplete;
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
        client.NoDelay = false; // Nagle ON for bulk — TCP coalesces into MSS-sized segments
        client.SendBufferSize = TcpSocketBufferSize;
        client.ReceiveBufferSize = TcpSocketBufferSize;
        client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
        client.Client.SetSocketOption(SocketOptionLevel.Tcp, (SocketOptionName)3, true); // TCP_NODELAY off during bulk
    }

    private static void ConfigureSocketForHandshake(TcpClient client)
    {
        client.NoDelay = true; // Nagle OFF for handshake — low latency for small control messages
        client.SendBufferSize = TcpSocketBufferSize;
        client.ReceiveBufferSize = TcpSocketBufferSize;
    }

    private async Task HandleTransferRequest(NetworkStream stream, TcpClient client, CancellationToken ct)
    {
        var jsonLen = BinaryPrimitives.ReadInt32BigEndian(await ReadBytes(stream, 4, ct));
        var json = Encoding.UTF8.GetString(await ReadBytes(stream, jsonLen, ct));
        var request = JsonSerializer.Deserialize<TransferRequest>(json);

        if (request == null) { await WriteResponse(stream, TransferResponse.Rejected); return; }

        if (!_password.HasPassword)
        {
            await WriteResponse(stream, TransferResponse.Approved);
            ConfigureSocketForBulk(client); // Switch to bulk mode after handshake
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

    private async Task ReceiveFiles(NetworkStream stream, List<FileEntry> files, long totalSize, CancellationToken ct)
    {
        var downloadDir = _password.DownloadPath;
        if (!Directory.Exists(downloadDir)) Directory.CreateDirectory(downloadDir);

        var progress = new TransferProgress
        {
            TotalFiles = Enumerable.Count(files, f => !f.IsDirectory),
            TotalBytes = totalSize,
            IsTransferring = true
        };
        TransferStarted?.Invoke();

        var speedWatch = Stopwatch.StartNew();
        long speedBytes = 0;
        int fileIndex = 0;
        string? currentFile = null;

        // Double-buffer: read into bufA while writing bufB to disk
        var bufA = new byte[DataBufferSize];
        var bufB = new byte[DataBufferSize];
        var readBuf = bufA;
        var writeBuf = bufB;

        try
        {
            for (int i = 0; i < files.Count; i++)
            {
                var entry = files[i];
                var fullPath = Path.Combine(downloadDir, entry.RelativePath);
                var dir = Path.GetDirectoryName(fullPath)!;
                if (!Directory.Exists(dir)) Directory.CreateDirectory(dir);

                if (entry.IsDirectory) continue;

                currentFile = fullPath;
                var fileLen = BinaryPrimitives.ReadInt64BigEndian(await ReadBytes(stream, 8, ct));
                long received = 0;

                progress.FileName = entry.RelativePath;
                progress.FileTotalBytes = fileLen;
                progress.FileBytesTransferred = 0;
                progress.FilesCompleted = fileIndex;

                await using var fs = new FileStream(fullPath, FileMode.Create, FileAccess.Write, FileShare.None, DataBufferSize);

                // First read
                var toRead = (int)Math.Min(readBuf.Length, fileLen - received);
                var bytesRead = await stream.ReadAsync(readBuf.AsMemory(0, toRead), ct);
                if (bytesRead == 0) throw new IOException("Connection closed");
                received += bytesRead;
                speedBytes += bytesRead;

                while (received < fileLen)
                {
                    ct.ThrowIfCancellationRequested();

                    // Swap buffers: current read becomes write, previous write becomes read
                    (readBuf, writeBuf) = (writeBuf, readBuf);

                    // Start network read into readBuf (parallel with disk write of writeBuf)
                    toRead = (int)Math.Min(readBuf.Length, fileLen - received);
                    var readTask = stream.ReadAsync(readBuf.AsMemory(0, toRead), ct);

                    // Write previous buffer to disk
                    await fs.WriteAsync(writeBuf.AsMemory(0, bytesRead), ct);

                    // Wait for network read
                    bytesRead = await readTask;
                    if (bytesRead == 0) throw new IOException("Connection closed");
                    received += bytesRead;
                    speedBytes += bytesRead;

                    progress.BytesTransferred = received;
                    progress.FileBytesTransferred = received;

                    if (speedWatch.ElapsedMilliseconds >= ProgressIntervalMs)
                    {
                        progress.Speed = speedBytes / (speedWatch.ElapsedMilliseconds / 1000.0);
                        speedBytes = 0;
                        speedWatch.Restart();
                        ProgressUpdated?.Invoke(progress);
                    }
                }

                // Write final chunk
                await fs.WriteAsync(readBuf.AsMemory(0, bytesRead), ct);
                await fs.FlushAsync(ct);
                currentFile = null;
                fileIndex++;
                progress.FilesCompleted = fileIndex;
                TransferComplete?.Invoke(entry.RelativePath);
            }

            progress.IsTransferring = false;
            progress.Speed = 0;
            ProgressUpdated?.Invoke(progress);
            TransferEnded?.Invoke();
        }
        catch
        {
            if (currentFile != null)
            {
                try { if (File.Exists(currentFile)) File.Delete(currentFile); } catch { }
            }
            throw;
        }
    }

    // ── Send side ──

    public async Task<TransferResponse> SendFiles(DeviceInfo target, string[] localPaths, string? password, CancellationToken ct)
    {
        _activeTransferCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var linkedCt = _activeTransferCts.Token;

        try
        {
            using var client = new TcpClient();
            ConfigureSocketForHandshake(client); // Low latency for handshake

            var connectTask = client.ConnectAsync(target.Ip, target.Port, linkedCt).AsTask();
            if (await Task.WhenAny(connectTask, Task.Delay(ConnectTimeoutMs, linkedCt)) != connectTask)
                throw new TimeoutException($"连接 {target.Name}({target.Ip}) 超时");

            await connectTask;
            using var stream = client.GetStream();

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

            var request = new TransferRequest
            {
                FromId = _password.DeviceId,
                FromName = _password.DeviceName,
                Files = entries,
                TotalSize = totalSize,
                Password = password
            };

            // Send request header
            stream.WriteByte(0x01);
            var json = JsonSerializer.Serialize(request);
            var jsonBytes = Encoding.UTF8.GetBytes(json);
            var lenBuf = new byte[4];
            BinaryPrimitives.WriteInt32BigEndian(lenBuf, jsonBytes.Length);
            await stream.WriteAsync(lenBuf, linkedCt);
            await stream.WriteAsync(jsonBytes, linkedCt);
            await stream.FlushAsync(linkedCt);

            var response = (TransferResponse)await ReadByte(stream, linkedCt);
            if (response != TransferResponse.Approved)
                return response;

            // Switch to bulk mode for data transfer
            ConfigureSocketForBulk(client);

            var progress = new TransferProgress
            {
                TotalFiles = Enumerable.Count(entries, e => !e.IsDirectory),
                TotalBytes = totalSize,
                IsTransferring = true
            };
            TransferStarted?.Invoke();

            var speedWatch = Stopwatch.StartNew();
            long speedBytes = 0;
            int fileIndex = 0;

            // Pre-allocated header+data buffer to avoid Nagle delay between header and first chunk
            var headerBuf = new byte[HeaderSize];
            var dataBuf = new byte[DataBufferSize];

            for (int i = 0; i < entries.Count; i++)
            {
                var entry = entries[i];
                if (entry.IsDirectory) continue;

                var localPath = FindLocalPath(localPaths, entry.RelativePath);
                if (localPath == null) continue;

                var fi = new FileInfo(localPath);

                progress.FileName = entry.RelativePath;
                progress.FileTotalBytes = fi.Length;
                progress.FileBytesTransferred = 0;
                progress.FilesCompleted = fileIndex;

                await using var fs = new FileStream(localPath, FileMode.Open, FileAccess.Read, FileShare.Read, DataBufferSize);
                long sent = 0;

                // Read first chunk from disk
                var toRead = (int)Math.Min(dataBuf.Length, fi.Length - sent);
                var read = await fs.ReadAsync(dataBuf.AsMemory(0, toRead), linkedCt);

                // Batch: write header (8 bytes) + first data chunk in one call
                // This avoids Nagle delay between header and data
                BinaryPrimitives.WriteInt64BigEndian(headerBuf, fi.Length);
                var combined = new byte[HeaderSize + read];
                headerBuf.CopyTo(combined, 0);
                dataBuf.AsSpan(0, read).CopyTo(combined.AsSpan(HeaderSize));
                await stream.WriteAsync(combined, linkedCt);
                sent += read;
                speedBytes += read;

                progress.BytesTransferred += read;
                progress.FileBytesTransferred = sent;

                // Stream remaining data
                while (sent < fi.Length)
                {
                    linkedCt.ThrowIfCancellationRequested();
                    toRead = (int)Math.Min(dataBuf.Length, fi.Length - sent);
                    read = await fs.ReadAsync(dataBuf.AsMemory(0, toRead), linkedCt);
                    if (read == 0) break;
                    await stream.WriteAsync(dataBuf.AsMemory(0, read), linkedCt);
                    sent += read;
                    speedBytes += read;

                    progress.BytesTransferred += read;
                    progress.FileBytesTransferred = sent;

                    if (speedWatch.ElapsedMilliseconds >= ProgressIntervalMs)
                    {
                        progress.Speed = speedBytes / (speedWatch.ElapsedMilliseconds / 1000.0);
                        speedBytes = 0;
                        speedWatch.Restart();
                        ProgressUpdated?.Invoke(progress);
                    }
                }

                await stream.FlushAsync(linkedCt);
                fileIndex++;
                progress.FilesCompleted = fileIndex;
                TransferComplete?.Invoke(entry.RelativePath);
            }

            progress.IsTransferring = false;
            progress.Speed = 0;
            ProgressUpdated?.Invoke(progress);
            TransferEnded?.Invoke();

            return TransferResponse.Approved;
        }
        catch (OperationCanceledException)
        {
            var p = new TransferProgress { IsTransferring = false, IsCancelled = true };
            ProgressUpdated?.Invoke(p);
            TransferEnded?.Invoke();
            return TransferResponse.Rejected;
        }
        catch (Exception ex)
        {
            TransferFailed?.Invoke(ex.Message);
            var p = new TransferProgress { IsTransferring = false };
            ProgressUpdated?.Invoke(p);
            TransferEnded?.Invoke();
            return TransferResponse.Rejected;
        }
        finally
        {
            _activeTransferCts?.Dispose();
            _activeTransferCts = null;
        }
    }

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
}
