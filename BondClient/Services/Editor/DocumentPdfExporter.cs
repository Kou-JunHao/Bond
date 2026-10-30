using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Web.WebView2.Core;

namespace Bond.Services.Editor;

/// <summary>
/// task-138：把服务端导出的 HTML 用本机 WebView2 打印成 PDF（完全离线，不需要额外安装）。
/// 用一次性的隐藏窗口 + 一次性控制器，打印完立即释放，不污染编辑器实例。
/// 说明：<see cref="CoreWebView2.PrintToPdfStreamAsync"/> 直接返回 PDF 字节流，
/// 由我们写文件，避免依赖系统保存对话框（自动化验收与无人值守导出都更可靠）。
/// </summary>
public static class DocumentPdfExporter
{
    private const int WsPopup = unchecked((int)0x80000000);

    /// <summary>默认导出位置：系统「下载」目录，重名时自动追加序号。</summary>
    public static string DefaultTargetPath(string fileName)
    {
        var name = string.IsNullOrWhiteSpace(fileName) ? "文档" : Path.GetFileNameWithoutExtension(fileName.Trim());
        if (string.IsNullOrWhiteSpace(name))
        {
            name = "文档";
        }

        var downloads = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "Downloads");
        if (!Directory.Exists(downloads))
        {
            downloads = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
        }

        var candidate = Path.Combine(downloads, name + ".pdf");
        var index = 2;
        while (File.Exists(candidate))
        {
            candidate = Path.Combine(downloads, name + " (" + index + ").pdf");
            index++;
        }

        return candidate;
    }

    /// <summary>把 HTML 打印成 PDF 写到 <paramref name="targetPath"/>；成功返回 null，失败返回可展示的原因。</summary>
    public static async Task<string?> PrintAsync(string html, string targetPath, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(targetPath))
        {
            return "没有指定导出路径";
        }

        var tempHtml = Path.Combine(Path.GetTempPath(), "bond-doc-pdf-" + Guid.NewGuid().ToString("N") + ".html");
        var hwnd = IntPtr.Zero;
        CoreWebView2Controller? controller = null;
        try
        {
            var directory = Path.GetDirectoryName(targetPath);
            if (!string.IsNullOrEmpty(directory))
            {
                Directory.CreateDirectory(directory);
            }

            await File.WriteAllTextAsync(tempHtml, html, System.Text.Encoding.UTF8, ct).ConfigureAwait(true);

            hwnd = NativeMethods.CreateWindowEx(
                0,
                "STATIC",
                string.Empty,
                WsPopup,
                0,
                0,
                1024,
                768,
                IntPtr.Zero,
                IntPtr.Zero,
                IntPtr.Zero,
                IntPtr.Zero);
            if (hwnd == IntPtr.Zero)
            {
                return "无法创建打印窗口";
            }

            var userData = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Bond",
                "editor-webview2");
            Directory.CreateDirectory(userData);

            var env = await CoreWebView2Environment.CreateAsync(null, userData).ConfigureAwait(true);
            controller = await env.CreateCoreWebView2ControllerAsync(hwnd).ConfigureAwait(true);
            controller.IsVisible = false;
            controller.Bounds = new System.Drawing.Rectangle(0, 0, 1024, 768);
            var core = controller.CoreWebView2;

            var loaded = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            void OnCompleted(object? sender, CoreWebView2NavigationCompletedEventArgs args)
            {
                loaded.TrySetResult(args.IsSuccess);
            }

            core.NavigationCompleted += OnCompleted;
            try
            {
                core.Navigate("file:///" + tempHtml.Replace('\\', '/'));
                var finished = await Task.WhenAny(loaded.Task, Task.Delay(TimeSpan.FromSeconds(20), ct)).ConfigureAwait(true);
                if (finished != loaded.Task)
                {
                    return "加载导出内容超时";
                }

                if (!await loaded.Task.ConfigureAwait(true))
                {
                    return "加载导出内容失败";
                }
            }
            finally
            {
                core.NavigationCompleted -= OnCompleted;
            }

            await using var pdf = await core.PrintToPdfStreamAsync(null).ConfigureAwait(true);
            await using var file = File.Create(targetPath);
            await pdf.CopyToAsync(file, ct).ConfigureAwait(true);
            return new FileInfo(targetPath).Length > 0 ? null : "生成的 PDF 为空";
        }
        catch (Exception ex)
        {
            return ex.Message;
        }
        finally
        {
            try
            {
                controller?.Close();
            }
            catch (Exception)
            {
                // ignore
            }

            if (hwnd != IntPtr.Zero)
            {
                NativeMethods.DestroyWindow(hwnd);
            }

            try
            {
                File.Delete(tempHtml);
            }
            catch (Exception)
            {
                // 临时文件删不掉不影响结果
            }
        }
    }

    private static class NativeMethods
    {
        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern IntPtr CreateWindowEx(
            int exStyle,
            string className,
            string windowName,
            int style,
            int x,
            int y,
            int width,
            int height,
            IntPtr parent,
            IntPtr menu,
            IntPtr instance,
            IntPtr param);

        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool DestroyWindow(IntPtr hwnd);
    }
}
