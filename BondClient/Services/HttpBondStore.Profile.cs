using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Media.Imaging;

namespace Bond.Services;

/// <summary>
/// task-153 / task-155：设置页「个人资料」写操作成功后，把新昵称/头像同步到会话层，
/// 让标题栏、账户区等所有绑定 <see cref="ISessionService"/> 的地方立即刷新
/// （而不是等下一次 Bootstrap 或重启）。
/// <para>task-155：头像改为「URL + 内存位图缓存」——URL 自带 <c>?v=</c>，同一 URL 可长期缓存；
/// 换头像必须用后端返回的新 URL（旧 v 会 404），不自己拼 v。ViewModel 不持有图片字节。</para>
/// </summary>
public sealed partial class HttpBondStore
{
    /// <summary>当前头像 URL → 已解码位图。只保留最近一个 URL，避免长时间占内存。</summary>
    private readonly Dictionary<string, Bitmap?> _avatarCache = new(StringComparer.Ordinal);

    /// <summary>在 UI 线程调用：昵称/头像更新后广播 Changed。</summary>
    public void ApplyProfile(string? nickname, string? avatarUrl)
    {
        var name = string.IsNullOrWhiteSpace(nickname) ? null : nickname!.Trim();
        if (!string.IsNullOrEmpty(name))
            _accountName = name;

        if (!string.IsNullOrWhiteSpace(avatarUrl)
            && !string.Equals(avatarUrl, AvatarUrl, StringComparison.Ordinal))
        {
            AvatarUrl = avatarUrl;
            // 头像是附加信息：下载失败保持空，界面回退首字母。
            _ = LoadAvatarAsync();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>
    /// task-155：把头像切到指定 URL 并加载位图。传 null/空 = 清空（回退首字母）。
    /// <para>返回是否加载到位图；加载失败不抛异常，只把 <see cref="AvatarSource"/> 置空。</para>
    /// </summary>
    public async Task<bool> RefreshAvatarAsync(string? avatarUrl)
    {
        var url = string.IsNullOrWhiteSpace(avatarUrl) ? null : avatarUrl!.Trim();

        if (string.IsNullOrEmpty(url))
        {
            ClearAvatar();
            return false;
        }

        // 代际号：账号切换（ResetSessionState）后晚到的旧头像直接丢弃，避免串号。
        var generation = Volatile.Read(ref _sessionGeneration);

        if (_avatarCache.TryGetValue(url, out var cached) && cached is not null)
        {
            if (generation != Volatile.Read(ref _sessionGeneration)) return false;
            AvatarUrl = url;
            AvatarSource = cached;
            Changed?.Invoke(this, EventArgs.Empty);
            return true;
        }

        AvatarUrl = url;
        var bytes = await _api.GetBytesAsync(url).ConfigureAwait(false);
        if (bytes is null || bytes.Length == 0) return false;
        if (generation != Volatile.Read(ref _sessionGeneration)) return false;

        Bitmap? bitmap = null;
        try
        {
            using var ms = new MemoryStream(bytes);
            bitmap = new Bitmap(ms);
        }
        catch
        {
            bitmap = null; // 坏图不显示裂图，回退首字母。
        }

        if (bitmap is null)
        {
            AvatarSource = null;
            Changed?.Invoke(this, EventArgs.Empty);
            return false;
        }

        _avatarCache.Clear();
        _avatarCache[url] = bitmap;
        AvatarUrl = url;
        AvatarSource = bitmap;
        Changed?.Invoke(this, EventArgs.Empty);
        return true;
    }

    /// <summary>删除头像后调用：清 URL、清缓存、界面回退首字母。</summary>
    public void ClearAvatar()
    {
        _avatarCache.Clear();
        AvatarUrl = null;
        AvatarSource = null;
        Changed?.Invoke(this, EventArgs.Empty);
    }
}
