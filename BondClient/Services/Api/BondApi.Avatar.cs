using System;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading;
using System.Threading.Tasks;

namespace Bond.Services.Api;

/// <summary>
/// task-155：头像上传 / 删除（V31 接口）。
/// <para>上传走 multipart，字段名固定 <c>file</c>；删除是 204 无 body，因此不复用
/// <see cref="BondApiClient.DeleteAsync"/>（那条路径会尝试反序列化空 body）。</para>
/// </summary>
public sealed partial class BondApiClient
{
    /// <summary>上传头像（png/jpeg/webp，≤2MB）。成功时 data.avatarUrl 自带新的 ?v=（必须直接用，不要自己拼）。</summary>
    public Task<ApiResult<UserDto>?> UploadAvatarAsync(byte[] bytes, string fileName, string contentType, CancellationToken ct = default)
    {
        var content = new MultipartFormDataContent();
        var file = new ByteArrayContent(bytes);
        file.Headers.ContentType = MediaTypeHeaderValue.Parse(contentType);
        content.Add(file, "file", fileName);
        // MultipartFormDataContent 由 SendAsync 内部的 HttpResponseMessage 释放；这里不额外 Dispose，
        // 避免请求还在飞时把内容流关掉。
        return PostMultipartAsync<UserDto>("/api/auth/avatar", content);
    }

    /// <summary>删除头像（204，幂等）。返回原始 HTTP 状态码；网络失败返回 -1。</summary>
    public async Task<int> DeleteAvatarAsync(CancellationToken ct = default)
    {
        try
        {
            using var resp = await _http.DeleteAsync("/api/auth/avatar", ct).ConfigureAwait(false);
            if (resp.StatusCode == HttpStatusCode.Unauthorized
                && await TryRefreshAsync().ConfigureAwait(false))
            {
                using var retried = await _http.DeleteAsync("/api/auth/avatar", ct).ConfigureAwait(false);
                return (int)retried.StatusCode;
            }

            return (int)resp.StatusCode;
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            return -1;
        }
        catch (Exception)
        {
            return -1;
        }
    }
}
