using System;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

namespace Bond.Services;

/// <summary>
/// 带进度回调的上传内容流：分块拷入请求体，每块回报累计字节数，
/// 供悬浮球面板显示真实上传进度（不允许假速度）。
/// 取消令牌在分块循环里检查，用户点「取消」后请求会中止。
/// </summary>
public sealed class ProgressStreamContent(
    Stream source,
    long totalBytes,
    IProgress<double>? progress,
    CancellationToken cancellationToken) : HttpContent
{
    private const int BufferSize = 64 * 1024;

    protected override async Task SerializeToStreamAsync(Stream stream, TransportContext? context)
    {
        var buffer = new byte[BufferSize];
        long done = 0;

        int read;
        while ((read = await source.ReadAsync(buffer, cancellationToken).ConfigureAwait(false)) > 0)
        {
            await stream.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
            done += read;
            progress?.Report(totalBytes <= 0 ? 1 : Math.Min(1, done / (double)totalBytes));
        }
    }

    protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context,
        CancellationToken ct)
        => SerializeToStreamAsync(stream, context);

    protected override bool TryComputeLength(out long length)
    {
        length = totalBytes;
        return totalBytes >= 0;
    }
}
