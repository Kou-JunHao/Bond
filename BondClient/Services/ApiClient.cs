using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization.Metadata;
using BondClient.Models;

namespace BondClient.Services;

public class ApiClient : IDisposable
{
    private readonly HttpClient _http;
    private readonly string _authDir;
    private string? _accessToken;
    private string? _refreshToken;
    private static readonly BondJsonContext Ctx = BondJsonContext.Default;

    public string? AccessToken => _accessToken;

    public ApiClient(string baseUrl = "http://localhost:8080")
    {
        _http = new HttpClient { BaseAddress = new Uri(baseUrl) };
        _http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        _authDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BondClient");
        Directory.CreateDirectory(_authDir);
        LoadTokens();
    }

    public bool IsLoggedIn => !string.IsNullOrEmpty(_accessToken) && JwtUtils.IsTokenValid(_accessToken);

    public long? GetCurrentUserId()
    {
        if (string.IsNullOrEmpty(_accessToken)) return null;
        return JwtUtils.GetUserId(_accessToken);
    }

    public void SetTokens(string accessToken, string refreshToken)
    {
        _accessToken = accessToken;
        _refreshToken = refreshToken;
        ApplyAuthHeader();
        SaveTokens();
    }

    public void ClearTokens()
    {
        _accessToken = null;
        _refreshToken = null;
        _http.DefaultRequestHeaders.Authorization = null;
        var path = Path.Combine(_authDir, "auth.json");
        if (File.Exists(path)) File.Delete(path);
    }

    private void ApplyAuthHeader()
    {
        _http.DefaultRequestHeaders.Authorization =
            string.IsNullOrEmpty(_accessToken) ? null : new AuthenticationHeaderValue("Bearer", _accessToken);
    }

    private void SaveTokens()
    {
        var data = new AuthToken { AccessToken = _accessToken ?? "", RefreshToken = _refreshToken ?? "" };
        var json = JsonSerializer.Serialize(data, Ctx.AuthToken);
        File.WriteAllText(Path.Combine(_authDir, "auth.json"), json);
    }

    private void LoadTokens()
    {
        var path = Path.Combine(_authDir, "auth.json");
        if (!File.Exists(path)) return;
        try
        {
            var json = File.ReadAllText(path);
            var data = JsonSerializer.Deserialize(json, Ctx.AuthToken);
            if (data != null && !string.IsNullOrEmpty(data.AccessToken))
            {
                _accessToken = data.AccessToken;
                _refreshToken = data.RefreshToken;
                ApplyAuthHeader();
            }
        }
        catch { }
    }

    private async Task<bool> TryRefreshToken()
    {
        if (string.IsNullOrEmpty(_refreshToken)) return false;
        try
        {
            var body = $$"""{"refreshToken":"{{_refreshToken}}"}""";
            var content = new StringContent(body, System.Text.Encoding.UTF8, "application/json");
            var resp = await _http.PostAsync("/api/auth/refresh", content);
            if (!resp.IsSuccessStatusCode) return false;
            var result = await resp.Content.ReadFromJsonAsync(Ctx.ApiResultUser);
            if (result?.Data?.AccessToken != null)
            {
                SetTokens(result.Data.AccessToken, result.Data.RefreshToken ?? "");
                return true;
            }
        }
        catch { }
        return false;
    }

    public async Task<HttpResponseMessage?> SendAsync(Func<HttpClient, Task<HttpResponseMessage>> send)
    {
        var resp = await send(_http);
        if (resp.StatusCode == System.Net.HttpStatusCode.Unauthorized)
        {
            if (await TryRefreshToken())
                resp = await send(_http);
        }
        return resp;
    }

    public async Task<ApiResult?> PostJsonAsync<TBody>(string path, TBody body, JsonTypeInfo<TBody> bodyType)
    {
        var resp = await SendAsync(http =>
        {
            var json = JsonSerializer.Serialize(body, bodyType);
            var content = new StringContent(json, System.Text.Encoding.UTF8, "application/json");
            return http.PostAsync(path, content);
        });
        if (resp == null) return null;
        return await resp.Content.ReadFromJsonAsync(Ctx.ApiResult);
    }

    public async Task<ApiResult<TResult>?> PostJsonAsync<TBody, TResult>(string path, TBody body,
        JsonTypeInfo<TBody> bodyType, JsonTypeInfo<ApiResult<TResult>> resultType)
    {
        var resp = await SendAsync(http =>
        {
            var json = JsonSerializer.Serialize(body, bodyType);
            var content = new StringContent(json, System.Text.Encoding.UTF8, "application/json");
            return http.PostAsync(path, content);
        });
        if (resp == null) return null;
        return await resp.Content.ReadFromJsonAsync(resultType);
    }

    public async Task<ApiResult<TResult>?> PostRawJsonAsync<TResult>(string path, string json,
        JsonTypeInfo<ApiResult<TResult>> resultType)
    {
        var resp = await SendAsync(http =>
        {
            var content = new StringContent(json, System.Text.Encoding.UTF8, "application/json");
            return http.PostAsync(path, content);
        });
        if (resp == null) return null;
        return await resp.Content.ReadFromJsonAsync(resultType);
    }

    public async Task<ApiResult?> PostRawJsonAsync(string path, string json)
    {
        var resp = await SendAsync(http =>
        {
            var content = new StringContent(json, System.Text.Encoding.UTF8, "application/json");
            return http.PostAsync(path, content);
        });
        if (resp == null) return null;
        return await resp.Content.ReadFromJsonAsync(Ctx.ApiResult);
    }

    public async Task<ApiResult<TResult>?> PutRawJsonAsync<TResult>(string path, string json,
        JsonTypeInfo<ApiResult<TResult>> resultType)
    {
        var resp = await SendAsync(http =>
        {
            var content = new StringContent(json, System.Text.Encoding.UTF8, "application/json");
            return http.PutAsync(path, content);
        });
        if (resp == null) return null;
        return await resp.Content.ReadFromJsonAsync(resultType);
    }

    public async Task<ApiResult<TResult>?> GetJsonAsync<TResult>(string path, JsonTypeInfo<ApiResult<TResult>> resultType)
    {
        var resp = await SendAsync(http => http.GetAsync(path));
        if (resp == null) return null;
        return await resp.Content.ReadFromJsonAsync(resultType);
    }

    public async Task<ApiResult?> GetAsync(string path)
    {
        var resp = await SendAsync(http => http.GetAsync(path));
        if (resp == null) return null;
        return await resp.Content.ReadFromJsonAsync(Ctx.ApiResult);
    }

    public async Task<ApiResult<TResult>?> PutJsonAsync<TBody, TResult>(string path, TBody body,
        JsonTypeInfo<TBody> bodyType, JsonTypeInfo<ApiResult<TResult>> resultType)
    {
        var resp = await SendAsync(http =>
        {
            var json = JsonSerializer.Serialize(body, bodyType);
            var content = new StringContent(json, System.Text.Encoding.UTF8, "application/json");
            return http.PutAsync(path, content);
        });
        if (resp == null) return null;
        return await resp.Content.ReadFromJsonAsync(resultType);
    }

    public async Task<ApiResult?> DeleteAsync(string path)
    {
        var resp = await SendAsync(http => http.DeleteAsync(path));
        if (resp == null) return null;
        return await resp.Content.ReadFromJsonAsync(Ctx.ApiResult);
    }

    public async Task<ApiResult<TResult>?> PostMultipartAsync<TResult>(string path, MultipartFormDataContent content,
        JsonTypeInfo<ApiResult<TResult>> resultType)
    {
        var resp = await SendAsync(http => http.PostAsync(path, content));
        if (resp == null) return null;
        return await resp.Content.ReadFromJsonAsync(resultType);
    }

    public async Task<HttpResponseMessage?> GetRawAsync(string path)
    {
        return await SendAsync(http => http.GetAsync(path));
    }

    public void Dispose()
    {
        _http.Dispose();
    }
}
