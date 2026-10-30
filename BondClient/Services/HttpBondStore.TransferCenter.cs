using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Threading;
using Bond.Models;

namespace Bond.Services;

/// <summary>
/// 传输中心数据层（task-14）：服务端任务快照对账、任务取消/重试、下载会话跟踪、活跃任务静默轮询。
/// 任务（<see cref="TransferItem"/>，服务端 transfer_tasks）与会话（<see cref="TransferSession"/>，本机网盘传输）保持分离，
/// 只在页面层合并成 <see cref="TransferRow"/> 展示。
/// </summary>
public sealed partial class HttpBondStore
{
    private static readonly TimeSpan TransferPollInterval = TimeSpan.FromSeconds(5);

    private readonly ObservableCollection<TransferSession> _sessions = [];

    private int _transferPolling;

    /// <summary>本机下载会话（上传会话通过 IUploadService.Uploads 提供）。</summary>
    public ObservableCollection<TransferSession> Sessions => _sessions;

    public event EventHandler? SessionsChanged;

    /// <summary>取消一次下载会话：中断读流，由 <c>DownloadAsync</c> 收尾为「已取消」。</summary>
    public void CancelSession(TransferSession session)
    {
        if (session is null) return;
        try { session.Cancellation?.Cancel(); }
        catch (ObjectDisposedException) { /* 已结束的会话忽略 */ }
    }

    // ── 服务端任务：取消 / 重试 ───────────────────────────────

    /// <summary>取消服务端任务；失败时如实把原因写进传输页错误条。</summary>
    public async Task<bool> CancelAsync(TransferItem item)
    {
        if (item is null) return false;

        if (!long.TryParse(item.Id, out var taskId))
        {
            // 仅本机会话内的虚拟任务（理论上不存在）——退化为本地标记。
            item.State = TransferState.Canceled;
            Changed?.Invoke(this, EventArgs.Empty);
            return false;
        }

        var dto = await _api.CancelTransferTaskAsync(taskId).ConfigureAwait(false);
        if (dto is null)
        {
            _lastTransfersError = LoadRetry.Humanize(_api.LastError, "取消任务失败，请检查网络后重试");
            Changed?.Invoke(this, EventArgs.Empty);
            return false;
        }

        _lastTransfersError = null;
        item.State = TransferState.Canceled;
        Changed?.Invoke(this, EventArgs.Empty);
        StartTransferPolling();
        return true;
    }

    /// <summary>重试失败/已取消任务：先走服务端 retry，失败再尝试用本机源文件重建。</summary>
    public async Task<bool> RetryAsync(TransferItem item)
    {
        if (long.TryParse(item.Id, out var taskId))
        {
            var dto = await _api.RetryTransferTaskAsync(taskId).ConfigureAwait(false);
            if (dto is not null)
            {
                _lastTransfersError = null;
                item.State = TransferState.Pending;
                item.Progress = 0;
                Changed?.Invoke(this, EventArgs.Empty);
                StartTransferPolling();
                return true;
            }
        }

        if (item.LocalPath is null || !File.Exists(item.LocalPath)) return false;
        if (!long.TryParse(item.ReceiverDeviceId, out var receiverDeviceId)) return false;

        var info = new FileInfo(item.LocalPath);
        var recreated = await _api.PostAsync<Bond.Services.Api.TransferTaskDto>("/api/transfer/tasks", new
        {
            receiverDeviceId,
            fileName = info.Name,
            fileSize = info.Length,
            chunkSize = 5 * 1024 * 1024
        }).ConfigureAwait(false);

        if (recreated?.IsOk != true) return false;

        item.State = TransferState.Pending;
        item.Progress = 0;
        _lastTransfersError = null;
        Changed?.Invoke(this, EventArgs.Empty);
        StartTransferPolling();
        return true;
    }

    /// <summary>接收方接受任务（task-94）：POST /accept，成功后立刻按服务端快照刷新。</summary>
    public Task<bool> AcceptAsync(TransferItem item, CancellationToken ct = default) =>
        RespondAsync(item, accept: true, ct);

    /// <summary>接收方拒绝任务（task-94）：POST /reject，成功后立刻按服务端快照刷新。</summary>
    public Task<bool> RejectAsync(TransferItem item, CancellationToken ct = default) =>
        RespondAsync(item, accept: false, ct);

    private async Task<bool> RespondAsync(TransferItem item, bool accept, CancellationToken ct)
    {
        if (item is null || !long.TryParse(item.Id, out var taskId)) return false;

        var dto = accept
            ? await _api.AcceptTransferTaskAsync(taskId).ConfigureAwait(false)
            : await _api.RejectTransferTaskAsync(taskId).ConfigureAwait(false);

        if (dto is null)
        {
            _lastTransfersError = LoadRetry.Humanize(
                _api.LastError,
                accept ? "接受任务失败，请稍后重试" : "拒绝任务失败，请稍后重试");
            Changed?.Invoke(this, EventArgs.Empty);
            return false;
        }

        _lastTransfersError = null;
        await RefreshTransfersAsync().ConfigureAwait(false);
        StartTransferPolling();
        return true;
    }

    // ── 任务快照对账（避免 ItemsControl 因 Reset 产生重复容器）──
    /// <summary>按 id 就地更新任务列表：已存在的改状态/进度，新增的插入，消失的移除。</summary>
    private async Task ApplyTransferSnapshotAsync(List<TransferItem> fresh)
    {
        await Dispatcher.UIThread.InvokeAsync(() =>
        {
            var existing = _transfers.ToDictionary(t => t.Id, StringComparer.Ordinal);
            var freshIds = fresh.Select(f => f.Id).ToHashSet(StringComparer.Ordinal);

            for (var i = _transfers.Count - 1; i >= 0; i--)
            {
                if (!freshIds.Contains(_transfers[i].Id)) _transfers.RemoveAt(i);
            }

            for (var i = 0; i < fresh.Count; i++)
            {
                if (existing.TryGetValue(fresh[i].Id, out var current))
                {
                    current.State = fresh[i].State;
                    current.Progress = fresh[i].Progress;
                    var index = _transfers.IndexOf(current);
                    if (index >= 0 && index != i) _transfers.Move(index, i);
                }
                else
                {
                    _transfers.Insert(i, fresh[i]);
                }
            }
        });
    }

    /// <summary>服务端状态码 → 客户端状态（0 PENDING / 1 UPLOADING / 2 COMPLETED / 3 FAILED / 4 EXPIRED / 5 CANCELLED / 6 REJECTED）。</summary>
    private static TransferState MapTransferState(int status) => status switch
    {
        1 => TransferState.Running,
        2 => TransferState.Done,
        3 => TransferState.Failed,
        4 => TransferState.Expired,
        5 => TransferState.Canceled,
        6 => TransferState.Rejected,
        _ => TransferState.Pending
    };

    private TransferItem MapTransferItem(Bond.Services.Api.TransferTaskDto dto, IReadOnlyDictionary<string, string> deviceNames)
    {
        var isSender = dto.SenderId == _currentUserId;
        var direction = isSender ? TransferDirection.Upload : TransferDirection.Download;

        // 方向不同，进度来源也不同：发送方看已上传分片，接收方看已下载分片（task-55 B 的真实打点）。
        var done = direction == TransferDirection.Upload ? dto.UploadedChunks : dto.DownloadedChunks;
        var progress = dto.ChunkCount > 0
            ? Math.Clamp(done / (double)dto.ChunkCount, 0, 1)
            : 0;

        var state = MapTransferState(dto.Status);
        if (state == TransferState.Done) progress = 1;

        return new TransferItem
        {
            Id = dto.Id.ToString(),
            FileName = string.IsNullOrWhiteSpace(dto.FileName) ? $"任务 {dto.Id}" : dto.FileName,
            PeerName = DescribePeer(isSender, dto, deviceNames),
            Size = dto.FileSize,
            Direction = direction,
            IsIncoming = !isSender,
            State = state,
            Progress = progress
        };
    }

    /// <summary>
    /// 对端展示名（task-96）：优先用设备列表里的设备名，其次服务端返回的对端用户名，
    /// 都拿不到时回退「接收方设备 / 发送方设备」——绝不把雪花 id 直接显示给用户。
    /// </summary>
    private static string DescribePeer(bool isSender, Bond.Services.Api.TransferTaskDto dto, IReadOnlyDictionary<string, string> deviceNames)
    {
        var role = isSender ? "接收方" : "发送方";
        var deviceId = isSender ? dto.ReceiverDeviceId : dto.SenderDeviceId;

        string? name = null;
        if (!string.IsNullOrWhiteSpace(deviceId) && deviceNames.TryGetValue(deviceId, out var deviceName))
        {
            name = deviceName;
        }

        if (string.IsNullOrWhiteSpace(name) && !string.IsNullOrWhiteSpace(dto.PeerName)) name = dto.PeerName;

        return string.IsNullOrWhiteSpace(name) ? $"{role}设备" : $"{role} · {name}";
    }

    /// <summary>设备行 id → 设备名快照（task-96）。在 UI 线程取，避免跨线程读 <see cref="Peers"/>。</summary>
    private async Task<Dictionary<string, string>> SnapshotDeviceNamesAsync()
    {
        return await Dispatcher.UIThread.InvokeAsync(() =>
        {
            var map = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var peer in _peers)
            {
                if (!string.IsNullOrWhiteSpace(peer.Id) && !string.IsNullOrWhiteSpace(peer.Name))
                {
                    map[peer.Id] = peer.Name;
                }
            }

            return map;
        });
    }

    // ── 活跃任务静默轮询（5s；失败不写错误条，界面不抖）──

    private void StartTransferPolling()
    {
        if (Interlocked.CompareExchange(ref _transferPolling, 1, 0) != 0) return;

        _ = Task.Run(async () =>
        {
            try
            {
                using var timer = new PeriodicTimer(TransferPollInterval);
                while (await timer.WaitForNextTickAsync().ConfigureAwait(false))
                {
                    var hasActive = await Dispatcher.UIThread.InvokeAsync(() => _transfers.Any(t => t.IsActive));
                    if (!hasActive) continue;

                    await RefreshTransfersAsync(silent: true).ConfigureAwait(false);
                }
            }
            catch (Exception)
            {
                // 轮询异常静默：手动「刷新」仍是完整入口。
            }
            finally
            {
                Interlocked.Exchange(ref _transferPolling, 0);
            }
        });
    }

    // ── 下载会话 ─────────────────────────────────────────────

    private TransferSession BeginDownloadSession(string fileName, string targetLabel, long totalBytes,
        CancellationTokenSource cancellation)
    {
        var session = new TransferSession(TransferSessionKind.Download,
            Guid.NewGuid().ToString("N"), fileName, targetLabel, totalBytes)
        {
            Cancellation = cancellation,
            State = TransferSessionState.Running
        };

        DispatchSessions(() => _sessions.Insert(0, session));
        return session;
    }

    private void ReportDownloadProgress(TransferSession session, long receivedBytes)
    {
        DispatchSessions(() => session.Record(receivedBytes));
    }

    private void FinishDownloadSession(TransferSession session, string? savedPath, string? error, bool canceled = false)
    {
        DispatchSessions(() =>
        {
            if (!string.IsNullOrEmpty(savedPath)) session.LocalPath = savedPath;
            session.SetState(canceled
                ? TransferSessionState.Canceled
                : error is null
                    ? TransferSessionState.Done
                    : TransferSessionState.Failed, error);
            SessionsChanged?.Invoke(this, EventArgs.Empty);
        });
    }

    private static void DispatchSessions(Action action)
    {
        if (Dispatcher.UIThread.CheckAccess()) action();
        else Dispatcher.UIThread.Post(action);
    }
}
