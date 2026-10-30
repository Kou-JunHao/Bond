using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Threading;
using Bond.Models;
using Bond.Services.Api;

namespace Bond.Services;

/// <summary>
/// 上传队列实现（task-42）：秒传 → 分片上传（v7 /api/files/uploads，可续传）→ 单文件直传兜底。
/// 进度只来自本机真实读盘/网络字节：SHA-256 计算与分片写入都逐块上报。
/// </summary>
public sealed partial class HttpBondStore : IUploadService
{
    /// <summary>超过该大小走分片（后端 v7 建议 8MB 以上分片）。</summary>
    private const long ChunkThreshold = 8L * 1024 * 1024;

    private const int ChunkSizeFallback = 4 * 1024 * 1024;

    /// <summary>团队工作区的分片大小：显式传给后端（后端默认 5MB，不传就无法按已收分片续传/对齐）。</summary>
    private const int WorkspaceChunkSize = 8 * 1024 * 1024;

    /// <summary>并发上传的文件数（分片串行，文件并行 2 个）。</summary>
    private const int UploadConcurrency = 2;

    private readonly ObservableCollection<UploadTaskItem> _uploads = [];
    private readonly SemaphoreSlim _uploadSlots = new(UploadConcurrency, UploadConcurrency);
    private readonly ConcurrentDictionary<string, CancellationTokenSource> _uploadCts = new();

    private bool _uploadsLoaded;

    private static readonly string UploadsFile = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Bond", "uploads.json");

    /// <summary>
    /// 别的账号的未完成任务暂存文件：uploads.{ownerId}.json。
    /// 账号切换时从 uploads.json 挪到这里，等原账号回来再恢复（task-67）。
    /// </summary>
    private static string UploadArchiveFile(long ownerId) => Path.Combine(
        Path.GetDirectoryName(UploadsFile)!,
        $"uploads.{ownerId}.json");

    public ObservableCollection<UploadTaskItem> Uploads
    {
        get
        {
            EnsureUploadsLoaded();
            return _uploads;
        }
    }

    public event EventHandler? UploadsChanged;

    // ─────────────────────────────────────────────────────────────
    // 对外接口
    // ─────────────────────────────────────────────────────────────
    public void Enqueue(IReadOnlyList<string> localPaths)
    {
        try
        {
            Bond.Services.Api.UploadDiag.Log($"enqueue count={localPaths.Count} path0={(localPaths.Count > 0 ? localPaths[0] : "-")}");
            EnsureUploadsLoaded();
            Bond.Services.Api.UploadDiag.Log("enqueue ensure-loaded ok");

            var parent = CurrentPath;
            foreach (var path in localPaths)
            {
                if (string.IsNullOrWhiteSpace(path) || !File.Exists(path))
                {
                    Bond.Services.Api.UploadDiag.Log($"enqueue skip missing={path}");
                    continue;
                }

                // 同一文件同一目录已有未结束任务时不重复排队
                if (_uploads.Any(t => t.ParentPath == parent
                                     && string.Equals(t.LocalPath, path, StringComparison.OrdinalIgnoreCase)
                                     && t.Status is UploadTaskStatus.Queued or UploadTaskStatus.Hashing
                                         or UploadTaskStatus.Uploading or UploadTaskStatus.Paused
                                         or UploadTaskStatus.Failed))
                {
                    Bond.Services.Api.UploadDiag.Log("enqueue skip duplicated");
                    continue;
                }

                var task = new UploadTaskItem(path, parent)
                {
                    OwnerUserId = ResolveUploadOwnerUserId()
                };
                _uploads.Add(task);
                Bond.Services.Api.UploadDiag.Log($"enqueue added {task.FileName}, start process");
                _ = ProcessAsync(task);
            }

            Persist();
            RaiseUploadsChanged();
        }
        catch (Exception ex)
        {
            Bond.Services.Api.UploadDiag.Log($"enqueue EXC {ex.GetType().Name}: {ex.Message} | {ex.StackTrace}");
        }
    }

    public void Cancel(UploadTaskItem task)
    {
        if (task is null) return;

        if (_uploadCts.TryGetValue(task.Key, out var cts))
        {
            try { cts.Cancel(); } catch { /* 已释放 */ }
            _uploadCts.TryRemove(task.Key, out _);
        }

        // 正在分片上传的会话在服务端 abort，避免占着临时空间
        // （task-158：团队工作区分片没有 abort 端点，跳过，别发一个必然 404/405 的请求）
        if (IsPersonalSpace && !string.IsNullOrEmpty(task.UploadId))
        {
            var uploadId = task.UploadId!;
            _ = _api.AbortUploadAsync(FilesBase, uploadId);
        }

        Ui(() =>
        {
            if (task.IsActive || task.Status == UploadTaskStatus.Queued)
            {
                task.SetStatus(UploadTaskStatus.Cancelled);
            }
        });

        Persist();
        RaiseUploadsChanged();
    }

    public Task ResumeAsync(UploadTaskItem task)
    {
        if (task is null || task.IsActive) return Task.CompletedTask;

        EnsureUploadsLoaded();

        // 源文件已被移动/删除时，任务没有任何恢复可能：直接丢弃，不再显示「0 B 可续传」。
        if (!File.Exists(task.LocalPath))
        {
            _uploads.Remove(task);
            Persist();
            RaiseUploadsChanged();
            return Task.CompletedTask;
        }

        Ui(() => task.SetStatus(UploadTaskStatus.Queued));
        Persist();
        RaiseUploadsChanged();
        return ProcessAsync(task);
    }

    public void Remove(UploadTaskItem task)
    {
        if (task is null || task.IsActive) return;

        _uploads.Remove(task);
        Persist();
        RaiseUploadsChanged();
    }

    // ─────────────────────────────────────────────────────────────
    // 上传主流程
    // ─────────────────────────────────────────────────────────────
    private async Task ProcessAsync(UploadTaskItem task)
    {
        if (_uploadCts.ContainsKey(task.Key)) return;

        var cts = new CancellationTokenSource();
        _uploadCts[task.Key] = cts;

        try
        {
            Bond.Services.Api.UploadDiag.Log($"process start key={task.Key} file={task.FileName} size={task.TotalBytes}");
            await _uploadSlots.WaitAsync(cts.Token).ConfigureAwait(false);
            Bond.Services.Api.UploadDiag.Log("process slot acquired");

            // 防御：入队后源文件被删/被移走，直接判失败而不是显示可继续的假任务。
            if (!File.Exists(task.LocalPath))
            {
                Ui(() => task.SetStatus(UploadTaskStatus.Failed, "源文件已丢失"));
                return;
            }

            Ui(() => task.SetStatus(UploadTaskStatus.Hashing, null));
            var (hash, size) = await HashFileAsync(task, cts.Token).ConfigureAwait(false);
            Bond.Services.Api.UploadDiag.Log($"hash={hash} size={size} path={task.LocalPath}");
            task.TotalBytes = size;
            Ui(() => { task.UpdateSent(0); task.UpdateSpeed(0); });

            // 1) 秒传：命中即完成（后端不传流量、按体积计配额）
            if (await TryInstantAsync(task, hash, cts.Token).ConfigureAwait(false))
            {
                Ui(() =>
                {
                    task.UpdateSent(task.TotalBytes);
                    task.SetStatus(UploadTaskStatus.Completed);
                });
                await RefreshFilesQuietAsync().ConfigureAwait(false);
                return;
            }

            Ui(() => task.SetStatus(UploadTaskStatus.Uploading, null));

            // 2) 大文件分片（可续传）；小文件直传
            if (size > ChunkThreshold)
            {
                await UploadChunkedAsync(task, hash, cts.Token).ConfigureAwait(false);
            }
            else
            {
                await UploadSingleAsync(task, cts.Token).ConfigureAwait(false);
            }

            Ui(() =>
            {
                task.UpdateSent(task.TotalBytes);
                task.UpdateSpeed(0);
                task.SetStatus(UploadTaskStatus.Completed);
            });

            await RefreshFilesQuietAsync().ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            Ui(() => task.SetStatus(UploadTaskStatus.Cancelled));
        }
        catch (Exception ex)
        {
            Bond.Services.Api.UploadDiag.Log($"process EXC {ex.GetType().Name}: {ex.Message} | {ex.StackTrace}");
            Ui(() =>
            {
                task.UpdateSpeed(0);
                task.SetStatus(UploadTaskStatus.Failed, ex.Message);
            });
        }
        finally
        {
            _uploadCts.TryRemove(task.Key, out _);
            _uploadSlots.Release();
            Persist();
            RaiseUploadsChanged();
        }
    }

    /// <summary>流式计算 SHA-256（每秒上报一次速度），返回小写 hex 与文件大小。</summary>
    private async Task<(string Hash, long Size)> HashFileAsync(UploadTaskItem task, CancellationToken ct)
    {
        await using var stream = new FileStream(task.LocalPath, FileMode.Open, FileAccess.Read,
            FileShare.Read, 1 << 20, FileOptions.Asynchronous | FileOptions.SequentialScan);

        using var hasher = SHA256.Create();
        var buffer = new byte[1 << 20];
        long done = 0;
        long lastBytes = 0;
        var sw = Stopwatch.StartNew();
        var lastTick = sw.Elapsed.TotalSeconds;

        int read;
        while ((read = await stream.ReadAsync(buffer, ct).ConfigureAwait(false)) > 0)
        {
            hasher.TransformBlock(buffer, 0, read, null, 0);
            done += read;

            var now = sw.Elapsed.TotalSeconds;
            if (now - lastTick >= 0.4)
            {
                var speed = (done - lastBytes) / (now - lastTick);
                lastBytes = done;
                lastTick = now;
                Ui(() => { task.UpdateSent(done); task.UpdateSpeed(speed); });
            }
        }

        hasher.TransformFinalBlock(buffer, 0, 0);
        var hash = Convert.ToHexString(hasher.Hash ?? Array.Empty<byte>()).ToLowerInvariant();
        return (hash, done);
    }

    private async Task<bool> TryInstantAsync(UploadTaskItem task, string hash, CancellationToken ct)
    {
        var result = await _api
            .InstantUploadAsync(_currentSpace?.WorkspaceId, task.ParentPath, task.FileName, hash,
                task.TotalBytes, ContentTypeFor(task.FileName))
            .ConfigureAwait(false);

        if (result?.Data?.Instant == true) return true;

        // 404 是未命中秒传的正常分支（回退普通上传）；其余错误也回退，但记录原因便于排查
        if (result is not null && result.Code is not (200 or 404))
        {
            Console.Error.WriteLine($"[Bond] 秒传探测返回 {result.Code}: {result.Message}");
        }

        return false;
    }

    private async Task UploadChunkedAsync(UploadTaskItem task, string hash, CancellationToken ct)
    {
        // task-158：filesBase/空间在开传时固化，中途切空间或目录不会把后续分片发到别的端点。
        var filesBase = FilesBase;

        // 分片会话：个人空间走 v7 /uploads（回 chunkSize + 已传分片）；
        // 团队工作区走 /files/chunks（只回 uploadId，可用持久化的 UploadId 续传）。
        var session = await OpenChunkTransferAsync(filesBase, task, hash).ConfigureAwait(false);

        var chunkSize = session.ChunkSize;
        var totalChunks = session.TotalChunks;
        var already = session.UploadedChunks;
        task.UploadId = session.UploadId;

        // 已收字节按「每片期望大小」累计（末片可能小于 chunkSize，不能用片数 × chunkSize）
        long received = 0;
        foreach (var index in already)
        {
            var offset = (long)index * chunkSize;
            if (offset < task.TotalBytes) received += Math.Min(chunkSize, task.TotalBytes - offset);
        }
        Ui(() => { task.UpdateSent(received); task.SetStatus(UploadTaskStatus.Uploading, null); });
        task.UpdateSpeed(0);

        Persist();

        var sw = Stopwatch.StartNew();
        var lastTick = 0.0;
        var lastBytes = received;

        await using var stream = new FileStream(task.LocalPath, FileMode.Open, FileAccess.Read,
            FileShare.Read, chunkSize, FileOptions.Asynchronous | FileOptions.SequentialScan);

        for (var index = 0; index < totalChunks; index++)
        {
            ct.ThrowIfCancellationRequested();
            if (already.Contains(index)) continue;

            var offset = (long)index * chunkSize;
            var length = (int)Math.Min(chunkSize, task.TotalBytes - offset);
            if (length <= 0) break;

            var buffer = new byte[length];
            stream.Seek(offset, SeekOrigin.Begin);
            var readTotal = 0;
            while (readTotal < length)
            {
                var n = await stream.ReadAsync(buffer.AsMemory(readTotal, length - readTotal), ct)
                    .ConfigureAwait(false);
                if (n <= 0) throw new IOException("读取本地文件失败（文件可能被移动/截断）");
                readTotal += n;
            }

            // task-151：分片重试（4 次 / 400-800-1600ms 指数退避）统一走 RetryPolicy.ChunkUpload，网络抖动/401 刷新都覆盖
            Bond.Services.Api.UploadDiag.Log(
                $"stream check: idx={index} offset={offset} len={length} read={readTotal} pos_after_read={stream.Position} sent={received}");
            var chunkRule = RetryPolicy.For(RetryScenario.ChunkUpload);
            var ok = false;
            for (var attempt = 0; attempt < chunkRule.MaxAttempts && !ok; attempt++)
            {
                ct.ThrowIfCancellationRequested();
                var result = await session.PutChunk(index, buffer, ct)
                    .ConfigureAwait(false);
                if (result is not null && result.Code is 200 or 201 or 204)
                {
                    ok = true;
                    break;
                }

                if (attempt < chunkRule.MaxAttempts - 1)
                {
                    await Task.Delay(chunkRule.Delays[attempt], ct).ConfigureAwait(false);
                }
            }

            if (!ok) throw new IOException($"分片 {index + 1}/{totalChunks} 上传失败，请重试");

            received += readTotal;
            var now = sw.Elapsed.TotalSeconds;
            if (now - lastTick >= 0.4)
            {
                var speed = (received - lastBytes) / Math.Max(0.001, now - lastTick);
                lastBytes = received;
                lastTick = now;
                var value = received;
                Ui(() => { task.UpdateSent(value); task.UpdateSpeed(speed); });
                Persist();
            }
            else
            {
                var value = received;
                Ui(() => task.UpdateSent(value));
            }
        }

        var complete = await session.Complete().ConfigureAwait(false);
        Bond.Services.Api.UploadDiag.Log($"complete uploadId={session.UploadId} code={complete?.Code} msg={complete?.Message}");
        if (complete?.Data is null)
        {
            throw new InvalidOperationException(complete?.Message ?? "合并分片失败");
        }

        task.UploadId = null;
    }

    /// <summary>分片会话的差异封装：个人空间（v7 /uploads）与团队工作区（/files/chunks）契约不同。</summary>
    private sealed class ChunkTransfer
    {
        public required string UploadId { get; init; }
        public required int ChunkSize { get; init; }
        public required int TotalChunks { get; init; }
        public required HashSet<int> UploadedChunks { get; init; }
        public required Func<int, byte[], CancellationToken, Task<ApiResult?>> PutChunk { get; init; }
        public required Func<Task<ApiResult<WorkspaceFileDto>?>> Complete { get; init; }
    }

    /// <summary>
    /// 打开分片会话（task-158）：
    /// 个人空间 → POST {filesBase}/uploads，回 {uploadId, chunkSize, totalChunks, uploadedChunks}；
    /// 团队工作区 → POST {filesBase}/chunks/init，只回 {uploadId}（hash 命中为 "instant:&lt;id&gt;"），
    /// 分片大小由客户端显式指定，续传时带持久化的 UploadId 并用 /status 对齐已传分片。
    /// </summary>
    private async Task<ChunkTransfer> OpenChunkTransferAsync(string filesBase, UploadTaskItem task, string hash)
    {
        var contentType = ContentTypeFor(task.FileName);

        if (IsPersonalSpace)
        {
            var init = await _api
                .InitUploadAsync(filesBase, null, task.ParentPath, task.FileName, task.TotalBytes, contentType, hash)
                .ConfigureAwait(false);

            var data = init?.Data;
            if (data is null || string.IsNullOrEmpty(data.UploadId))
            {
                throw new InvalidOperationException(init?.Message ?? "无法创建上传会话");
            }

            var chunkSize = data.ChunkSize > 0 ? data.ChunkSize : ChunkSizeFallback;
            return new ChunkTransfer
            {
                UploadId = data.UploadId,
                ChunkSize = chunkSize,
                TotalChunks = data.TotalChunks > 0
                    ? data.TotalChunks
                    : (int)Math.Ceiling(task.TotalBytes / (double)chunkSize),
                UploadedChunks = new HashSet<int>(data.UploadedChunks),
                PutChunk = (index, buffer, ct) => _api.PutChunkAsync(filesBase, data.UploadId, index, buffer, ct),
                Complete = () => _api.CompleteUploadAsync(filesBase, data.UploadId)
            };
        }

        var wsInit = await _api
            .InitWorkspaceChunkUploadAsync(filesBase, task.ParentPath, task.FileName, task.TotalBytes,
                contentType, hash, WorkspaceChunkSize, task.UploadId)
            .ConfigureAwait(false);

        var wsData = wsInit?.Data;
        if (wsData is null || string.IsNullOrEmpty(wsData.UploadId))
        {
            throw new InvalidOperationException(wsInit?.Message ?? "无法创建上传会话");
        }

        var uploadId = wsData.UploadId;
        var actualChunkSize = WorkspaceChunkSize;
        var uploaded = new HashSet<int>();

        // 秒传命中：服务端已有同 hash 文件，直接 complete 落库（instant: 前缀由后端识别），不上传分片。
        if (uploadId.StartsWith("instant:", StringComparison.Ordinal))
        {
            return new ChunkTransfer
            {
                UploadId = uploadId,
                ChunkSize = actualChunkSize,
                TotalChunks = 0,
                UploadedChunks = uploaded,
                PutChunk = (_, _, _) => Task.FromResult<ApiResult?>(null),
                Complete = () => _api.CompleteWorkspaceChunkUploadAsync(filesBase, uploadId)
            };
        }

        // 续传/新会话都以服务端为准（服务端可能按配置改写 chunkSize）
        var status = await _api.GetWorkspaceChunkStatusAsync(filesBase, uploadId).ConfigureAwait(false);
        if (status?.Data is { } st)
        {
            if (st.ChunkSize > 0) actualChunkSize = st.ChunkSize;
            foreach (var index in st.UploadedChunks) uploaded.Add(index);
        }

        return new ChunkTransfer
        {
            UploadId = uploadId,
            ChunkSize = actualChunkSize,
            TotalChunks = (int)Math.Ceiling(task.TotalBytes / (double)actualChunkSize),
            UploadedChunks = uploaded,
            PutChunk = (index, buffer, ct) => _api.PutWorkspaceChunkAsync(filesBase, uploadId, index, buffer, ct),
            Complete = () => _api.CompleteWorkspaceChunkUploadAsync(filesBase, uploadId)
        };
    }

    private async Task UploadSingleAsync(UploadTaskItem task, CancellationToken ct)
    {
        await using var stream = new FileStream(task.LocalPath, FileMode.Open, FileAccess.Read,
            FileShare.Read, 64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);

        var progress = new Progress<double>(p =>
        {
            var sent = (long)Math.Clamp(p * task.TotalBytes, 0, task.TotalBytes);
            Ui(() => task.UpdateSent(sent));
        });

        using var form = new System.Net.Http.MultipartFormDataContent();
        form.Add(new System.Net.Http.StringContent(task.ParentPath), "parentPath");

        var fileContent = new ProgressStreamContent(stream, task.TotalBytes, progress, ct);
        fileContent.Headers.ContentType =
            new System.Net.Http.Headers.MediaTypeHeaderValue(ContentTypeFor(task.FileName));
        form.Add(fileContent, "file", task.FileName);

        var result = await _api
            .PostMultipartAsync<WorkspaceFileDto>($"{FilesBase}/upload", form)
            .ConfigureAwait(false);

        if (result?.Data is null)
        {
            throw new InvalidOperationException(result?.Message ?? "上传失败");
        }
    }

    /// <summary>上传完成后刷新列表与配额；失败不打断上传结果。必须回到 UI 线程改集合。</summary>
    private async Task RefreshFilesQuietAsync()
    {
        try
        {
            await Dispatcher.UIThread.InvokeAsync(() => RefreshFilesAsync()).ConfigureAwait(false);
        }
        catch
        {
            // 列表刷新失败不影响上传已完成的事实
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 持久化（断点续传：uploadId + 已传字节跨进程保留）
    // ─────────────────────────────────────────────────────────────
    private void Persist()
    {
        try
        {
            // 归属未知时不落库：宁可暂时不持久化，也不能把当前进程的任务写给错误账号。
            var owner = ResolveUploadOwnerUserId();
            if (owner == 0) return;

            var records = _uploads
                .Where(t => t.OwnerUserId == owner
                            && t.TotalBytes > 0 && File.Exists(t.LocalPath) && (t.Status is UploadTaskStatus.Uploading
                                                 or UploadTaskStatus.Paused or UploadTaskStatus.Failed
                                                 or UploadTaskStatus.Queued or UploadTaskStatus.Hashing))
                .Select(t => new UploadPersist
                {
                    Key = t.Key,
                    OwnerUserId = owner,
                    LocalPath = t.LocalPath,
                    ParentPath = t.ParentPath,
                    UploadId = t.UploadId,
                    TotalBytes = t.TotalBytes,
                    SentBytes = t.SentBytes,
                    ExpiresAt = t.ExpiresAt
                })
                .ToList();

            var dir = Path.GetDirectoryName(UploadsFile);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            File.WriteAllText(UploadsFile, JsonSerializer.Serialize(records));
        }
        catch
        {
            // 持久化失败不影响上传
        }
    }

    private void EnsureUploadsLoaded()
    {
        if (_uploadsLoaded) return;

        // 归属未明（登录 Bootstrap 还没完成）时先不加载：否则会误把别人的任务当自己的恢复出来。
        var owner = ResolveUploadOwnerUserId();
        if (owner == 0) return;

        _uploadsLoaded = true;

        try
        {
            // 账号隔离（task-67）：uploads.json 只保留「当前账号」的任务；
            // 别的账号的未完成任务移到 uploads.{ownerId}.json 暂存，等它自己登录时再恢复，
            // 这样既不会串号显示，也不会把别人可续传的任务真删掉。
            var pooled = new List<UploadPersist>();
            if (File.Exists(UploadsFile))
            {
                var records = JsonSerializer.Deserialize<List<UploadPersist>>(File.ReadAllText(UploadsFile));
                if (records is not null) pooled.AddRange(records);
            }

            // 本账号上次被别的账号挤出去时暂存的任务，优先并入本次恢复。
            var ownArchive = UploadArchiveFile(owner);
            if (File.Exists(ownArchive))
            {
                var archived = JsonSerializer.Deserialize<List<UploadPersist>>(File.ReadAllText(ownArchive));
                if (archived is not null) pooled.AddRange(archived);
                try { File.Delete(ownArchive); } catch { /* 忽略 */ }
            }

            // 别人的任务归档到各自的 archive 文件（源文件已丢失的直接丢，避免归档垃圾）。
            foreach (var group in pooled
                         .Where(r => r.OwnerUserId != 0 && r.OwnerUserId != owner
                                     && !string.IsNullOrWhiteSpace(r.LocalPath) && File.Exists(r.LocalPath))
                         .GroupBy(r => r.OwnerUserId))
            {
                try
                {
                    var file = UploadArchiveFile(group.Key);
                    var existing = File.Exists(file)
                        ? JsonSerializer.Deserialize<List<UploadPersist>>(File.ReadAllText(file)) ?? new List<UploadPersist>()
                        : new List<UploadPersist>();
                    foreach (var r in group)
                    {
                        if (existing.All(e => e.Key != r.Key)) existing.Add(r);
                    }
                    File.WriteAllText(file, JsonSerializer.Serialize(existing));
                }
                catch { /* 归档失败不影响本次加载 */ }
            }

            foreach (var r in pooled.Where(r => r.OwnerUserId == owner))
            {
                // 源文件已不存在（恢复到列表后不允许出现「0 B 可续传」的幽灵行）→ 丢弃并清理持久化。
                if (string.IsNullOrWhiteSpace(r.LocalPath) || !File.Exists(r.LocalPath)) continue;

                var task = new UploadTaskItem(r.LocalPath, r.ParentPath)
                {
                    OwnerUserId = owner,
                    UploadId = r.UploadId,
                    TotalBytes = r.TotalBytes,
                    SentBytes = Math.Clamp(r.SentBytes, 0, r.TotalBytes),
                    ExpiresAt = r.ExpiresAt
                };
                task.SetStatus(UploadTaskStatus.Paused);
                _uploads.Add(task);
            }

            // uploads.json 重写为「只含当前账号」的集合（别的账号已归档）。
            Persist();
        }
        catch
        {
            // 文件损坏就当作没有未完成任务
        }
    }

    /// <summary>登录 Bootstrap 拿到用户 id 后调用：重新评估归属并丢弃不属于当前账号的任务。</summary>
    public void RefreshUploadOwnership()
    {
        var owner = ResolveUploadOwnerUserId();
        if (owner == 0) return;

        EnsureUploadsLoaded();

        var stale = _uploads.Where(t => t.OwnerUserId != owner).ToList();
        if (stale.Count == 0) return;

        Ui(() =>
        {
            foreach (var task in stale) _uploads.Remove(task);
        });
        Persist();
        RaiseUploadsChanged();
    }

    /// <summary>退出登录：先把当前账号进行中的任务落库，再清空内存队列，避免切换账号后串号。</summary>
    public void FlushUploadsOnSignOut()
    {
        try { Persist(); } catch { /* 落库失败也不阻塞退出 */ }

        // 逐个 Remove 而不是 Clear：ObservableCollection.Clear() 只发 Reset，
        // Avalonia 12 的 ItemsControl 会保留旧容器，登出后上传行残留（换账号后还能看到上一位用户的进度）。
        Ui(() => Core.CollectionSync.Apply(_uploads, []));
        _uploadsLoaded = false;
        RaiseUploadsChanged();
    }

    /// <summary>任务归属账号：优先用 Bootstrap 拿到的用户 id，其次从 JWT sub 解析；都拿不到返回 0（未知）。</summary>
    private long ResolveUploadOwnerUserId()
    {
        if (_currentUserId > 0) return _currentUserId;

        try
        {
            var token = _api.AccessToken;
            if (!string.IsNullOrWhiteSpace(token)
                && Bond.Core.JwtUtil.GetUserId(token) is { } userId && userId > 0)
            {
                return userId;
            }
        }
        catch
        {
            // 解析失败按未知处理
        }

        return 0;
    }

    // ─────────────────────────────────────────────────────────────
    // 小工具
    // ─────────────────────────────────────────────────────────────
    private static void Ui(Action action)
    {
        if (Dispatcher.UIThread.CheckAccess()) action();
        else Dispatcher.UIThread.Post(action);
    }

    private void RaiseUploadsChanged() => UploadsChanged?.Invoke(this, EventArgs.Empty);

    private static string ContentTypeFor(string fileName) => Path.GetExtension(fileName).ToLowerInvariant() switch
    {
        ".jpg" or ".jpeg" => "image/jpeg",
        ".png" => "image/png",
        ".gif" => "image/gif",
        ".webp" => "image/webp",
        ".pdf" => "application/pdf",
        ".txt" or ".log" => "text/plain",
        ".md" => "text/markdown",
        ".json" => "application/json",
        ".zip" => "application/zip",
        ".mp4" => "video/mp4",
        ".mp3" => "audio/mpeg",
        _ => "application/octet-stream"
    };

    private sealed class UploadPersist
    {
        public string Key { get; set; } = "";
        public long OwnerUserId { get; set; }
        public string LocalPath { get; set; } = "";
        public string ParentPath { get; set; } = "/";
        public string? UploadId { get; set; }
        public long TotalBytes { get; set; }
        public long SentBytes { get; set; }
        public DateTimeOffset? ExpiresAt { get; set; }
    }
}
