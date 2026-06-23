using System.Net.Http.Headers;
using BondClient.Models;

namespace BondClient.Services;

public class WorkspaceService
{
    private readonly ApiClient _api;

    public WorkspaceService(ApiClient api)
    {
        _api = api;
    }

    public async Task<Workspace?> CreateWorkspaceAsync(string name, string? description)
    {
        var desc = description ?? "";
        var json = $$"""{"name":"{{name}}","description":"{{desc}}"}""";
        var result = await _api.PostRawJsonAsync("/api/workspaces", json, BondJsonContext.Default.ApiResultWorkspace);
        return result?.IsSuccess == true ? result.Data : null;
    }

    public async Task<List<Workspace>?> ListWorkspacesAsync()
    {
        var result = await _api.GetJsonAsync("/api/workspaces", BondJsonContext.Default.ApiResultListWorkspace);
        return result?.IsSuccess == true ? result.Data : null;
    }

    public async Task<Workspace?> GetWorkspaceAsync(long workspaceId)
    {
        var result = await _api.GetJsonAsync($"/api/workspaces/{workspaceId}", BondJsonContext.Default.ApiResultWorkspace);
        return result?.IsSuccess == true ? result.Data : null;
    }

    public async Task<Workspace?> UpdateWorkspaceAsync(long workspaceId, string? name, string? description)
    {
        var n = name ?? "";
        var d = description ?? "";
        var json = $$"""{"name":"{{n}}","description":"{{d}}"}""";
        var result = await _api.PutRawJsonAsync($"/api/workspaces/{workspaceId}", json, BondJsonContext.Default.ApiResultWorkspace);
        return result?.IsSuccess == true ? result.Data : null;
    }

    public async Task<bool> DeleteWorkspaceAsync(long workspaceId)
    {
        var result = await _api.DeleteAsync($"/api/workspaces/{workspaceId}");
        return result?.IsSuccess == true;
    }

    public async Task<bool> AddMemberAsync(long workspaceId, long userId, int role = 2)
    {
        var json = $$"""{"userId":{{userId}},"role":{{role}}}""";
        var result = await _api.PostRawJsonAsync($"/api/workspaces/{workspaceId}/members", json);
        return result?.IsSuccess == true;
    }

    public async Task<bool> RemoveMemberAsync(long workspaceId, long userId)
    {
        var result = await _api.DeleteAsync($"/api/workspaces/{workspaceId}/members/{userId}");
        return result?.IsSuccess == true;
    }

    public async Task<List<WorkspaceFile>?> ListFilesAsync(long workspaceId, string? parentPath = null)
    {
        var path = $"/api/workspaces/{workspaceId}/files";
        if (!string.IsNullOrEmpty(parentPath)) path += $"?parentPath={Uri.EscapeDataString(parentPath)}";
        var result = await _api.GetJsonAsync(path, BondJsonContext.Default.ApiResultListWorkspaceFile);
        return result?.IsSuccess == true ? result.Data : null;
    }

    public async Task<WorkspaceFile?> UploadFileAsync(long workspaceId, string filePath, string? parentPath = null)
    {
        using var content = new MultipartFormDataContent();
        var fileBytes = await File.ReadAllBytesAsync(filePath);
        var fileContent = new ByteArrayContent(fileBytes);
        fileContent.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        content.Add(fileContent, "file", Path.GetFileName(filePath));
        if (!string.IsNullOrEmpty(parentPath))
            content.Add(new StringContent(parentPath), "parentPath");

        var result = await _api.PostMultipartAsync<WorkspaceFile>(
            $"/api/workspaces/{workspaceId}/files/upload",
            content,
            BondJsonContext.Default.ApiResultWorkspaceFile);
        return result?.IsSuccess == true ? result.Data : null;
    }

    public async Task<bool> DownloadFileAsync(long workspaceId, long fileId, string savePath)
    {
        var resp = await _api.GetRawAsync($"/api/workspaces/{workspaceId}/files/{fileId}/download");
        if (resp == null || !resp.IsSuccessStatusCode) return false;
        using var fs = new FileStream(savePath, FileMode.Create, FileAccess.Write);
        await resp.Content.CopyToAsync(fs);
        return true;
    }

    public async Task<bool> DeleteFileAsync(long workspaceId, long fileId)
    {
        var result = await _api.DeleteAsync($"/api/workspaces/{workspaceId}/files/{fileId}");
        return result?.IsSuccess == true;
    }
}
