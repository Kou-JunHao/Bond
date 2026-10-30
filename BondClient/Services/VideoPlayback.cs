using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using LibVLCSharp.Shared;

namespace Bond.Services;

/// <summary>
/// LibVLC 帧回调播放器：解码到托管 BGRA 缓冲区，由 UI 侧写入 WriteableBitmap。
/// 只随包分发 win-x64 的 libvlc；运行库缺失时 <see cref="TryInitialize"/> 返回 false，
/// 预览面板回退为"用系统播放器打开"。
/// </summary>
public sealed class VideoPlayback : IDisposable
{
    private const string NativeFolder = "libvlc\\win-x64";

    private static readonly object InitLock = new();
    private static bool _initialized;
    private static string? _initError;

    private readonly object _frameGate = new();
    private LibVLC? _libVlc;
    private MediaPlayer? _player;
    private Media? _media;
    private IntPtr _frameBuffer;
    private byte[] _frameData = [];
    private int _bufferSize;
    private bool _disposed;

    /// <summary>确认 libvlc 运行库可用（只做一次）。</summary>
    public static bool TryInitialize(out string? error)
    {
        lock (InitLock)
        {
            if (_initialized)
            {
                error = _initError;
                return _initError is null;
            }

            try
            {
                var folder = Path.Combine(AppContext.BaseDirectory, "libvlc", "win-x64");
                if (!File.Exists(Path.Combine(folder, "libvlc.dll")))
                {
                    _initError = "未找到 libvlc 运行库（libvlc/win-x64/libvlc.dll）";
                }
                else
                {
                    // 明确写全名：本文件在 Bond.* 命名空间下，直接写 Core 会解析成 Bond.Core。
                    LibVLCSharp.Shared.Core.Initialize(folder);
                    _initError = null;
                }
            }
            catch (Exception ex)
            {
                _initError = $"libvlc 初始化失败：{ex.Message}";
            }

            _initialized = true;
            error = _initError;
            return _initError is null;
        }
    }

    public int Width { get; private set; } = 1280;

    public int Height { get; private set; } = 720;

    public bool IsPlaying => _player?.IsPlaying == true;

    public long DurationMs => _player?.Length ?? 0;

    /// <summary>帧缓冲区就绪（在调用线程上触发，UI 侧需在 UI 线程创建位图）。</summary>
    public event Action<int, int>? FormatReady;

    /// <summary>新帧（VLC 线程回调；data 为复用缓冲区，同步拷走）。</summary>
    public event Action<byte[]>? FrameArrived;

    public event Action<bool>? StateChanged;

    public event Action<float>? PositionChanged;

    public event Action<long, long>? TimeChanged;

    public event Action? PlaybackEnded;

    public event Action<string>? Failed;

    /// <summary>开始播放指定 URL（服务端 content 接口，带 token 查询串）。</summary>
    public async Task<bool> StartAsync(string url, CancellationToken ct = default)
    {
        if (_disposed) return false;
        if (!TryInitialize(out var error))
        {
            Failed?.Invoke(error ?? "libvlc 不可用");
            return false;
        }

        try
        {
            _libVlc = new LibVLC("--no-video-title-show", "--quiet");
            _player = new MediaPlayer(_libVlc) { EnableKeyInput = false, EnableMouseInput = false };
            _media = new Media(_libVlc, url, FromType.FromLocation);

            // 先解析媒体拿分辨率；解析失败就按 1280x720 播放（VLC 会缩放）。
            var parsed = await _media.Parse(MediaParseOptions.ParseNetwork, 4000, ct).ConfigureAwait(true);
            var (w, h) = ResolveSize();
            Width = w;
            Height = h;

            _bufferSize = Width * Height * 4;
            _frameData = new byte[_bufferSize];
            _frameBuffer = Marshal.AllocHGlobal(_bufferSize);

            _player.SetVideoFormat("RV32", (uint)Width, (uint)Height, (uint)(Width * 4));
            _player.SetVideoCallbacks(OnLock, OnUnlock, OnDisplay);

            _player.Playing += (_, _) => StateChanged?.Invoke(true);
            _player.Paused += (_, _) => StateChanged?.Invoke(false);
            _player.Stopped += (_, _) => StateChanged?.Invoke(false);
            _player.EndReached += (_, _) => PlaybackEnded?.Invoke();
            _player.EncounteredError += (_, _) => Failed?.Invoke("播放失败：VLC 无法解码该视频");
            _player.TimeChanged += (_, e) => TimeChanged?.Invoke(e.Time, _player?.Length ?? 0);
            _player.PositionChanged += (_, e) => PositionChanged?.Invoke(e.Position);

            FormatReady?.Invoke(Width, Height);
            _player.Play(_media);

            // 音量给默认 80，倍速 1.0。
            _player.Volume = 80;
            return true;
        }
        catch (Exception ex)
        {
            Failed?.Invoke($"播放器启动失败：{ex.Message}");
            return false;
        }
    }

    private (int W, int H) ResolveSize()
    {
        try
        {
            foreach (var track in _media?.Tracks ?? [])
            {
                if (track.TrackType != TrackType.Video) continue;
                var v = track.Data.Video;
                if (v.Width > 0 && v.Height > 0) return ((int)v.Width, (int)v.Height);
            }
        }
        catch
        {
            // 解析失败走默认尺寸。
        }

        return (1280, 720);
    }

    private IntPtr OnLock(IntPtr opaque, IntPtr planes)
    {
        if (planes != IntPtr.Zero && _frameBuffer != IntPtr.Zero)
            Marshal.WriteIntPtr(planes, 0, _frameBuffer);
        return _frameBuffer;
    }

    private void OnUnlock(IntPtr opaque, IntPtr picture, IntPtr planes)
    {
    }

    private void OnDisplay(IntPtr opaque, IntPtr picture)
    {
        if (_disposed || _frameBuffer == IntPtr.Zero || _bufferSize <= 0) return;

        // 上一帧还没拷完就丢帧，避免阻塞 VLC 线程。
        if (!Monitor.TryEnter(_frameGate)) return;
        try
        {
            Marshal.Copy(_frameBuffer, _frameData, 0, _bufferSize);
            FrameArrived?.Invoke(_frameData);
        }
        catch
        {
            // 渲染回调里的异常不能扩散到 native。
        }
        finally
        {
            Monitor.Exit(_frameGate);
        }
    }

    public void TogglePause()
    {
        if (_player is null) return;

        // 停住后位置在结尾（播完 / 还没开始）时，点播放从头开始。
        var position = _player.Position;
        if (!_player.IsPlaying && (position <= 0.0001f || position >= 0.999f))
            _player.Position = 0f;

        _player.SetPause(_player.IsPlaying);
    }

    public void Seek(float position)
    {
        if (_player is null) return;
        var p = Math.Clamp(position, 0f, 1f);
        _player.Position = p;
    }

    public void SetVolume(int volume)
    {
        if (_player is null) return;
        _player.Volume = Math.Clamp(volume, 0, 100);
    }

    public void SetRate(float rate)
    {
        if (_player is null) return;
        _player.SetRate(Math.Clamp(rate, 0.25f, 4f));
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;

        try
        {
            if (_player is not null)
            {
                _player.Stop();
                _player.Dispose();
            }
        }
        catch
        {
            // 忽略关闭期异常。
        }

        _media?.Dispose();
        _libVlc?.Dispose();

        if (_frameBuffer != IntPtr.Zero)
        {
            Marshal.FreeHGlobal(_frameBuffer);
            _frameBuffer = IntPtr.Zero;
        }

        _player = null;
        _media = null;
        _libVlc = null;
    }
}
