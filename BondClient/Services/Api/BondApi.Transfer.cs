using System.Collections.Generic;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;

namespace Bond.Services.Api;

/// <summary>
/// 传输任务分页信封（task-54：传 page 时后端返回 <c>{items,total,page,size}</c>）。
/// </summary>
public class TransferTaskPageDto
{
    [JsonPropertyName("items")] public List<TransferTaskDto>? Items { get; set; }
    [JsonPropertyName("total")] public long Total { get; set; }
    [JsonPropertyName("page")] public int Page { get; set; }
    [JsonPropertyName("size")] public int Size { get; set; }
}

/// <summary>
/// 传输相关端点封装（task-14）：任务列表（分页）、取消、重试。
/// 页面层不再直接拼 URL。
/// </summary>
public sealed partial class BondApiClient
{
    /// <summary>GET /api/transfer/tasks?page=&amp;size= —— 分页信封形式，返回 null 表示失败。</summary>
    public async Task<IReadOnlyList<TransferTaskDto>?> GetTransferTasksPagedAsync(
        int page = 1,
        int size = 100,
        CancellationToken ct = default)
    {
        var result = await GetAsync<TransferTaskPageDto>(
            $"/api/transfer/tasks?page={page}&size={size}").ConfigureAwait(false);
        return result is { IsOk: true } ? result.Data?.Items : null;
    }

    /// <summary>POST /api/transfer/tasks/{id}/cancel —— 成功后返回服务端最新任务快照。</summary>
    public async Task<TransferTaskDto?> CancelTransferTaskAsync(long taskId)
    {
        var result = await PostAsync<TransferTaskDto>(
            $"/api/transfer/tasks/{taskId}/cancel").ConfigureAwait(false);
        return result is { IsOk: true } ? result.Data : null;
    }

    /// <summary>POST /api/transfer/tasks/{id}/retry —— 仅 FAILED / CANCELLED 可重试。</summary>
    public async Task<TransferTaskDto?> RetryTransferTaskAsync(long taskId)
    {
        var result = await PostAsync<TransferTaskDto>(
            $"/api/transfer/tasks/{taskId}/retry").ConfigureAwait(false);
        return result is { IsOk: true } ? result.Data : null;
    }

    /// <summary>POST /api/transfer/tasks/{id}/accept —— 接收方接受任务（PENDING → UPLOADING），重复调用幂等。</summary>
    public async Task<TransferTaskDto?> AcceptTransferTaskAsync(long taskId)
    {
        var result = await PostAsync<TransferTaskDto>(
            $"/api/transfer/tasks/{taskId}/accept").ConfigureAwait(false);
        return result is { IsOk: true } ? result.Data : null;
    }

    /// <summary>POST /api/transfer/tasks/{id}/reject —— 接收方拒绝任务（PENDING → REJECTED），其它状态 409。</summary>
    public async Task<TransferTaskDto?> RejectTransferTaskAsync(long taskId)
    {
        var result = await PostAsync<TransferTaskDto>(
            $"/api/transfer/tasks/{taskId}/reject").ConfigureAwait(false);
        return result is { IsOk: true } ? result.Data : null;
    }
}
