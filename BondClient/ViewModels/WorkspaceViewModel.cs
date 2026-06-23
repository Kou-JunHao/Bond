using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using BondClient.Models;
using BondClient.Services;

namespace BondClient.ViewModels;

public class WorkspaceViewModel : INotifyPropertyChanged
{
    private readonly WorkspaceService _workspaceService;
    private long? _currentWorkspaceId;
    private string? _currentParentPath = "/";
    private string _newWorkspaceName = "";
    private string _newWorkspaceDescription = "";
    private bool _isLoading;

    public ObservableCollection<Workspace> Workspaces { get; } = new();
    public ObservableCollection<WorkspaceFile> Files { get; } = new();

    public long? CurrentWorkspaceId
    {
        get => _currentWorkspaceId;
        set { _currentWorkspaceId = value; OnPropertyChanged(); }
    }

    public string NewWorkspaceName
    {
        get => _newWorkspaceName;
        set { _newWorkspaceName = value; OnPropertyChanged(); }
    }

    public string NewWorkspaceDescription
    {
        get => _newWorkspaceDescription;
        set { _newWorkspaceDescription = value; OnPropertyChanged(); }
    }

    public bool IsLoading
    {
        get => _isLoading;
        set { _isLoading = value; OnPropertyChanged(); }
    }

    public string CurrentPathDisplay => _currentParentPath ?? "/";

    public event PropertyChangedEventHandler? PropertyChanged;
    public event Action<string>? ShowError;
    public event Action<string>? ShowSuccess;

    public WorkspaceViewModel(WorkspaceService workspaceService)
    {
        _workspaceService = workspaceService;
    }

    public async Task LoadWorkspacesAsync()
    {
        IsLoading = true;
        try
        {
            var list = await _workspaceService.ListWorkspacesAsync();
            if (list == null) return;
            Workspaces.Clear();
            foreach (var ws in list) Workspaces.Add(ws);
        }
        finally
        {
            IsLoading = false;
        }
    }

    public async Task CreateWorkspaceAsync()
    {
        if (string.IsNullOrWhiteSpace(NewWorkspaceName))
        {
            ShowError?.Invoke("请输入工作区名称");
            return;
        }
        var ws = await _workspaceService.CreateWorkspaceAsync(NewWorkspaceName, NewWorkspaceDescription);
        if (ws != null)
        {
            Workspaces.Add(ws);
            NewWorkspaceName = "";
            NewWorkspaceDescription = "";
            ShowSuccess?.Invoke("工作区创建成功");
        }
        else
        {
            ShowError?.Invoke("创建工作区失败");
        }
    }

    public async Task OpenWorkspaceAsync(long workspaceId)
    {
        _currentWorkspaceId = workspaceId;
        _currentParentPath = "/";
        await LoadFilesAsync();
    }

    public async Task LoadFilesAsync()
    {
        if (_currentWorkspaceId == null) return;
        IsLoading = true;
        try
        {
            var files = await _workspaceService.ListFilesAsync(_currentWorkspaceId.Value, _currentParentPath);
            if (files == null) return;
            Files.Clear();
            foreach (var f in files) Files.Add(f);
        }
        finally
        {
            IsLoading = false;
        }
    }

    public async Task NavigateToFolderAsync(string folderName)
    {
        _currentParentPath = _currentParentPath == "/"
            ? $"/{folderName}"
            : $"{_currentParentPath}/{folderName}";
        OnPropertyChanged(nameof(CurrentPathDisplay));
        await LoadFilesAsync();
    }

    public async Task NavigateUpAsync()
    {
        if (_currentParentPath == "/") return;
        var lastSlash = _currentParentPath!.LastIndexOf('/');
        _currentParentPath = lastSlash <= 0 ? "/" : _currentParentPath[..lastSlash];
        OnPropertyChanged(nameof(CurrentPathDisplay));
        await LoadFilesAsync();
    }

    public async Task UploadFileAsync(string filePath)
    {
        if (_currentWorkspaceId == null) return;
        var file = await _workspaceService.UploadFileAsync(_currentWorkspaceId.Value, filePath, _currentParentPath);
        if (file != null)
        {
            Files.Add(file);
            ShowSuccess?.Invoke($"上传成功: {file.FileName}");
        }
        else
        {
            ShowError?.Invoke("上传失败");
        }
    }

    public async Task DownloadFileAsync(long fileId, string fileName, string savePath)
    {
        if (_currentWorkspaceId == null) return;
        if (await _workspaceService.DownloadFileAsync(_currentWorkspaceId.Value, fileId, savePath))
            ShowSuccess?.Invoke($"下载完成: {fileName}");
        else
            ShowError?.Invoke("下载失败");
    }

    public async Task DeleteFileAsync(long fileId)
    {
        if (_currentWorkspaceId == null) return;
        if (await _workspaceService.DeleteFileAsync(_currentWorkspaceId.Value, fileId))
        {
            var item = Files.FirstOrDefault(f => f.Id == fileId);
            if (item != null) Files.Remove(item);
            ShowSuccess?.Invoke("删除成功");
        }
        else
        {
            ShowError?.Invoke("删除失败");
        }
    }

    public void GoBack()
    {
        _currentWorkspaceId = null;
        Files.Clear();
    }

    private void OnPropertyChanged([CallerMemberName] string? name = null)
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}
