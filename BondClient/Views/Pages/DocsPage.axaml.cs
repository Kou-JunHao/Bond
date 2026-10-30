using System;
using System.IO;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;
using Bond.Services.Editor;
using Bond.ViewModels;

namespace Bond.Views.Pages;

public partial class DocsPage : UserControl
{
    private DocsPageViewModel? _vm;
    private bool _editorWired;
    private bool _suppressLoad;

    public DocsPage()
    {
        InitializeComponent();
        AddHandler(KeyDownEvent, OnPageKeyDown, RoutingStrategies.Tunnel);
        WireEditor();
    }

    protected override void OnDataContextChanged(EventArgs e)
    {
        base.OnDataContextChanged(e);
        Detach();
        if (DataContext is DocsPageViewModel vm)
        {
            _vm = vm;
            vm.ExportRequested += OnExportRequested;
            vm.EditorLoadRequested += OnEditorLoadRequested;
            vm.PdfExportRequested += OnPdfExportRequested;
            vm.EditorMarkdownProvider = GetEditorMarkdownAsync;
            vm.PropertyChanged += OnVmPropertyChanged;
        }
    }

    /// <summary>task-138：浮层/历史版本打开时隐藏原生 WebView，否则它会把 Avalonia 浮层盖住。</summary>
    private void OnVmPropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (_vm is null)
        {
            return;
        }

        if (e.PropertyName is nameof(DocsPageViewModel.IsDiffOpen)
            or nameof(DocsPageViewModel.IsVersionsOpen)
            or nameof(DocsPageViewModel.IsViewingVersion))
        {
            EditorView.SetSuspended(_vm.IsDiffOpen || _vm.IsVersionsOpen || _vm.IsViewingVersion);
        }
    }

    private void Detach()
    {
        if (_vm is not null)
        {
            _vm.ExportRequested -= OnExportRequested;
            _vm.EditorLoadRequested -= OnEditorLoadRequested;
            _vm.PdfExportRequested -= OnPdfExportRequested;
            _vm.PropertyChanged -= OnVmPropertyChanged;
            _vm.EditorMarkdownProvider = null;
        }

        _vm = null;
    }

    /// <summary>task-138：把富文本编辑器内核的消息接到 ViewModel 上（只接一次）。</summary>
    private void WireEditor()
    {
        if (_editorWired)
        {
            return;
        }

        _editorWired = true;
        EditorView.MarkdownChanged += (_, markdown) => _vm?.ApplyEditorMarkdown(markdown);
        EditorView.SaveRequested += (_, _) =>
        {
            if (_vm is not null && _vm.SaveNowCommand.CanExecute(null))
            {
                _vm.SaveNowCommand.Execute(null);
            }
        };
        EditorView.Ready += async (_, _) =>
        {
            // 内核就绪后补一次当前内容，避免「先打开文档、后建宿主」时空白。
            if (_vm is { IsEditorOpen: true } && !_suppressLoad)
            {
                _suppressLoad = true;
                try
                {
                    await EditorView.LoadMarkdownAsync(_vm.EditorContent).ConfigureAwait(true);
                }
                finally
                {
                    _suppressLoad = false;
                }
            }
        };
        EditorView.Failed += (_, message) => Console.WriteLine("[Bond] 文档编辑器失败：" + message);
    }

    private async Task<string?> GetEditorMarkdownAsync()
    {
        try
        {
            return await EditorView.GetMarkdownAsync().ConfigureAwait(true);
        }
        catch (Exception)
        {
            return null;
        }
    }

    private void OnPageKeyDown(object? sender, KeyEventArgs e)
    {
        if (_vm is null)
        {
            return;
        }

        if (e.Key == Key.S && e.KeyModifiers.HasFlag(KeyModifiers.Control))
        {
            if (_vm.SaveNowCommand.CanExecute(null))
            {
                _vm.SaveNowCommand.Execute(null);
            }

            e.Handled = true;
        }
    }

    private async void OnEditorLoadRequested(object? sender, string markdown)
    {
        _suppressLoad = true;
        try
        {
            await EditorView.LoadMarkdownAsync(markdown).ConfigureAwait(true);
            EditorView.FocusEditor();
        }
        catch (Exception ex)
        {
            Console.WriteLine("[Bond] 载入文档失败：" + ex.Message);
        }
        finally
        {
            _suppressLoad = false;
        }
    }

    private async void OnExportRequested(object? sender, (string Format, byte[] Data, string FileName) e)
    {
        try
        {
            var top = TopLevel.GetTopLevel(this);
            if (top?.StorageProvider is null)
            {
                return;
            }

            var file = await top.StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
            {
                Title = "导出文档",
                SuggestedFileName = e.FileName,
                DefaultExtension = e.Format,
                FileTypeChoices = new[]
                {
                    new FilePickerFileType(e.Format == "html" ? "HTML 文档" : "Markdown 文档")
                    {
                        Patterns = new[] { "*." + e.Format },
                    },
                },
            }).ConfigureAwait(true);

            if (file is null || file.TryGetLocalPath() is not { } path)
            {
                return;
            }

            await File.WriteAllBytesAsync(path, e.Data).ConfigureAwait(true);
        }
        catch (Exception)
        {
            // 导出失败由 VM 通知；这里只保证不崩溃
        }
    }

    private async void OnPdfExportRequested(object? sender, (string Html, string FileName) e)
    {
        try
        {
            // task-138：PDF 字节流由 WebView2 直接产出，这里不弹保存对话框，直接写到「下载」目录后提示路径
            // （无对话框的路径也让真机验收与无人值守导出可自动化）。
            var target = DocumentPdfExporter.DefaultTargetPath(e.FileName);
            var error = await DocumentPdfExporter.PrintAsync(e.Html, target).ConfigureAwait(true);
            _vm?.ReportPdfExport(error is null ? target : null, error);
        }
        catch (Exception ex)
        {
            _vm?.ReportPdfExport(null, ex.Message);
        }
    }

    protected override void OnDetachedFromVisualTree(Avalonia.VisualTreeAttachmentEventArgs e)
    {
        base.OnDetachedFromVisualTree(e);
        Detach();
    }
}
