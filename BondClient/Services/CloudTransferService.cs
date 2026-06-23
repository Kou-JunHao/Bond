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

    public async Task DownloadFileAsync(long taskId, string outputDir)
    {
        _cts = new CancellationTokenSource();
        var token = _cts.Token;

        try
        {
            TransferStarted?.Invoke();

            var taskResult = await _api.GetJsonAsync(
                $"/api/transfer/tasks/{taskId}",
                BondJsonContext.Default.ApiResultTransferTask);

            if (taskResult?.IsSuccess != true || taskResult.Data == null)
            {
                TransferFailed?.Invoke(taskResult?.Message ?? "Get task failed");
                return;
            }

            var task = taskResult.Data;
            if (task.Status != 2)
            {
                TransferFailed?.Invoke("Task not completed");
                return;
            }

            var aesKey = Convert.FromBase64String(task.EncryptedKey!);
            var fileName = task.FileName;
            var fileSize = task.FileSize;
            var chunkSize = task.ChunkSize;
            var chunkCount = task.ChunkCount;

            var outputPath = Path.Combine(outputDir, fileName);
            if (File.Exists(outputPath))
                outputPath = Path.Combine(outputDir, $"{Path.GetFileNameWithoutExtension(fileName)}_{taskId}{Path.GetExtension(fileName)}");

            _progress.Reset();
            _progress.FileName = fileName;
            _progress.TotalBytes = fileSize;
            _progress.TotalFiles = 1;
            _progress.IsTransferring = true;

            var downloadedChunks = new HashSet<int>();
            var resumeState = _chunkManager.LoadResumeState(taskId.ToString());
            if (resumeState != null)
            {
                downloadedChunks = new HashSet<int>(resumeState.UploadedChunks);
            }

            using var fs = new FileStream(outputPath, FileMode.OpenOrCreate, FileAccess.Write, FileShare.None);
            fs.SetLength(fileSize);

            for (int i = 0; i < chunkCount; i++)
            {
                token.ThrowIfCancellationRequested();

                if (downloadedChunks.Contains(i))
                {
                    long skipBytes = Math.Min(chunkSize, fileSize - (long)i * chunkSize);
                    _progress.BytesTransferred += skipBytes;
                    continue;
                }

                var chunkResp = await _api.GetRawAsync($"/api/transfer/chunks/{taskId}/{i}");
                if (chunkResp == null || !chunkResp.IsSuccessStatusCode)
                {
                    TransferFailed?.Invoke($"Download chunk {i} failed");
                    return;
                }

                var encryptedData = await chunkResp.Content.ReadAsByteArrayAsync(token);
                var decryptedData = _chunkManager.DecryptChunk(aesKey, encryptedData);

                fs.Seek((long)i * chunkSize, SeekOrigin.Begin);
                await fs.WriteAsync(decryptedData, token);

                downloadedChunks.Add(i);
                _progress.BytesTransferred += decryptedData.Length;
                _progress.FileBytesTransferred = _progress.BytesTransferred;
                ProgressUpdated?.Invoke(_progress);

                if (resumeState == null)
                    resumeState = new ResumeState
                    {
                        TaskId = taskId.ToString(),
                        FileName = fileName,
                        FileSize = fileSize,
                        ChunkSize = chunkSize,
                        CreatedAt = DateTime.UtcNow.ToString("o")
                    };
                resumeState.UploadedChunks = downloadedChunks.ToList();
                _chunkManager.SaveResumeState(resumeState);
            }

            _chunkManager.DeleteResumeState(taskId.ToString());
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
