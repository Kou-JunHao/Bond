using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using Avalonia.Threading;
using Bond.Core;
using Bond.Models;
using Bond.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Bond.ViewModels;

/// <summary>
/// 在线文档页（task-56；task-138 起正文改为本地 Quill 富文本编辑器）。
/// 存储仍为 Markdown（后端 contentMd），编辑器内核由 <see cref="Editor.IDocumentEditorHost"/> 抽象，
/// 页面把内核的脏通知回写到 <see cref="EditorContent"/>，保存前再向内核取一次最新内容。
/// </summary>
public partial class DocsPageViewModel : ObservableObject
{
    private readonly IDocService _docs;
    private readonly IWorkspaceService _workspaces;
    private readonly INotificationCenter _notify;

    private readonly DispatcherTimer _autoSave;
    private bool _suppressDirty;
    private long _docId;
    private long _version;
    private bool _savingInFlight;

    /// <summary>由文档页注入：从编辑器内核取当前 Markdown（保存/导出前调用）。</summary>
    public Func<Task<string?>>? EditorMarkdownProvider { get; set; }

    /// <summary>请求把一段 Markdown 载入编辑器内核（打开/重载/恢复版本时触发）。</summary>
    public event EventHandler<string>? EditorLoadRequested;

    /// <summary>导出 PDF：页面用 WebView2 打印 HTML（服务端只提供 md/html）。</summary>
    public event EventHandler<(string Html, string FileName)>? PdfExportRequested;

    public DocsPageViewModel(IDocService docs, IWorkspaceService workspaces, INotificationCenter notify)
    {
        _docs = docs;
        _workspaces = workspaces;
        _notify = notify;
        _autoSave = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
        _autoSave.Tick += async (_, _) =>
        {
            _autoSave.Stop();
            await SaveAsync(force: false).ConfigureAwait(true);
        };

        Spaces.Add(new DocSpaceOption { WorkspaceId = null, Name = "个人空间" });
        SelectedSpace = Spaces[0];
        _ = InitAsync();
    }

    private async Task InitAsync()
    {
        await LoadWorkspacesAsync().ConfigureAwait(true);
        await RefreshAsync().ConfigureAwait(true);
    }

    private async Task LoadWorkspacesAsync()
    {
        try
        {
            await _workspaces.RefreshAsync().ConfigureAwait(true);
            foreach (var w in _workspaces.Workspaces)
            {
                Spaces.Add(new DocSpaceOption { WorkspaceId = long.TryParse(w.Id, out var wid) ? wid : null, Name = w.Name });
            }
        }
        catch (Exception)
        {
            // 工作区加载失败不影响个人空间文档
        }
    }

    // ── 列表 ─────────────────────────────────────────────
    public ObservableCollection<DocumentSummary> Documents { get; } = new();

    public ObservableCollection<DocSpaceOption> Spaces { get; } = new();

    [ObservableProperty]
    private DocSpaceOption? _selectedSpace;

    [ObservableProperty]
    private bool _isLoading;

    [ObservableProperty]
    private bool _hasError;

    [ObservableProperty]
    private string _errorText = "";

    [ObservableProperty]
    private string _newDocTitle = "";

    public bool IsEmpty => !IsLoading && !HasError && Documents.Count == 0;

    public async Task RefreshAsync()
    {
        IsLoading = true;
        HasError = false;
        ErrorText = "";
        OnPropertyChanged(nameof(IsEmpty));
        try
        {
            var list = await _docs.GetDocumentsAsync(SelectedSpace?.WorkspaceId, "/").ConfigureAwait(true);
            // task-135：逐项同步，避免 Clear 的 Reset 残留文档卡片。
            CollectionSync.Apply(
                Documents,
                list.OrderBy(d => d.Title, StringComparer.OrdinalIgnoreCase).ToList());

            if (_docs.LastError is { Length: > 0 } err)
            {
                HasError = true;
                ErrorText = err;
            }
        }
        catch (Exception ex)
        {
            HasError = true;
            ErrorText = ex.Message;
        }
        finally
        {
            IsLoading = false;
            OnPropertyChanged(nameof(IsEmpty));
        }
    }

    [RelayCommand]
    private async Task RetryAsync() => await RefreshAsync().ConfigureAwait(true);

    partial void OnSelectedSpaceChanged(DocSpaceOption? value) => _ = RefreshAsync();

    [RelayCommand]
    private async Task CreateAsync()
    {
        var title = string.IsNullOrWhiteSpace(NewDocTitle) ? "未命名文档" : NewDocTitle.Trim();
        try
        {
            var created = await _docs.CreateAsync(SelectedSpace?.WorkspaceId, "/", title, "# " + title + Environment.NewLine + Environment.NewLine).ConfigureAwait(true);
            NewDocTitle = "";
            await RefreshAsync().ConfigureAwait(true);
            await OpenAsync(created.Id).ConfigureAwait(true);
            _notify.Notify($"已新建文档《{created.Title}》");
        }
        catch (Exception ex)
        {
            _notify.Notify($"新建文档失败：{ex.Message}", NoticeKind.Error);
        }
    }

    [RelayCommand]
    private async Task OpenAsync(long id)
    {
        // task-138：切换文档前先把当前草稿落盘，避免「切文档丢稿」。
        if (IsEditorOpen && _docId != id)
        {
            await FlushAsync().ConfigureAwait(true);

            // 保存失败（例如冲突）时不要切走，停在当前文档让用户处理。
            if (SaveState == DocEditState.Dirty || Conflict is not null)
            {
                return;
            }
        }

        var detail = await _docs.GetAsync(id).ConfigureAwait(true);
        if (detail is null)
        {
            // task-151：统一失败文案，不再出现「未知错误」兜底。
            _notify.Notify($"打开文档失败：{LoadRetry.Humanize(_docs.LastError, "请稍后重试")}", NoticeKind.Error);
            return;
        }

        _suppressDirty = true;
        _docId = detail.Id;
        _version = detail.Version;
        EditorTitle = detail.Title;
        EditorContent = detail.ContentMd;
        LastEditorName = detail.LastEditorName ?? "";
        _suppressDirty = false;
        Conflict = null;
        SaveState = DocEditState.Saved;
        IsViewingVersion = false;
        // task-135：版本列表切文档时清空也要逐项移除（Reset 会残留版本行）。
        CollectionSync.Apply(Versions, Array.Empty<DocumentVersionItem>());
        IsVersionsOpen = false;
        IsDiffOpen = false;
        IsEditorOpen = true;
        RefreshWordCount();
        EditorLoadRequested?.Invoke(this, detail.ContentMd);
    }

    // ── 编辑器 ───────────────────────────────────────────
    [ObservableProperty]
    private bool _isEditorOpen;

    [ObservableProperty]
    private string _editorTitle = "";

    [ObservableProperty]
    private string _editorContent = "";

    [ObservableProperty]
    private long _documentId;

    [ObservableProperty]
    private int _wordCount;

    [ObservableProperty]
    private DocEditState _saveState = DocEditState.Saved;

    [ObservableProperty]
    private bool _isSaving;

    [ObservableProperty]
    private DocConflict? _conflict;

    [ObservableProperty]
    private string _lastEditorName = "";

    [ObservableProperty]
    private bool _isViewingVersion;

    [ObservableProperty]
    private string _viewingVersionText = "";

    public bool HasConflict => Conflict is not null;

    public string SaveStateText => SaveState switch
    {
        DocEditState.Saved => "已保存",
        DocEditState.Dirty => "未保存",
        DocEditState.Saving => "保存中…",
        DocEditState.Conflict => "有冲突",
        _ => "保存失败",
    };

    public string ConflictText => Conflict is null
        ? ""
        : $"服务器版本 v{Conflict.ServerVersion}" + (string.IsNullOrWhiteSpace(Conflict.LastEditorName) ? "" : $"（最后由 {Conflict.LastEditorName} 修改）");

    partial void OnEditorContentChanged(string value)
    {
        RefreshWordCount();
        MarkDirty();
    }

    partial void OnEditorTitleChanged(string value) => MarkDirty();

    partial void OnConflictChanged(DocConflict? value)
    {
        OnPropertyChanged(nameof(HasConflict));
        OnPropertyChanged(nameof(ConflictText));
    }

    partial void OnSaveStateChanged(DocEditState value) => OnPropertyChanged(nameof(SaveStateText));

    private void RefreshWordCount() => WordCount = (int)CountNonWhitespace(EditorContent);

    private static long CountNonWhitespace(string? text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return 0;
        }

        long n = 0;
        foreach (var ch in text)
        {
            if (!char.IsWhiteSpace(ch))
            {
                n++;
            }
        }

        return n;
    }

    private void MarkDirty()
    {
        if (_suppressDirty || IsViewingVersion)
        {
            return;
        }

        SaveState = DocEditState.Dirty;
        _autoSave.Stop();
        _autoSave.Start();
    }

    [RelayCommand]
    private async Task SaveNowAsync() => await SaveAsync(force: false).ConfigureAwait(true);

    private async Task SaveAsync(bool force)
    {
        if (!IsEditorOpen || _savingInFlight || IsViewingVersion)
        {
            return;
        }

        if (!force && SaveState is DocEditState.Saved or DocEditState.Saving)
        {
            return;
        }

        _savingInFlight = true;
        IsSaving = true;
        SaveState = DocEditState.Saving;
        try
        {
            var title = string.IsNullOrWhiteSpace(EditorTitle) ? "未命名文档" : EditorTitle.Trim();
            // task-138：保存前向编辑器内核取一次最新 Markdown，避免脏通知的防抖窗口丢字。
            var markdown = EditorContent;
            if (EditorMarkdownProvider is { } provider)
            {
                try
                {
                    markdown = await provider().ConfigureAwait(true) ?? markdown;
                }
                catch (Exception)
                {
                    // 取不到就用最后一次已知内容
                }
            }

            if (!string.Equals(markdown, EditorContent, StringComparison.Ordinal))
            {
                _suppressDirty = true;
                EditorContent = markdown;
                _suppressDirty = false;
                RefreshWordCount();
            }

            var result = await _docs.SaveAsync(_docId, title, EditorContent, _version, force).ConfigureAwait(true);
            if (result.Document is not null)
            {
                _version = result.Document.Version;
                Conflict = null;
                IsDiffOpen = false;
                SaveState = DocEditState.Saved;
                LastEditorName = result.Document.LastEditorName ?? LastEditorName;
                _notify.Notify("已保存");
            }
            else if (result.Conflict is not null)
            {
                Conflict = result.Conflict;
                SaveState = DocEditState.Conflict;
            }
            else
            {
                SaveState = DocEditState.Error;
                _notify.Notify($"保存失败：{LoadRetry.Humanize(result.Error, "请稍后重试")}", NoticeKind.Error);
            }
        }
        catch (Exception ex)
        {
            SaveState = DocEditState.Error;
            _notify.Notify($"保存失败：{ex.Message}", NoticeKind.Error);
        }
        finally
        {
            _savingInFlight = false;
            IsSaving = false;
        }
    }

    [RelayCommand]
    private async Task ForceOverwriteAsync() => await SaveAsync(force: true).ConfigureAwait(true);

    [RelayCommand]
    private async Task ReloadFromServerAsync()
    {
        if (_docId == 0)
        {
            return;
        }

        var detail = await _docs.GetAsync(_docId).ConfigureAwait(true);
        if (detail is null)
        {
            _notify.Notify("重新加载失败", NoticeKind.Error);
            return;
        }

        _suppressDirty = true;
        _version = detail.Version;
        EditorContent = detail.ContentMd;
        EditorTitle = detail.Title;
        _suppressDirty = false;
        Conflict = null;
        IsDiffOpen = false;
        SaveState = DocEditState.Saved;
        RefreshWordCount();
        EditorLoadRequested?.Invoke(this, EditorContent);
        _notify.Notify("已放弃本地修改并重新加载");
    }

    [RelayCommand]
    private async Task CloseEditorAsync()
    {
        _autoSave.Stop();
        if (SaveState == DocEditState.Dirty)
        {
            await SaveAsync(force: false).ConfigureAwait(true);
        }

        IsEditorOpen = false;
        IsVersionsOpen = false;
        IsViewingVersion = false;
    }

    /// <summary>task-138：编辑器内核推送的 Markdown 回写（触发字数统计与脏标记）。</summary>
    public void ApplyEditorMarkdown(string markdown)
    {
        if (IsViewingVersion || _suppressDirty)
        {
            return;
        }

        if (string.Equals(markdown, EditorContent, StringComparison.Ordinal))
        {
            return;
        }

        EditorContent = markdown;
    }

    /// <summary>task-138：离开编辑器（切页/关窗）前把内核内容强制落盘。</summary>
    public async Task FlushAsync()
    {
        _autoSave.Stop();
        if (!IsEditorOpen || IsViewingVersion)
        {
            return;
        }

        if (SaveState == DocEditState.Dirty)
        {
            await SaveAsync(force: false).ConfigureAwait(true);
        }
    }

    // ── 冲突差异（task-138：左右只读并排，不做 diff 算法） ──
    [ObservableProperty]
    private bool _isDiffOpen;

    [ObservableProperty]
    private string _diffMineText = "";

    [ObservableProperty]
    private string _diffServerText = "";

    [ObservableProperty]
    private string _diffServerCaption = "服务器版本";

    [RelayCommand]
    private async Task OpenDiffAsync()
    {
        if (_docId == 0)
        {
            return;
        }

        DiffMineText = EditorContent;
        DiffServerText = "正在加载服务器版本…";
        DiffServerCaption = Conflict is null ? "服务器版本（当前）" : $"服务器版本 v{Conflict.ServerVersion}";
        IsDiffOpen = true;

        var detail = await _docs.GetAsync(_docId).ConfigureAwait(true);
        DiffServerText = detail?.ContentMd ?? "（无法加载服务器版本）";
    }

    [RelayCommand]
    private void CloseDiff() => IsDiffOpen = false;

    // ── 版本历史 ─────────────────────────────────────────
    public ObservableCollection<DocumentVersionItem> Versions { get; } = new();

    [ObservableProperty]
    private bool _isVersionsOpen;

    [ObservableProperty]
    private bool _isVersionLoading;

    [RelayCommand]
    private async Task ToggleVersionsAsync()
    {
        if (IsVersionsOpen)
        {
            IsVersionsOpen = false;
            return;
        }

        IsVersionLoading = true;
        IsVersionsOpen = true;
        try
        {
            var list = await _docs.GetVersionsAsync(_docId).ConfigureAwait(true);
            // task-135：逐项同步（版本面板刷新同样不能 Clear）。
            CollectionSync.Apply(Versions, list);
        }
        catch (Exception ex)
        {
            _notify.Notify($"加载版本历史失败：{ex.Message}", NoticeKind.Error);
        }
        finally
        {
            IsVersionLoading = false;
        }
    }

    [RelayCommand]
    private void ViewVersion(DocumentVersionItem? item)
    {
        if (item is null)
        {
            return;
        }

        // 后端 versions/{version} 只回元数据（不含正文），因此这里给出只读说明，
        // 恢复后正文才会回到编辑器。
        IsViewingVersion = true;
        ViewingVersionText = $"正在查看历史版本 v{item.Version}（{item.TimeText} · {(string.IsNullOrWhiteSpace(item.EditorName) ? "未知编辑者" : item.EditorName)}）\n\n" +
                              "服务端版本接口只返回元数据，不返回该版本正文；如需查看或采用该版本内容，请点「恢复此版本」。";
    }

    [RelayCommand]
    private void CloseVersionView() => IsViewingVersion = false;

    [RelayCommand]
    private async Task RestoreVersionAsync(DocumentVersionItem? item)
    {
        if (item is null)
        {
            return;
        }

        // 二次确认：第一次点只挂起并提示，再点同一条才真正恢复。
        if (PendingRestoreVersion != item.Version)
        {
            PendingRestoreVersion = item.Version;
            _notify.Notify($"再点一次「恢复此版本」，确认用 v{item.Version} 覆盖当前文档");
            return;
        }

        PendingRestoreVersion = 0;
        try
        {
            var restored = await _docs.RestoreVersionAsync(_docId, item.Version).ConfigureAwait(true);
            if (restored is null)
            {
                _notify.Notify("恢复失败", NoticeKind.Error);
                return;
            }

            _suppressDirty = true;
            _version = restored.Version;
            EditorContent = restored.ContentMd;
            EditorTitle = restored.Title;
            _suppressDirty = false;
            IsViewingVersion = false;
            IsDiffOpen = false;
            SaveState = DocEditState.Saved;
            RefreshWordCount();
            IsVersionsOpen = false;
            EditorLoadRequested?.Invoke(this, EditorContent);
            await RefreshAsync().ConfigureAwait(true);
            _notify.Notify($"已恢复到版本 v{item.Version}");
        }
        catch (Exception ex)
        {
            _notify.Notify($"恢复失败：{ex.Message}", NoticeKind.Error);
        }
    }

    [ObservableProperty]
    private int _pendingRestoreVersion;

    // ── 导出 / 预览 ──────────────────────────────────────
    public event EventHandler<(string Format, byte[] Data, string FileName)>? ExportRequested;

    [RelayCommand]
    private async Task ExportMarkdownAsync() => await ExportAsync("md").ConfigureAwait(true);

    [RelayCommand]
    private async Task ExportHtmlAsync() => await ExportAsync("html").ConfigureAwait(true);

    private async Task ExportAsync(string format)
    {
        if (_docId == 0)
        {
            return;
        }

        if (SaveState == DocEditState.Dirty)
        {
            await SaveAsync(force: false).ConfigureAwait(true);
        }

        var (data, name) = await _docs.ExportAsync(_docId, format).ConfigureAwait(true);
        if (data is null)
        {
            _notify.Notify("导出失败", NoticeKind.Error);
            return;
        }

        var fallback = (string.IsNullOrWhiteSpace(EditorTitle) ? "document" : EditorTitle.Trim()) + "." + format;
        ExportRequested?.Invoke(this, (format, data, string.IsNullOrWhiteSpace(name) ? fallback : name!));
    }

    [RelayCommand]
    private async Task ExportPdfAsync()
    {
        if (_docId == 0)
        {
            return;
        }

        if (SaveState == DocEditState.Dirty)
        {
            await SaveAsync(force: false).ConfigureAwait(true);
        }

        // task-169：服务端 markdown→HTML 渲染器没有图片分支（导出 HTML 里没有 <img>），
        // 所以 PDF 改由客户端本地渲染（marked + 图片内联 data URI），再交给 WebView2 打印。
        var html = await BuildLocalRenderHtmlAsync(EditorTitle).ConfigureAwait(true);
        if (html is null)
        {
            var (data, _) = await _docs.ExportAsync(_docId, "html").ConfigureAwait(true);
            if (data is null)
            {
                _notify.Notify("导出 PDF 失败：无法获取文档内容", NoticeKind.Error);
                return;
            }

            html = Encoding.UTF8.GetString(data);
        }

        var name = (string.IsNullOrWhiteSpace(EditorTitle) ? "document" : EditorTitle.Trim()) + ".pdf";
        PdfExportRequested?.Invoke(this, (html, name));
    }

    /// <summary>task-138：文档页完成 PDF 导出后回报结果（成功给出路径，失败给出原因）。</summary>
    public void ReportPdfExport(string? path, string? error)
    {
        if (error is null && !string.IsNullOrWhiteSpace(path))
        {
            _notify.Notify($"已导出 PDF：{path}");
            return;
        }

        _notify.Notify($"导出 PDF 失败：{LoadRetry.Humanize(error, "请稍后重试")}", NoticeKind.Error);
    }

    [RelayCommand]
    private async Task PreviewInBrowserAsync()
    {
        if (_docId == 0)
        {
            return;
        }

        if (SaveState == DocEditState.Dirty)
        {
            await SaveAsync(force: false).ConfigureAwait(true);
        }

        // task-169：预览不再用服务端导出的 HTML（那里图片会被丢掉），改用本地渲染的自包含 HTML。
        byte[]? data = null;
        var localHtml = await BuildLocalRenderHtmlAsync(EditorTitle).ConfigureAwait(true);
        if (localHtml is not null)
        {
            data = Encoding.UTF8.GetBytes(localHtml);
        }
        else
        {
            (data, _) = await _docs.ExportAsync(_docId, "html").ConfigureAwait(true);
        }

        if (data is null)
        {
            _notify.Notify("预览失败：无法导出 HTML", NoticeKind.Error);
            return;
        }

        try
        {
            var dir = Path.Combine(Path.GetTempPath(), "bond-doc-preview");
            Directory.CreateDirectory(dir);
            var file = Path.Combine(dir, "preview.html");
            await File.WriteAllBytesAsync(file, data).ConfigureAwait(true);
            Process.Start(new ProcessStartInfo("explorer.exe", file) { UseShellExecute = true });
            _notify.Notify("已在浏览器中打开预览（包含图片，临时文件）");
        }
        catch (Exception ex)
        {
            _notify.Notify($"预览失败：{ex.Message}", NoticeKind.Error);
        }
    }

    // ── task-169：本地 Markdown→HTML 渲染（预览 / PDF 用，含图片） ──────────────

    private static readonly Regex MarkdownImageUrlPattern = new(
        @"!\[[^\]]*\]\(\s*(<[^>]+>|[^)\s]+)(?:\s+[""'][^""']*[""'])?\s*\)",
        RegexOptions.Compiled);

    private static readonly Regex HtmlImageUrlPattern = new(
        @"<img\b[^>]*?\bsrc\s*=\s*(?:""([^""]+)""|'([^']+)')",
        RegexOptions.Compiled | RegexOptions.IgnoreCase);

    private const int MaxInlineImageBytes = 20 * 1024 * 1024;

    /// <summary>
    /// 生成预览/PDF 用的自包含 HTML（task-169）：客户端自己把 Markdown 渲染成静态 HTML，
    /// 图片（本站鉴权地址 / 公网 https / data URL）预取后内联成 data URI。
    /// 之所以不用 marked+脚本渲染：WebView2 打印导出 PDF 时在 NavigationCompleted 就开始打印，
    /// 脚本渲染的内容可能还没落到版面，会出现「PDF 空白」；静态 HTML 没有这个时序问题。
    /// 返回 null 表示内容取不到（调用方回退到服务端导出 HTML）。
    /// </summary>
    private async Task<string?> BuildLocalRenderHtmlAsync(string? titleText)
    {
        string markdown = "";
        if (EditorMarkdownProvider is { } provider)
        {
            try
            {
                markdown = await provider().ConfigureAwait(true) ?? "";
            }
            catch (Exception)
            {
                markdown = "";
            }
        }

        if (markdown.Length == 0)
        {
            markdown = EditorContent;
        }

        if (markdown.Length == 0 && _docId != 0)
        {
            var detail = await _docs.GetAsync(_docId).ConfigureAwait(true);
            markdown = detail?.ContentMd ?? "";
        }

        if (markdown.Length == 0)
        {
            return null;
        }

        var title = string.IsNullOrWhiteSpace(titleText) ? "文档" : titleText.Trim();
        var imageMap = await InlineImagesAsync(markdown).ConfigureAwait(true);
        var bodyHtml = RenderMarkdownBody(markdown, imageMap);

        var html = new StringBuilder(bodyHtml.Length + 8192);
        html.Append("<!doctype html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n")
            .Append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            .Append("<title>").Append(HtmlEscape(title)).Append("</title>\n")
            .Append("<style>\n").Append(LocalPreviewCss).Append("\n</style>\n</head>\n<body>\n")
            .Append("<article class=\"bond-doc\">\n").Append(bodyHtml).Append("\n</article>\n")
            .Append("</body>\n</html>\n");
        return html.ToString();
    }

    /// <summary>
    /// 静态 Markdown→HTML（预览/PDF 用）：覆盖编辑器（turndown）产出的标题/段落/列表/引用/
    /// 代码/粗斜体/链接/图片；内嵌 HTML 一律转义（不执行脚本），图片使用内联 data URI。
    /// </summary>
    private static string RenderMarkdownBody(
        string markdown, IReadOnlyDictionary<string, string> images)
    {
        var output = new StringBuilder(markdown.Length + 1024);
        var paragraph = new StringBuilder();
        var code = new StringBuilder();
        string? listType = null;
        var inCode = false;

        void CloseList()
        {
            if (listType is not null)
            {
                output.Append("</").Append(listType).Append(">\n");
                listType = null;
            }
        }

        void FlushParagraph()
        {
            if (paragraph.Length == 0)
            {
                return;
            }

            output.Append("<p>").Append(RenderInline(paragraph.ToString(), images)).Append("</p>\n");
            paragraph.Clear();
        }

        var lines = markdown.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n');
        foreach (var line in lines)
        {
            var trimmed = line.Trim();
            if (inCode)
            {
                if (trimmed.StartsWith("```", StringComparison.Ordinal))
                {
                    output.Append("<pre><code>").Append(HtmlEscape(code.ToString())).Append("</code></pre>\n");
                    code.Clear();
                    inCode = false;
                }
                else
                {
                    code.Append(line).Append('\n');
                }

                continue;
            }

            if (trimmed.StartsWith("```", StringComparison.Ordinal))
            {
                FlushParagraph();
                CloseList();
                inCode = true;
                code.Clear();
                continue;
            }

            if (trimmed.Length == 0)
            {
                FlushParagraph();
                CloseList();
                continue;
            }

            var heading = HeadingPattern.Match(trimmed);
            if (heading.Success)
            {
                FlushParagraph();
                CloseList();
                var level = heading.Groups[1].Value.Length;
                output.Append("<h").Append(level).Append('>')
                    .Append(RenderInline(heading.Groups[2].Value, images))
                    .Append("</h").Append(level).Append(">\n");
                continue;
            }

            if (HorizontalRulePattern.IsMatch(trimmed))
            {
                FlushParagraph();
                CloseList();
                output.Append("<hr/>\n");
                continue;
            }

            if (trimmed.StartsWith('>'))
            {
                FlushParagraph();
                CloseList();
                output.Append("<blockquote><p>")
                    .Append(RenderInline(trimmed.TrimStart('>').Trim(), images))
                    .Append("</p></blockquote>\n");
                continue;
            }

            var bullet = BulletPattern.Match(trimmed);
            if (bullet.Success)
            {
                FlushParagraph();
                if (listType != "ul")
                {
                    CloseList();
                    output.Append("<ul>\n");
                    listType = "ul";
                }

                output.Append("<li>").Append(RenderInline(bullet.Groups[1].Value, images)).Append("</li>\n");
                continue;
            }

            var ordered = OrderedPattern.Match(trimmed);
            if (ordered.Success)
            {
                FlushParagraph();
                if (listType != "ol")
                {
                    CloseList();
                    output.Append("<ol>\n");
                    listType = "ol";
                }

                output.Append("<li>").Append(RenderInline(ordered.Groups[1].Value, images)).Append("</li>\n");
                continue;
            }

            CloseList();
            if (paragraph.Length > 0)
            {
                paragraph.Append(' ');
            }

            paragraph.Append(trimmed);
        }

        if (inCode)
        {
            output.Append("<pre><code>").Append(HtmlEscape(code.ToString())).Append("</code></pre>\n");
        }

        FlushParagraph();
        CloseList();
        return output.ToString();
    }

    private static readonly Regex HeadingPattern = new(@"^(#{1,6})\s+(.*)$", RegexOptions.Compiled);
    private static readonly Regex HorizontalRulePattern = new(@"^([-*_])\1{2,}\s*$", RegexOptions.Compiled);
    private static readonly Regex BulletPattern = new(@"^[-*+]\s+(.*)$", RegexOptions.Compiled);
    private static readonly Regex OrderedPattern = new(@"^\d+\.\s+(.*)$", RegexOptions.Compiled);
    private static readonly Regex InlineImagePattern = new(@"!\[([^\]]*)\]\(([^)\s]+)\)", RegexOptions.Compiled);
    private static readonly Regex InlineCodePattern = new("`([^`]*)`", RegexOptions.Compiled);
    private static readonly Regex InlineBoldPattern = new(@"\*\*([^*]+)\*\*", RegexOptions.Compiled);
    private static readonly Regex InlineItalicPattern = new(@"(?<!\*)\*([^*\n]+)\*(?!\*)", RegexOptions.Compiled);
    private static readonly Regex InlineLinkPattern = new(@"\[([^\]]+)\]\(([^)\s]+)\)", RegexOptions.Compiled);

    private static string RenderInline(
        string text, IReadOnlyDictionary<string, string> images)
    {
        if (string.IsNullOrEmpty(text))
        {
            return "";
        }

        var result = HtmlEscape(text);
        result = InlineImagePattern.Replace(result, match =>
        {
            var alt = match.Groups[1].Value;
            var rawUrl = match.Groups[2].Value.Replace("&amp;", "&", StringComparison.Ordinal);
            var absolute = ResolvePreviewImageUrl(rawUrl);
            if (absolute is null)
            {
                return "<span class=\"img-unsupported\">[图片不支持：仅支持公网 https 外链或本地内嵌图片]</span>";
            }

            var src = images.TryGetValue(absolute, out var inlined) ? inlined : absolute;
            return "<img src=\"" + HtmlEscape(src) + "\" alt=\"" + alt + "\" />";
        });
        result = InlineCodePattern.Replace(result, "<code>$1</code>");
        result = InlineBoldPattern.Replace(result, "<strong>$1</strong>");
        result = InlineItalicPattern.Replace(result, "<em>$1</em>");
        result = InlineLinkPattern.Replace(result, match =>
            "<a href=\"" + match.Groups[2].Value + "\">" + match.Groups[1].Value + "</a>");
        return result;
    }

    /// <summary>
    /// 收集 Markdown / 内嵌 HTML 里的图片地址。安全口径（task-169 收窄）：
    /// 只接受公网 http(s) 外链与 data:image/…;base64 内嵌图片；本站鉴权地址、相对路径、
    /// 本地文件一律不解析/不代取（预览里显示为「图片不支持」文字），不携带任何登录态。
    /// </summary>
    private static async Task<Dictionary<string, string>> InlineImagesAsync(string markdown)
    {
        var result = new Dictionary<string, string>(StringComparer.Ordinal);
        if (string.IsNullOrEmpty(markdown))
        {
            return result;
        }

        var sources = new HashSet<string>(StringComparer.Ordinal);
        foreach (Match match in MarkdownImageUrlPattern.Matches(markdown))
        {
            sources.Add(match.Groups[1].Value);
        }

        foreach (Match match in HtmlImageUrlPattern.Matches(markdown))
        {
            sources.Add(match.Groups[1].Success ? match.Groups[1].Value : match.Groups[2].Value);
        }

        if (sources.Count == 0)
        {
            return result;
        }

        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
        foreach (var source in sources)
        {
            var absolute = ResolvePreviewImageUrl(source);
            if (absolute is null || result.ContainsKey(absolute))
            {
                continue;
            }

            if (absolute.StartsWith("data:image/", StringComparison.OrdinalIgnoreCase))
            {
                result[absolute] = absolute;
                continue;
            }

            if (!Uri.TryCreate(absolute, UriKind.Absolute, out var uri)
                || uri.Scheme is not ("http" or "https"))
            {
                continue;
            }

            try
            {
                // 不附加 Authorization：公网图片按公网取，内网/鉴权资源不在支持范围内。
                using var response = await http
                    .SendAsync(new HttpRequestMessage(HttpMethod.Get, uri), HttpCompletionOption.ResponseHeadersRead)
                    .ConfigureAwait(false);
                if (!response.IsSuccessStatusCode)
                {
                    continue;
                }

                var mediaType = response.Content.Headers.ContentType?.MediaType;
                if (string.IsNullOrWhiteSpace(mediaType)
                    || !mediaType.StartsWith("image/", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                var bytes = await response.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
                if (bytes.Length == 0 || bytes.Length > MaxInlineImageBytes)
                {
                    continue;
                }

                result[absolute] = "data:" + mediaType + ";base64," + Convert.ToBase64String(bytes);
            }
            catch (Exception)
            {
                // 取不到就保留原地址（能直连的浏览器仍会尝试加载）
            }
        }

        return result;
    }

    /// <summary>
    /// 解析预览/PDF 支持的图片地址：仅 http(s) 绝对地址与 data:image/(png|jpeg|jpg|gif|webp|bmp);base64。
    /// 其他（/api/** 鉴权地址、相对路径、file:、本地盘符等）返回 null → 预览里显示「图片不支持」。
    /// </summary>
    private static string? ResolvePreviewImageUrl(string raw)
    {
        var value = (raw ?? "").Trim();
        if (value.StartsWith('<') && value.EndsWith('>') && value.Length > 2)
        {
            value = value[1..^1].Trim();
        }

        if (value.Length == 0)
        {
            return null;
        }

        if (value.StartsWith("data:image/", StringComparison.OrdinalIgnoreCase))
        {
            return IsSupportedDataImage(value) ? value : null;
        }

        if (!value.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
            && !value.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        // 2026-10-30 安全口径：即使是绝对 https，凡是本站后端鉴权地址（/api/** 或带 token=）也不渲染，
        // 避免受保护资源被当图床、短期凭证随文档/导出 PDF 长期留存。
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri))
        {
            return null;
        }

        var path = uri.AbsolutePath ?? "";
        if (path.Equals("/api", StringComparison.OrdinalIgnoreCase)
            || path.StartsWith("/api/", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        return uri.Query.Contains("token=", StringComparison.OrdinalIgnoreCase) ? null : value;
    }

    private static bool IsSupportedDataImage(string value)
        => Regex.IsMatch(
            value,
            "^data:image/(png|jpeg|jpg|gif|webp|bmp);base64,",
            RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static string HtmlEscape(string value)
        => (value ?? "")
            .Replace("&", "&amp;", StringComparison.Ordinal)
            .Replace("<", "&lt;", StringComparison.Ordinal)
            .Replace(">", "&gt;", StringComparison.Ordinal)
            .Replace("\"", "&quot;", StringComparison.Ordinal);

    private const string LocalPreviewCss = """
  :root { color-scheme: light; }
  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 0; background: #f6f2ec; color: #211f1c;
    font-family: "Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif;
    font-size: 15px; line-height: 1.75;
  }
  .bond-doc {
    max-width: 860px; margin: 28px auto; padding: 48px 56px 56px;
    background: #fff; border-radius: 14px; box-shadow: 0 10px 40px rgba(42, 32, 24, .10);
  }
  h1, h2, h3, h4, h5, h6 { color: #1b1a17; line-height: 1.3; margin: 1.3em 0 .55em; }
  h1 { font-size: 2em; } h2 { font-size: 1.55em; } h3 { font-size: 1.28em; } h4 { font-size: 1.12em; }
  p { margin: .7em 0; }
  img { max-width: 100%; height: auto; border-radius: 8px; display: block; margin: 14px auto; }
  a { color: #c8582c; }
  blockquote { margin: 1em 0; padding: .4em 1em; border-left: 3px solid #e8a75d; background: #fbf4ea; color: #5a5248; }
  pre { background: #f3efe8; border: 1px solid #e6ded2; border-radius: 10px; padding: 14px 16px; overflow: auto; }
  code { font-family: "Cascadia Mono", Consolas, monospace; font-size: .92em; background: #f3efe8; padding: 1px 5px; border-radius: 5px; }
  pre code { background: none; padding: 0; }
  table { border-collapse: collapse; width: 100%; margin: 1em 0; }
  th, td { border: 1px solid #e2d9cc; padding: 7px 10px; text-align: left; }
  th { background: #faf6f0; }
  hr { border: none; border-top: 1px solid #e2d9cc; margin: 1.6em 0; }
  ul, ol { padding-left: 1.6em; }
  li { margin: .28em 0; }
  .img-unsupported {
    display: inline-block; padding: 6px 10px; border-radius: 8px;
    background: #f3efe8; border: 1px dashed #d9cbb8; color: #8a7f72; font-size: 13px;
  }
  @page { size: A4; margin: 16mm 14mm; }
  @media print {
    body { background: #fff; }
    .bond-doc { max-width: none; margin: 0; padding: 0; box-shadow: none; border-radius: 0; }
    img { break-inside: avoid; }
    h1, h2, h3, h4 { break-after: avoid; }
    pre, blockquote, table { break-inside: avoid; }
  }
""";
}
