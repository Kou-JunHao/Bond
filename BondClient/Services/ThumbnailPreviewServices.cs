using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Media.Imaging;
using Bond.Models;
using Bond.Services.Api;

namespace Bond.Services;

/// <summary>
/// 预览数据服务：短签 URL / 内容流 / 临时文件下载。
/// URL 由后端签发（token 在 query 里），缩略图与内容端点都无需再带 Authorization。
/// </summary>
public sealed class PreviewService(BondApiClient api) : IPreviewService
{
    private sealed record CachedUrls(PreviewUrlDto Urls, DateTimeOffset ExpiresAt);

    private readonly Dictionary<string, CachedUrls> _urlCache = new();
    private readonly object _urlLock = new();

    private static string BasePath(NodeItem node)
    {
        if (ChatFileSpace.IsChatFile(node))
        {
            return $"/api/chat/files/{ChatFileSpace.MessageId(node)}";
        }

        return string.Equals(node.SpaceId, "personal", StringComparison.OrdinalIgnoreCase)
            ? $"/api/files/{node.Id}"
            : $"/api/workspaces/{node.SpaceId}/files/{node.Id}";
    }

    public async Task<PreviewUrlDto?> ResolveUrlsAsync(NodeItem node, CancellationToken ct = default)
    {
        var key = BasePath(node);

        lock (_urlLock)
        {
            if (_urlCache.TryGetValue(key, out var cached) && cached.ExpiresAt > DateTimeOffset.UtcNow)
                return cached.Urls;
        }

        PreviewUrlDto? urls;
        if (ChatFileSpace.IsChatFile(node))
        {
            // 聊天文件：按 messageId 签发短时令牌，发送方/接取方同一路径，URL 已带 token。
            // 所有失败语义统一 404（撤回/移出会话/删除），不区分原因。
            var tokens = await api.PostAsync<ChatFileTokenDto>(key + "/tokens").ConfigureAwait(false);
            var data = tokens?.Data;
            urls = data is null
                ? null
                : new PreviewUrlDto
                {
                    ContentUrl = data.ContentUrl,
                    ThumbnailUrl = data.ThumbnailUrl,
                    OfficePdfUrl = data.OfficePdfUrl,
                    ExpiresIn = data.ExpiresIn,
                    ContentType = data.ContentType,
                    FileName = data.FileName
                };
        }
        else
        {
            var result = await api.GetAsync<PreviewUrlDto>(key + "/preview-url").ConfigureAwait(false);
            urls = result?.Data;
        }

        if (urls is null) return null;

        lock (_urlLock)
        {
            var ttl = TimeSpan.FromSeconds(Math.Max(30, urls.ExpiresIn - 90));
            _urlCache[key] = new CachedUrls(urls, DateTimeOffset.UtcNow + ttl);
        }

        return urls;
    }

    public async Task<string?> GetContentUrlAsync(NodeItem node, CancellationToken ct = default)
    {
        var urls = await ResolveUrlsAsync(node, ct).ConfigureAwait(false);
        if (urls is null || string.IsNullOrEmpty(urls.ContentUrl)) return null;

        return Uri.TryCreate(urls.ContentUrl, UriKind.Absolute, out var absolute)
            ? absolute.ToString()
            : api.BaseUrl.TrimEnd('/') + "/" + urls.ContentUrl.TrimStart('/');
    }

    public async Task<(byte[]? Data, bool Truncated)> GetContentAsync(
        NodeItem node, long? maxBytes = null, CancellationToken ct = default)
    {
        var urls = await ResolveUrlsAsync(node, ct).ConfigureAwait(false);
        if (urls is null || string.IsNullOrEmpty(urls.ContentUrl)) return (null, false);

        if (maxBytes is null)
        {
            var all = await api.GetBytesAsync(urls.ContentUrl).ConfigureAwait(false);
            return (all, false);
        }

        return await api.GetBytesLimitedAsync(urls.ContentUrl, maxBytes.Value).ConfigureAwait(false);
    }

    /// <summary>task-60：办公文档预览（服务端转 PDF）。命中本地缓存直接读盘。</summary>
    public async Task<(byte[]? Data, int Status)> GetOfficePdfAsync(NodeItem node, CancellationToken ct = default)
    {
        var cacheDir = OfficeCacheDir();
        var cachePath = Path.Combine(cacheDir, $"{node.Id}-{node.Size}.pdf");

        try
        {
            if (File.Exists(cachePath))
            {
                var cached = await File.ReadAllBytesAsync(cachePath, ct).ConfigureAwait(false);
                if (cached.Length > 4) return (cached, 200);
            }
        }
        catch
        {
            // 缓存读失败不影响网络路径。
        }

        var urls = await ResolveUrlsAsync(node, ct).ConfigureAwait(false);
        if (urls is null || string.IsNullOrWhiteSpace(urls.OfficePdfUrl)) return (null, 0);

        var url = Uri.TryCreate(urls.OfficePdfUrl, UriKind.Absolute, out var absolute)
            ? absolute.ToString()
            : api.BaseUrl.TrimEnd('/') + "/" + urls.OfficePdfUrl.TrimStart('/');

        using var response = await api.GetStreamAsync(url).ConfigureAwait(false);
        if (response is null) return (null, -1);

        var status = (int)response.StatusCode;
        if (!response.IsSuccessStatusCode) return (null, status);

        var bytes = await response.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
        if (bytes.Length == 0) return (null, status);

        try
        {
            Directory.CreateDirectory(cacheDir);
            await File.WriteAllBytesAsync(cachePath, bytes, ct).ConfigureAwait(false);
            TrimOfficeCache(cacheDir);
        }
        catch
        {
            // 缓存写失败不影响本次预览。
        }

        return (bytes, status);
    }

    private static string OfficeCacheDir()
        => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Bond", "office-cache");

    /// <summary>单文件缓存上限 200MB，超出按最后访问时间（LRU 近似）清理。</summary>
    private static void TrimOfficeCache(string dir, long limit = 200L * 1024 * 1024)
    {
        try
        {
            var files = new DirectoryInfo(dir).GetFiles("*.pdf");
            long total = files.Sum(f => f.Length);
            foreach (var file in files.OrderBy(f => f.LastAccessTimeUtc))
            {
                if (total <= limit) break;
                total -= file.Length;
                file.Delete();
            }
        }
        catch
        {
            // 清理失败可忽略。
        }
    }

    public async Task<string?> DownloadToTempAsync(NodeItem node, CancellationToken ct = default)
    {
        var urls = await ResolveUrlsAsync(node, ct).ConfigureAwait(false);
        if (urls is null || string.IsNullOrEmpty(urls.ContentUrl)) return null;

        try
        {
            var dir = Path.Combine(Path.GetTempPath(), "Bond", "preview");
            Directory.CreateDirectory(dir);

            var safeName = string.Join("_", node.Name.Split(
                Path.GetInvalidFileNameChars(), StringSplitOptions.RemoveEmptyEntries));
            if (string.IsNullOrWhiteSpace(safeName)) safeName = "preview";
            var target = Path.Combine(dir, node.Id + "_" + safeName);

            if (File.Exists(target) && new FileInfo(target).Length == node.Size) return target;

            using var response = await api.GetStreamAsync(urls.ContentUrl).ConfigureAwait(false);
            if (response is null) return null;

            await using var source = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            await using var file = File.Create(target);
            await source.CopyToAsync(file, ct).ConfigureAwait(false);
            return target;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>请求指定宽度的缩略图（后端白名单 64/128/320/640）；204/空内容返回 null。</summary>
    public async Task<byte[]?> GetThumbnailBytesAsync(NodeItem node, int width, CancellationToken ct = default)
    {
        // task-165：附件即时占位阶段直接读本地文件字节（图片缩略图先本地渲染，不等上传）。
        if (LocalFileSpace.IsLocal(node))
        {
            try
            {
                var localPath = LocalFileSpace.Path(node);
                return await File.ReadAllBytesAsync(localPath, ct).ConfigureAwait(false);
            }
            catch
            {
                return null;
            }
        }

        var urls = await ResolveUrlsAsync(node, ct).ConfigureAwait(false);
        if (urls is null || string.IsNullOrEmpty(urls.ThumbnailUrl)) return null;

        var url = ReplaceWidth(urls.ThumbnailUrl, width);
        var bytes = await api.GetBytesAsync(url).ConfigureAwait(false);
        return bytes is { Length: > 0 } ? bytes : null;
    }

    private static string ReplaceWidth(string url, int width)
    {
        var idx = url.IndexOf("w=", StringComparison.Ordinal);
        if (idx < 0) return url + "&w=" + width;

        var end = url.IndexOf('&', idx);
        return end < 0
            ? url[..idx] + "w=" + width
            : url[..idx] + "w=" + width + url[end..];
    }
}

/// <summary>
/// 缩略图：内存 LRU（≤200 张 / 64MB）、最多 4 并发、后台解码、204 回退为 null。
/// 滚出视口时由调用方取消 CancellationToken（ThumbnailView 负责）。
/// </summary>
public sealed class ThumbnailService(PreviewService preview) : IThumbnailService
{
    private const int MaxEntries = 200;
    private const long MaxCacheBytes = 64L * 1024 * 1024;
    private const int MaxConcurrency = 4;

    private sealed class Entry(Bitmap? bitmap, long cost)
    {
        public Bitmap? Bitmap { get; } = bitmap;
        public long Cost { get; } = cost;
    }

    private readonly Dictionary<string, Entry> _cache = new();
    private readonly LinkedList<string> _lru = new();
    private readonly Dictionary<string, DateTimeOffset> _negative = new();
    private readonly SemaphoreSlim _gate = new(MaxConcurrency, MaxConcurrency);
    private readonly object _lock = new();
    private long _cacheBytes;

    // 临时失败（网络/服务端生成超时）只压制这么多秒：一次失败不能让缩略图永久消失
    private const int NegativeTtlSeconds = 20;

    private bool IsNegative(string key)
        => _negative.TryGetValue(key, out var until) && until > DateTimeOffset.UtcNow;

    private void AddNegative(string key)
    {
        _negative[key] = DateTimeOffset.UtcNow.AddSeconds(NegativeTtlSeconds);
        if (_negative.Count > 512) _negative.Clear();
    }

    public async Task<Bitmap?> GetAsync(NodeItem node, int width, CancellationToken ct = default)
    {
        if (node.Kind == NodeKind.Folder || !node.HasThumbnail) return null;

        var key = $"{node.Id}@{node.ModifiedAt.Ticks}@{width}";

        lock (_lock)
        {
            if (_cache.TryGetValue(key, out var hit))
            {
                _lru.Remove(key);
                _lru.AddFirst(key);
                return hit.Bitmap;
            }

            if (IsNegative(key)) return null;
        }

        await _gate.WaitAsync(ct).ConfigureAwait(true);
        try
        {
            // 排队期间可能已被别的请求填好
            lock (_lock)
            {
                if (_cache.TryGetValue(key, out var hit)) return hit.Bitmap;
                if (IsNegative(key)) return null;
            }

            var bytes = await preview.GetThumbnailBytesAsync(node, width, ct).ConfigureAwait(true);
            if (bytes is null)
            {
                lock (_lock) AddNegative(key);
                return null;
            }

            // 解码放后台线程，避免 UI 线程 DecodeToWidth 卡滚动
            var bitmap = await Task.Run(() =>
            {
                try
                {
                    using var ms = new MemoryStream(bytes);
                    return Bitmap.DecodeToWidth(ms, width);
                }
                catch
                {
                    return null;
                }
            }, ct).ConfigureAwait(true);

            if (bitmap is null)
            {
                lock (_lock) AddNegative(key);
                return null;
            }

            lock (_lock)
            {
                var cost = (long)bitmap.PixelSize.Width * bitmap.PixelSize.Height * 4;
                _cache[key] = new Entry(bitmap, cost);
                _lru.AddFirst(key);
                _cacheBytes += cost;
                TrimLocked();
            }

            return bitmap;
        }
        catch (OperationCanceledException)
        {
            return null;
        }
        catch
        {
            lock (_lock) AddNegative(key);
            return null;
        }
        finally
        {
            _gate.Release();
        }
    }

    private void TrimLocked()
    {
        while ((_cache.Count > MaxEntries || _cacheBytes > MaxCacheBytes) && _lru.Last is { } tail)
        {
            var key = tail.Value;
            _lru.RemoveLast();
            if (_cache.Remove(key, out var entry)) _cacheBytes -= entry.Cost;
        }
    }

    public void ClearCache()
    {
        lock (_lock)
        {
            _cache.Clear();
            _lru.Clear();
            _negative.Clear();
            _cacheBytes = 0;
        }
    }
}
