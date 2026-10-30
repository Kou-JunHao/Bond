using System;
using System.Threading.Tasks;

namespace Bond.Services.Editor;

/// <summary>
/// 文档编辑器宿主抽象（task-138）。
/// 目前只有 WebView2 + 本地 Quill 一个实现；把它抽出来是为了将来换编辑器内核
/// （Web 端 / 原生富文本）时不需要重写文档页与保存流程。
/// 约定：所有方法都在 UI 线程调用；<see cref="MarkdownChanged"/> 的载荷是 Markdown 文本。
/// </summary>
public interface IDocumentEditorHost
{
    /// <summary>内核是否已就绪（可以加载/读取内容）。</summary>
    bool IsReady { get; }

    /// <summary>初始化失败时的可展示原因（缺 WebView2 Runtime、资源缺失等），未失败为 null。</summary>
    string? FailureMessage { get; }

    /// <summary>内核就绪（脚本 ready 消息已收到）。</summary>
    event EventHandler? Ready;

    /// <summary>内容被编辑（来自内核的防抖通知），载荷为 Markdown。</summary>
    event EventHandler<string>? MarkdownChanged;

    /// <summary>用户在内核里按了 Ctrl+S。</summary>
    event EventHandler? SaveRequested;

    /// <summary>初始化失败或运行期内核报错，载荷为可展示原因。</summary>
    event EventHandler<string>? Failed;

    /// <summary>把 Markdown 载入编辑器（内部转 HTML；会抑制脏标记）。</summary>
    Task LoadMarkdownAsync(string markdown);

    /// <summary>读取当前 Markdown（内核未就绪时返回最近一次已知内容）。</summary>
    Task<string?> GetMarkdownAsync();

    /// <summary>让编辑器获得焦点。</summary>
    void FocusEditor();

    /// <summary>切换明暗主题。</summary>
    Task SetDarkThemeAsync(bool dark);
}
