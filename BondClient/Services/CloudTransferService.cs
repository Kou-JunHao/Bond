using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json.Serialization.Metadata;
using BondClient.Models;

namespace BondClient.Services;

public class CloudTransferService : IDisposable
{
    private readonly ApiClient _api;
    private readonly ChunkManager _chunkManager;
    private readonly E2ECryptoService _crypto;

    public event Action<TransferProgress>? ProgressUpdated;
    public event Action<string>? TransferComplete;
    public event Action<string>? TransferFailed;
    public event Action? TransferStarted;
    public event Action? TransferEnded;

    private CancellationTokenSource? _cts;
    private readonly TransferProgress _progress = new();

    public CloudTransferService(ApiClient api, ChunkManager chunkManager, E2ECryptoService crypto)
    {
        _api = api;
        _chunkManager = chunkManager;
        _crypto = crypto;
    }

    public async Task SendFileAsync(string filePath, long receiverId, long? senderDeviceId, long? receiverDeviceId)
    {
        _cts = new CancellationTokenSource();
        var token = _cts.Token;

        try
        {
            TransferStarted?.Invoke();
            var fileName = Path.GetFileName(filePath);
            var fileSize = new FileInfo(filePath).Length;
            var chunkSize = _chunkManager.GetChunkSize(fileSize);
            var chunkCount = _chunkManager.CalculateChunkCount(fileSize);

            _progress.Reset();
            _progress.FileName = fileName;
            _progress.TotalBytes = fileSize;
            _progress.TotalFiles = 1;
            _progress.IsTransferring = true;

            var aesKey = E2ECryptoService.GenerateAesKey();

            var createJson = $$"""{"receiverId":{{receiverId}},"senderDeviceId":{{senderDeviceId ?? 0}},"receiverDeviceId":{{receiverDeviceId ?? 0}},"fileName":"{{fileName}}","fileSize":{{fileSize}},"chunkSize":{{chunkSize}},"encryptedKey":"{{Convert.ToBase64String(aesKey)}}"}""";
            var createResult = await _api.PostRawJsonAsync("/api/transfer/tasks", createJson, BondJsonContext.Default.ApiResultTransferTask);

            if (createResult?.IsSuccess != true || createResult.Data == null)
            {
                TransferFailed?.Invoke(createResult?.Message ?? "Create task failed");
                return;
            }

            var task = createResult.Data;
            var initResult = await _api.PostRawJsonAsync(
                $"/api/transfer/chunks/{task.Id}/init", "{}",
                BondJsonContext.Default.ApiResultDictionaryStringString);

            if (initResult?.IsSuccess != true || initResult.Data == null)
            {
                TransferFailed?.Invoke("Init multipart upload failed");
                return;
            }

            var uploadId = initResult.Data["uploadId"];
            var resumeState = _chunkManager.LoadResumeState(task.Id.ToString())
                ?? new ResumeState
                {
                    TaskId = task.Id.ToString(),
                    FileName = fileName,
                    FileSize = fileSize,
                    ChunkSize = chunkSize,
                    CreatedAt = DateTime.UtcNow.ToString("o")
                };

            for (int i = 0; i < chunkCount; i++)
            {
                token.ThrowIfCancellationRequested();

                if (resumeState.UploadedChunks.Contains(i))
                {
                    _progress.BytesTransferred += Math.Min(chunkSize, fileSize - (long)i * chunkSize);
                    continue;
                }

                var encryptedChunk = await _chunkManager.ReadAndEncryptChunkAsync(filePath, i, chunkSize, aesKey);
                using var content = new MultipartFormDataContent();
                var fileContent = new ByteArrayContent(encryptedChunk);
                fileContent.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
                content.Add(fileContent, "file", $"chunk_{i}");

                var chunkResult = await _api.PostMultipartAsync<TransferChunkInfo>(
                    $"/api/transfer/chunks/{task.Id}/{i}?uploadId={uploadId}",
                    content,
                    BondJsonContext.Default.ApiResultTransferChunkInfo);

                if (chunkResult?.IsSuccess != true)
                {
                    TransferFailed?.Invoke($"Upload chunk {i} failed: {chunkResult?.Message}");
                    return;
                }

                resumeState.UploadedChunks.Add(i);
                _chunkManager.SaveResumeState(resumeState);

                long chunkBytes = Math.Min(chunkSize, fileSize - (long)i * chunkSize);
                _progress.BytesTransferred += chunkBytes;
                _progress.FileBytesTransferred = _progress.BytesTransferred;
                ProgressUpdated?.Invoke(_progress);
            }

            var completeResult = await _api.PostRawJsonAsync(
                $"/api/transfer/tasks/{task.Id}/complete", "{}");

            if (completeResult?.IsSuccess != true)
            {
                TransferFailed?.Invoke("Complete task failed");
                return;
            }

            _chunkManager.DeleteResumeState(task.Id.ToString());
            _progress.IsTransferring = false;
            _progress.FilesCompleted = 1;
            TransferComplete?.Invoke(fileName);
        }
        catch (OperationCanceledException)
        {
            _progress.IsCancelled = true;
        }
        catch (Exception ex)
        {
            TransferFailed?.Invoke(ex.Message);
        }
        finally
        {
            _progress.IsTransferring = false;
            TransferEnded?.Invoke();
        }
    }

    public void CancelTransfer()
    {
        _cts?.Cancel();
    }

    public void Dispose()
    {
        _cts?.Dispose();
        _crypto.Dispose();
        _chunkManager.Dispose();
    }
}
