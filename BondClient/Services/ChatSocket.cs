using System;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Bond.Models;

namespace Bond.Services;

/// <summary>
/// 聊天 WebSocket 客户端：<c>wss://&lt;host&gt;/ws/chat?token=&lt;JWT&gt;</c>（注意不走 <c>/api</c> 前缀）。
/// <para>心跳 30s、60s 无帧判死、指数退避重连 1/2/4/8/15/30s。</para>
/// <para>token 只在内存中拼进握手 URL，不落盘、不打印、不进日志。</para>
/// </summary>
public sealed class ChatSocket : IAsyncDisposable
{
    /// <summary>心跳间隔（服务端 60s 无帧判死，客户端每 30s 发 ping）。task-151：口径来自 <see cref="RetryPolicy"/>。</summary>
    public static readonly int PingIntervalMs = (int)RetryPolicy.SocketPingInterval.TotalMilliseconds;

    /// <summary>超过该时长没收到任何帧就认为连接已死（半开连接）。task-151：口径来自 <see cref="RetryPolicy"/>。</summary>
    public static readonly int IdleTimeoutMs = (int)RetryPolicy.SocketIdleTimeout.TotalMilliseconds;

    /// <summary>task-151：重连退避（1/2/4/8/15/30s）统一由 <see cref="RetryPolicy"/> 定义，这里不再保留第二份数组。</summary>
    private static TimeSpan ReconnectDelay(int attempt)
        => RetryPolicy.DelayFor(RetryScenario.SocketReconnect, attempt);

    private readonly Func<string> _baseUrl;
    private readonly Func<string?> _tokenProvider;
    private readonly Action<ChatConnectionState> _stateHandler;
    private readonly Action<string> _frameHandler;
    private readonly Action<string> _log;
    private readonly CancellationTokenSource _disposeCts = new();

    private Task? _loop;
    private ClientWebSocket? _socket;
    private long _lastInboundTicks = Environment.TickCount64;

    public ChatSocket(
        Func<string> baseUrl,
        Func<string?> tokenProvider,
        Action<ChatConnectionState> stateHandler,
        Action<string> frameHandler,
        Action<string>? log = null)
    {
        _baseUrl = baseUrl;
        _tokenProvider = tokenProvider;
        _stateHandler = stateHandler;
        _frameHandler = frameHandler;
        _log = log ?? (message => System.Diagnostics.Debug.WriteLine($"[chat-ws] {message}"));
    }

    public ChatConnectionState State { get; private set; } = ChatConnectionState.Offline;

    /// <summary>启动后台连接循环（幂等）。</summary>
    public void Start()
    {
        if (_loop is not null)
        {
            return;
        }

        _loop = Task.Run(() => RunAsync(_disposeCts.Token));
    }

    /// <summary>主动断开（登出、换账号时调用）。</summary>
    public async Task StopAsync()
    {
        try
        {
            _disposeCts.Cancel();
        }
        catch (ObjectDisposedException)
        {
            // 已释放
        }

        var socket = _socket;
        if (socket is not null)
        {
            try
            {
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", timeout.Token).ConfigureAwait(false);
            }
            catch
            {
                // 主动关闭失败无所谓，下面是 Abort
            }
        }

        if (_loop is { } loop)
        {
            try
            {
                await loop.ConfigureAwait(false);
            }
            catch
            {
                // 循环内的异常已经各自处理
            }
        }

        _loop = null;
        SetState(ChatConnectionState.Offline);
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync().ConfigureAwait(false);
        _disposeCts.Dispose();
    }

    private void SetState(ChatConnectionState state)
    {
        if (State == state)
        {
            return;
        }

        State = state;
        try
        {
            _stateHandler(state);
        }
        catch (Exception ex)
        {
            _log($"state handler failed: {ex.GetType().Name}");
        }
    }

    private async Task RunAsync(CancellationToken ct)
    {
        var attempt = 0;
        while (!ct.IsCancellationRequested)
        {
            var token = _tokenProvider();
            if (string.IsNullOrWhiteSpace(token))
            {
                SetState(ChatConnectionState.Offline);
                if (!await DelayAsync(TimeSpan.FromSeconds(15), ct).ConfigureAwait(false))
                {
                    break;
                }

                continue;
            }

            SetState(attempt == 0 ? ChatConnectionState.Connecting : ChatConnectionState.Polling);

            var connected = false;
            ClientWebSocket? socket = null;
            try
            {
                socket = new ClientWebSocket();
                socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(20);
                using var connectTimeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
                // task-151：连接超时口径来自 RetryPolicy（15s），不再散落在调用点。
                connectTimeout.CancelAfter(RetryPolicy.SocketConnectTimeout);
                await socket.ConnectAsync(BuildUri(token), connectTimeout.Token).ConfigureAwait(false);

                _socket = socket;
                _lastInboundTicks = Environment.TickCount64;
                connected = true;
                attempt = 0;
                SetState(ChatConnectionState.Connected);
                await PumpAsync(socket, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                socket?.Dispose();
                break;
            }
            catch (Exception ex)
            {
                // 401（token 失效）也走退避重连：REST 侧刷新 token 后自然恢复。
                _log($"connect/pump failed: {ex.GetType().Name} {DescribeError(ex)}");
            }
            finally
            {
                _socket = null;
                socket?.Dispose();
            }

            if (ct.IsCancellationRequested)
            {
                break;
            }

            SetState(ChatConnectionState.Polling);

            if (connected)
            {
                // 连接曾建立过：断线后立即重试一次（1s），之后按退避升级。
                attempt = Math.Max(attempt, 1);
            }

            var delay = ReconnectDelay(attempt);
            attempt++;
            if (!await DelayAsync(delay, ct).ConfigureAwait(false))
            {
                break;
            }
        }

        SetState(ChatConnectionState.Offline);
    }

    /// <summary>连接建立后跑收发/心跳/判死，直到连接断开。</summary>
    private async Task PumpAsync(ClientWebSocket socket, CancellationToken ct)
    {
        using var pumpCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var pumpToken = pumpCts.Token;

        var receiveTask = ReceiveLoopAsync(socket, pumpToken);
        var pingTask = PingLoopAsync(socket, pumpToken);
        var watchdogTask = IdleWatchdogAsync(socket, pumpToken);

        try
        {
            await receiveTask.ConfigureAwait(false);
        }
        finally
        {
            try
            {
                pumpCts.Cancel();
            }
            catch (ObjectDisposedException)
            {
                // ignore
            }

            await IgnoreAsync(pingTask).ConfigureAwait(false);
            await IgnoreAsync(watchdogTask).ConfigureAwait(false);
        }
    }

    private async Task ReceiveLoopAsync(ClientWebSocket socket, CancellationToken ct)
    {
        var buffer = new byte[16 * 1024];
        using var message = new MemoryStream();
        try
        {
            while (!ct.IsCancellationRequested && socket.State == WebSocketState.Open)
            {
                var result = await socket
                    .ReceiveAsync(new ArraySegment<byte>(buffer), ct)
                    .ConfigureAwait(false);

                if (result.MessageType == WebSocketMessageType.Close)
                {
                    try
                    {
                        await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None)
                            .ConfigureAwait(false);
                    }
                    catch
                    {
                        // server 已关闭，忽略
                    }

                    return;
                }

                if (result.MessageType != WebSocketMessageType.Text)
                {
                    continue;
                }

                message.Write(buffer, 0, result.Count);
                if (!result.EndOfMessage)
                {
                    continue;
                }

                _lastInboundTicks = Environment.TickCount64;
                var frame = Encoding.UTF8.GetString(message.GetBuffer(), 0, (int)message.Length);
                message.SetLength(0);
                if (frame.Length == 0)
                {
                    continue;
                }

                try
                {
                    _frameHandler(frame);
                }
                catch (Exception ex)
                {
                    _log($"frame handler failed: {ex.GetType().Name}");
                }
            }
        }
        catch (OperationCanceledException)
        {
            // 主动停止
        }
        catch (WebSocketException ex)
        {
            _log($"receive failed: {ex.GetType().Name} {DescribeError(ex)}");
        }
        catch (Exception ex) when (ex is ObjectDisposedException or InvalidOperationException)
        {
            // socket 被 watchdog Abort
        }
    }

    private async Task PingLoopAsync(ClientWebSocket socket, CancellationToken ct)
    {
        var payload = Encoding.UTF8.GetBytes("{\"t\":\"ping\"}");
        try
        {
            while (!ct.IsCancellationRequested && socket.State == WebSocketState.Open)
            {
                await Task.Delay(PingIntervalMs, ct).ConfigureAwait(false);
                if (socket.State != WebSocketState.Open)
                {
                    return;
                }

                await socket
                    .SendAsync(new ArraySegment<byte>(payload), WebSocketMessageType.Text, true, ct)
                    .ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
            // 主动停止
        }
        catch (Exception ex) when (ex is WebSocketException or ObjectDisposedException or InvalidOperationException)
        {
            // 发送失败说明连接已坏：让 receive 循环结束，触发重连。
            try
            {
                socket.Abort();
            }
            catch
            {
                // ignore
            }
        }
    }

    private async Task IdleWatchdogAsync(ClientWebSocket socket, CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                await Task.Delay(5_000, ct).ConfigureAwait(false);
                var idle = Environment.TickCount64 - _lastInboundTicks;
                if (idle < IdleTimeoutMs)
                {
                    continue;
                }

                _log($"connection idle for {idle}ms, aborting");
                try
                {
                    socket.Abort();
                }
                catch
                {
                    // ignore
                }

                return;
            }
        }
        catch (OperationCanceledException)
        {
            // 主动停止
        }
    }

    private Uri BuildUri(string token)
    {
        var baseUrl = _baseUrl().TrimEnd('/');
        var uri = new Uri(baseUrl, UriKind.Absolute);
        var scheme = uri.Scheme.Equals("https", StringComparison.OrdinalIgnoreCase) ? "wss" : "ws";
        var builder = new UriBuilder(uri)
        {
            Scheme = scheme,
            Path = "/ws/chat",
            Query = $"token={Uri.EscapeDataString(token)}"
        };
        return builder.Uri;
    }

    private static async Task IgnoreAsync(Task task)
    {
        try
        {
            await task.ConfigureAwait(false);
        }
        catch
        {
            // 已记录/已取消
        }
    }

    private static async Task<bool> DelayAsync(TimeSpan delay, CancellationToken ct)
    {
        try
        {
            await Task.Delay(delay, ct).ConfigureAwait(false);
            return true;
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }

    /// <summary>只报告错误类型和状态码，绝不带出含 token 的 URL。</summary>
    private static string DescribeError(Exception ex)
    {
        var text = ex.Message ?? "";
        var marker = text.IndexOf("status code", StringComparison.OrdinalIgnoreCase);
        return marker >= 0 ? text[marker..] : "";
    }
}
