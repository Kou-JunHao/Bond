using System;
using System.Collections.Generic;
using System.Threading;
using Docnet.Core;
using Docnet.Core.Models;
using Docnet.Core.Readers;

namespace Bond.Services;

/// <summary>
/// PDFium（Docnet.Core）的薄封装：打开 PDF、探测页数/页面比例、按目标像素宽度渲染单页。
/// 渲染结果是 BGRA32 原始字节（不透明），由视图侧拷进 WriteableBitmap。
///
/// task-95：PDFium 是 native 库，Docnet 的 <c>DocReader.Dispose()</c> 会走
/// <c>FPDFDOC_ExitFormFillEnvironment / FPDF_CloseDocument</c>。这类 native teardown 有两个硬性前提：
/// 1) 必须在**同一个线程**上完成（PDFium 不保证跨线程安全，Avalonia 的关闭回调发生在 UI 线程，
///    而打开/渲染发生在 <c>Task.Run</c> 的线程池线程上，跨线程释放会直接 AccessViolation）；
/// 2) 同一个 handle **只能释放一次**（重复释放 = use-after-free，同样在 native 层崩溃）。
/// 因此这里把所有 PDFium 调用（含释放）都串行到一条专用后台线程上，并保证「先创建新 reader、成功后再
/// 释放旧 reader」，任何失败路径都不会留下「已被释放但字段还指着它」的悬挂引用。
/// </summary>
public sealed class PdfRenderer : IDisposable
{
    // ── 全局 PDFium 线程：进程内所有 PdfRenderer 的 DocLib/DocReader 调用都在这条线程上执行 ──
    private static readonly object QueueLock = new();
    private static readonly Queue<Action> WorkItems = new();
    private static Thread? _pdfThread;

    private readonly byte[] _bytes;

    // 以下字段只在 PDFium 线程上读写；_disposed 由任意线程写入（volatile）。
    private IDocReader? _reader;
    private int _renderWidth;
    private int _renderHeight;
    private volatile bool _open;
    private volatile bool _disposed;

    public PdfRenderer(byte[] bytes) => _bytes = bytes;

    private volatile int _pageCount;

    /// <summary>页数（Open 成功后才有效）。</summary>
    public int PageCount => _pageCount;

    /// <summary>页面高/宽比（取第 1 页；用于换算渲染高度）。</summary>
    public double PageAspect { get; private set; } = 1.4142;

    public bool IsOpen => _open;

    public bool Open(out string? error)
    {
        if (_disposed)
        {
            error = "已关闭";
            return false;
        }

        string? failure = null;
        bool opened = RunOnPdfThread(() =>
        {
            if (_disposed) return false;

            try
            {
                // task-95：先创建新的 reader，成功后才释放旧的；旧代码是「先 Dispose 再创建」，
                // 一旦 GetDocReader 抛异常，_reader 就变成指向已释放对象的悬挂引用，
                // 关闭预览时再 Dispose 一次 → native 重复释放（崩溃栈正是 ExitFormFillEnvironment）。
                var next = DocLib.Instance.GetDocReader(_bytes, new PageDimensions(595, 842));
                var previous = _reader;
                _reader = next;
                previous?.Dispose();

                _pageCount = next.GetPageCount();
                if (PageCount <= 0)
                {
                    failure = "PDF 没有页面";
                    CloseReaderOnPdfThread();
                    return false;
                }

                using var first = next.GetPageReader(0);
                int w = Math.Max(1, first.GetPageWidth());
                int h = Math.Max(1, first.GetPageHeight());
                PageAspect = h / (double)w;
                _renderWidth = 0;
                _renderHeight = 0;
                _open = true;
                failure = null;
                return true;
            }
            catch (Exception ex)
            {
                failure = ex.Message;
                CloseReaderOnPdfThread();
                return false;
            }
        });

        error = opened ? null : (failure ?? "PDF 打开失败");
        return opened;
    }

    /// <summary>按目标像素宽度渲染第 pageIndex 页；失败返回 null。</summary>
    public (byte[] Bytes, int Width, int Height)? RenderPage(int pageIndex, int targetPixelWidth)
    {
        if (_disposed) return null;

        return RunOnPdfThread<(byte[] Bytes, int Width, int Height)?>(() =>
        {
            if (_disposed || !_open || _reader is null || pageIndex < 0 || pageIndex >= _pageCount)
                return null;

            try
            {
                int width = Math.Clamp(targetPixelWidth, 64, 6000);
                int height = Math.Max(64, (int)Math.Round(width * PageAspect));

                if (_renderWidth != width || _renderHeight != height)
                {
                    // task-95：同样「先创建后释放」，失败时保留旧 reader 而不是留下悬挂引用。
                    var next = DocLib.Instance.GetDocReader(_bytes, new PageDimensions(width, height));
                    var previous = _reader;
                    _reader = next;
                    previous?.Dispose();
                    _renderWidth = width;
                    _renderHeight = height;
                }

                using var page = _reader.GetPageReader(pageIndex);
                var bytes = page.GetImage(RenderFlags.RenderAnnotations);
                if (bytes is null || bytes.Length == 0) return null;

                return (bytes, page.GetPageWidth(), page.GetPageHeight());
            }
            catch
            {
                return null;
            }
        });
    }

    /// <summary>
    /// 释放 native 资源。调用方可能在任何线程（含 UI 线程）——这里只把 teardown 排进 PDFium 线程队列，
    /// 不在调用线程上做 native 操作，避免跨线程释放；队列 FIFO 保证在它之前排队的渲染先跑完。
    /// 释放本身幂等：<see cref="_disposed"/> 置位后不再接受新的打开/渲染请求。
    /// </summary>
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;

        EnqueuePdfWork(() =>
        {
            CloseReaderOnPdfThread();
            _pageCount = 0;
            _open = false;
            _renderWidth = 0;
            _renderHeight = 0;
        });
    }

    private void CloseReaderOnPdfThread()
    {
        var reader = _reader;
        _reader = null;
        _open = false;
        try
        {
            reader?.Dispose();
        }
        catch
        {
            // 释放失败不再重试：重复释放比泄漏更危险（native AV）。
        }
    }

    // ── PDFium 单线程执行器 ──────────────────────────────────────────────

    private static void EnqueuePdfWork(Action action)
    {
        lock (QueueLock)
        {
            WorkItems.Enqueue(action);
            if (_pdfThread is null or { IsAlive: false })
            {
                _pdfThread = new Thread(Pump)
                {
                    IsBackground = true,
                    Name = "Bond-Pdfium",
                };
                _pdfThread.Start();
            }

            Monitor.Pulse(QueueLock);
        }
    }

    private static void Pump()
    {
        while (true)
        {
            Action? item;
            lock (QueueLock)
            {
                while (WorkItems.Count == 0) Monitor.Wait(QueueLock);
                item = WorkItems.Dequeue();
            }

            try
            {
                item();
            }
            catch
            {
                // 单个操作失败不能打死 PDFium 线程；错误的语义由各自方法内的 catch 负责。
            }
        }
    }

    private static void RunOnPdfThread(Action action)
        => RunOnPdfThread(() =>
        {
            action();
            return true;
        });

    private static T RunOnPdfThread<T>(Func<T> func)
    {
        T result = default!;
        using var done = new ManualResetEventSlim(false);
        EnqueuePdfWork(() =>
        {
            try
            {
                result = func();
            }
            finally
            {
                done.Set();
            }
        });
        done.Wait();
        return result;
    }
}
