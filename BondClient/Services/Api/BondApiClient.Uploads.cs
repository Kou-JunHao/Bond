using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;

namespace Bond.Services.Api;

// ═══════════════════════════════════════════════════════════════
//  分片上传 / 秒传 DTO（后端 v7 + task-41 已上生产）
//  · POST {filesBase}/uploads            → {uploadId, chunkSize, totalChunks, uploadedChunks}
//  · PUT  {filesBase}/uploads/{id}/{n}   → 204（原始字节，必须带准确 Content-Length）
//  · GET  {filesBase}/uploads/{id}       → {uploadedChunks, receivedBytes, totalChunks, expiresAt}
//  · POST {filesBase}/uploads/{id}/complete → 与单次上传同构 DTO
//  · DELETE {filesBase}/uploads/{id}     → abort
//  · POST /api/files/instant-upload      → 命中 200(instant=true)，未命中 404
// ═══════════════════════════════════════════════════════════════

/// <summary>临时诊断（验收用）：追加写 %TEMP%\bond-ui\upload-diag.txt，定位完即删。</summary>
internal static class UploadDiag
{
    private static readonly object Gate = new();

    public static string FilePath { get; } =
        Path.Combine(Path.GetTempPath(), "bond-ui", "upload-diag.txt");

    public static void Log(string message)
    {
        // 默认静默：仅在排查时设 BOND_UPLOAD_DIAG=1 才落盘（避免日常上传写临时文件）
        if (Environment.GetEnvironmentVariable("BOND_UPLOAD_DIAG") != "1") return;

        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
            lock (Gate)
            {
                File.AppendAllText(FilePath,
                    $"{DateTime.Now:HH:mm:ss.fff} {message}{Environment.NewLine}", Encoding.UTF8);
            }
        }
        catch
        {
            // 诊断不能影响主流程
        }
    }

    public static string Snippet(string? text, int max = 300)
        => string.IsNullOrEmpty(text) ? "" : text.Length <= max ? text : text[..max];
}

public class UploadSessionDto
{
    [JsonPropertyName("uploadId")] public string UploadId { get; set; } = "";
    [JsonPropertyName("chunkSize")] public int ChunkSize { get; set; }
    [JsonPropertyName("totalChunks")] public int TotalChunks { get; set; }
    [JsonPropertyName("uploadedChunks")] public List<int> UploadedChunks { get; set; } = new();
}

public class UploadStatusDto
{
    [JsonPropertyName("uploadedChunks")] public List<int> UploadedChunks { get; set; } = new();
    [JsonPropertyName("receivedBytes")] public long ReceivedBytes { get; set; }
    [JsonPropertyName("totalChunks")] public int TotalChunks { get; set; }
    [JsonPropertyName("expiresAt")] public long? ExpiresAt { get; set; }
}

public class InstantUploadDto
{
    [JsonPropertyName("instant")] public bool Instant { get; set; }
}

/// <summary>团队工作区分片会话：POST {filesBase}/chunks/init 只返回 uploadId（task-158）。</summary>
public class ChunkSessionDto
{
    [JsonPropertyName("uploadId")] public string UploadId { get; set; } = "";
}

/// <summary>团队工作区分片状态：GET {filesBase}/chunks/{uploadId}/status。</summary>
public class ChunkStatusDto
{
    [JsonPropertyName("uploadId")] public string UploadId { get; set; } = "";
    [JsonPropertyName("chunkSize")] public int ChunkSize { get; set; }
    [JsonPropertyName("uploadedChunks")] public List<int> UploadedChunks { get; set; } = new();
}

public sealed partial class BondApiClient
{
    /// <summary>秒传：命中返回 instant=true；未命中返回 code=404（正常分支，调用方回退普通上传）。</summary>
    public async Task<ApiResult<InstantUploadDto>?> InstantUploadAsync(object? workspaceId, string parentPath,
        string fileName, string fileHash, long fileSize, string? contentType)
    {
        object body = workspaceId is null
            ? new { parentPath, fileName, fileHash, fileSize, contentType }
            : new { workspaceId, parentPath, fileName, fileHash, fileSize, contentType };

        UploadDiag.Log($"instant req body={{parentPath={parentPath}, fileName={fileName}, fileHash={(fileHash.Length > 8 ? fileHash[..8] : fileHash)}, fileSize={fileSize}}}");

        var result = await SendAsync<ApiResult<InstantUploadDto>>(http =>
            http.PostAsync("/api/files/instant-upload", JsonBody(body))).ConfigureAwait(false);

        UploadDiag.Log($"instant resp=code={result?.Code} message={result?.Message}");
        return result;
    }

    /// <summary>创建上传会话（同名同大小未完成会复用，返回旧 uploadId 与已传分片）。</summary>
    public async Task<ApiResult<UploadSessionDto>?> InitUploadAsync(string filesBase, object? workspaceId,
        string parentPath, string fileName, long fileSize, string? contentType, string? fileHash)
    {
        object body = workspaceId is null
            ? new { parentPath, fileName, fileSize, contentType, fileHash }
            : new { workspaceId, parentPath, fileName, fileSize, contentType, fileHash };

        UploadDiag.Log($"init req=POST {filesBase}/uploads body={{parentPath={parentPath}, fileName={fileName}, fileSize={fileSize}, contentType={contentType}}}");

        var result = await SendAsync<ApiResult<UploadSessionDto>>(http =>
            http.PostAsync($"{filesBase}/uploads", JsonBody(body))).ConfigureAwait(false);

        if (result?.Data is { } data)
        {
            UploadDiag.Log($"init resp=code={result.Code} uploadId={data.UploadId} chunkSize={data.ChunkSize} totalChunks={data.TotalChunks}");
        }
        else
        {
            UploadDiag.Log($"init FAIL code={result?.Code} message={result?.Message}");
        }

        return result;
    }

    /// <summary>查询会话：已传分片 / 已收字节（续传用）。</summary>
    public Task<ApiResult<UploadStatusDto>?> GetUploadStatusAsync(string filesBase, string uploadId)
        => SendAsync<ApiResult<UploadStatusDto>>(http => http.GetAsync($"{filesBase}/uploads/{uploadId}"));

    /// <summary>
    /// 团队工作区分片会话（task-158）：POST {filesBase}/chunks/init，body 与后端 InitChunkUploadRequest 对齐。
    /// 返回只有 uploadId；fileHash 命中时返回 "instant:&lt;fileId&gt;"。带 resumeUploadId 可续传。
    /// </summary>
    public async Task<ApiResult<ChunkSessionDto>?> InitWorkspaceChunkUploadAsync(string filesBase,
        string parentPath, string fileName, long fileSize, string? contentType, string? fileHash,
        int chunkSize, string? resumeUploadId)
    {
        object body = new
        {
            fileName,
            fileSize,
            parentPath,
            fileHash,
            contentType,
            chunkSize,
            uploadId = string.IsNullOrWhiteSpace(resumeUploadId) ? null : resumeUploadId
        };

        UploadDiag.Log($"ws-init req=POST {filesBase}/chunks/init body={{parentPath={parentPath}, fileName={fileName}, fileSize={fileSize}, chunkSize={chunkSize}, resume={resumeUploadId}}}");

        var result = await SendAsync<ApiResult<ChunkSessionDto>>(http =>
            http.PostAsync($"{filesBase}/chunks/init", JsonBody(body))).ConfigureAwait(false);

        UploadDiag.Log($"ws-init resp=code={result?.Code} uploadId={result?.Data?.UploadId}");
        return result;
    }

    /// <summary>团队工作区分片状态：已收分片 + 服务端实际分片大小。</summary>
    public Task<ApiResult<ChunkStatusDto>?> GetWorkspaceChunkStatusAsync(string filesBase, string uploadId)
        => SendAsync<ApiResult<ChunkStatusDto>>(http => http.GetAsync($"{filesBase}/chunks/{uploadId}/status"));

    /// <summary>团队工作区：合并分片落库，返回与单次上传同构的文件 DTO（instant: 前缀由后端识别）。</summary>
    public async Task<ApiResult<WorkspaceFileDto>?> CompleteWorkspaceChunkUploadAsync(string filesBase, string uploadId)
    {
        UploadDiag.Log($"ws-complete req=POST {filesBase}/chunks/{uploadId}/complete");

        var result = await SendAsync<ApiResult<WorkspaceFileDto>>(http => http.PostAsync(
            $"{filesBase}/chunks/{uploadId}/complete",
            new StringContent("{}", Encoding.UTF8, "application/json"))).ConfigureAwait(false);

        UploadDiag.Log($"ws-complete resp=code={result?.Code} message={result?.Message}");
        return result;
    }

    /// <summary>个人空间 v7 会话：上传一个分片（原始字节，PUT）。成功 204，失败时返回后端 ApiResult。</summary>
    public Task<ApiResult?> PutChunkAsync(string filesBase, string uploadId, int index, byte[] data,
        CancellationToken ct = default)
        => PutChunkCoreAsync($"{filesBase}/uploads/{uploadId}/{index}", index, data, ct);

    /// <summary>
    /// 团队工作区：PUT {filesBase}/chunks/{uploadId}/{index}。
    /// 后端签名是 <c>@RequestParam MultipartFile file</c>（不是原始字节），必须用 multipart 字段 file 发送；
    /// 分片大小由 file 的真实长度决定，必须与 init 时约定的 chunkSize 完全一致（task-158）。
    /// </summary>
    public async Task<ApiResult?> PutWorkspaceChunkAsync(string filesBase, string uploadId, int index, byte[] data,
        CancellationToken ct = default)
    {
        var url = $"{filesBase}/chunks/{uploadId}/{index}";
        UploadDiag.Log($"ws-chunk req=PUT {url} len={data.Length}");

        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(TimeSpan.FromSeconds(180));

        static MultipartFormDataContent BuildBody(byte[] payload)
        {
            var form = new MultipartFormDataContent();
            var part = new ByteArrayContent(payload);
            part.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            form.Add(part, "file", "chunk");
            return form;
        }

        try
        {
            var sw = Stopwatch.StartNew();
            using (var form = BuildBody(data))
            using (var resp = await _http.PutAsync(url, form, timeoutCts.Token).ConfigureAwait(false))
            {
                var text = await resp.Content.ReadAsStringAsync(timeoutCts.Token).ConfigureAwait(false);
                UploadDiag.Log($"ws-chunk resp={(int)resp.StatusCode} idx={index} after {sw.ElapsedMilliseconds}ms body={UploadDiag.Snippet(text)}");

                if (resp.IsSuccessStatusCode)
                {
                    return new ApiResult { Code = (int)resp.StatusCode, Message = "OK" };
                }

                if (resp.StatusCode == HttpStatusCode.Unauthorized && await TryRefreshAsync().ConfigureAwait(false))
                {
                    using var retryForm = BuildBody(data);
                    using var retryResp = await _http.PutAsync(url, retryForm, timeoutCts.Token).ConfigureAwait(false);
                    UploadDiag.Log($"ws-chunk retry resp={(int)retryResp.StatusCode} idx={index}");
                    return new ApiResult
                    {
                        Code = (int)retryResp.StatusCode,
                        Message = retryResp.IsSuccessStatusCode ? "OK" : retryResp.ReasonPhrase ?? ""
                    };
                }

                try
                {
                    var result = JsonSerializer.Deserialize<ApiResult>(text, JsonOptions);
                    if (result is not null) return result;
                }
                catch
                {
                    // 非 JSON（网关错误页）
                }

                return new ApiResult
                {
                    Code = (int)resp.StatusCode,
                    Message = resp.ReasonPhrase ?? $"HTTP {(int)resp.StatusCode}"
                };
            }
        }
        catch (OperationCanceledException)
        {
            UploadDiag.Log($"ws-chunk TIMEOUT idx={index}");
            throw;
        }
        catch (Exception ex)
        {
            UploadDiag.Log($"ws-chunk EXC idx={index}: {ex.GetType().Name} {ex.Message}");
            return null;
        }
    }

    private async Task<ApiResult?> PutChunkCoreAsync(string url, int index, byte[] data,
        CancellationToken ct = default)
    {
        UploadDiag.Log($"chunk req=PUT {url} len={data.Length} contentLength={data.Length}");

        var sw = Stopwatch.StartNew();

        // 单次 PUT 最多 180s（低速链路 8MB 片可能 30s+）
        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(TimeSpan.FromSeconds(180));

        try
        {
            using var body = new ByteArrayContent(data);
            body.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            body.Headers.ContentLength = data.Length;

            using var request = new HttpRequestMessage(HttpMethod.Put, url) { Content = body };

            using var resp = await _http.SendAsync(request, timeoutCts.Token).ConfigureAwait(false);
            var text = await resp.Content.ReadAsStringAsync(timeoutCts.Token).ConfigureAwait(false);

            UploadDiag.Log($"chunk resp={((int)resp.StatusCode)} idx={index} after {sw.ElapsedMilliseconds}ms body={UploadDiag.Snippet(text)}");

            if (resp.IsSuccessStatusCode)
            {
                return new ApiResult { Code = (int)resp.StatusCode, Message = "OK" };
            }

            if (resp.StatusCode == HttpStatusCode.Unauthorized && await TryRefreshAsync().ConfigureAwait(false))
            {
                using var retryBody = new ByteArrayContent(data);
                retryBody.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
                retryBody.Headers.ContentLength = data.Length;

                using var retry = new HttpRequestMessage(HttpMethod.Put, url) { Content = retryBody };
                using var retryResp = await _http.SendAsync(retry, timeoutCts.Token).ConfigureAwait(false);
                UploadDiag.Log($"chunk retry resp={((int)retryResp.StatusCode)} idx={index}");
                return new ApiResult
                {
                    Code = (int)retryResp.StatusCode,
                    Message = retryResp.IsSuccessStatusCode ? "OK" : retryResp.ReasonPhrase ?? ""
                };
            }

            try
            {
                var result = JsonSerializer.Deserialize<ApiResult>(text, JsonOptions);
                if (result is not null) return result;
            }
            catch
            {
                // 非 JSON（如网关 502）
            }

            return new ApiResult
            {
                Code = (int)resp.StatusCode,
                Message = resp.ReasonPhrase ?? $"HTTP {(int)resp.StatusCode}"
            };
        }
        catch (OperationCanceledException)
        {
            UploadDiag.Log($"chunk TIMEOUT idx={index} after {sw.ElapsedMilliseconds}ms");
            throw;
        }
        catch (Exception ex)
        {
            UploadDiag.Log($"chunk EXC idx={index} after {sw.ElapsedMilliseconds}ms: {ex.GetType().Name} {ex.Message}");
            return null;
        }
    }

    /// <summary>合并分片并落库，返回与单次上传同构的文件 DTO。</summary>
    public async Task<ApiResult<WorkspaceFileDto>?> CompleteUploadAsync(string filesBase, string uploadId)
    {
        UploadDiag.Log($"complete req=POST {filesBase}/uploads/{uploadId}/complete body={{}}");

        var result = await SendAsync<ApiResult<WorkspaceFileDto>>(http => http.PostAsync(
            $"{filesBase}/uploads/{uploadId}/complete",
            new StringContent("{}", Encoding.UTF8, "application/json"))).ConfigureAwait(false);

        UploadDiag.Log($"complete resp=code={result?.Code} message={result?.Message}");
        return result;
    }

    /// <summary>abort 会话（取消上传）。</summary>
    public Task<ApiResult?> AbortUploadAsync(string filesBase, string uploadId)
        => DeleteAsync($"{filesBase}/uploads/{uploadId}");
}
