using BondClient.Models;

namespace BondClient.Services;

public class AuthService
{
    private readonly ApiClient _api;
    private User? _currentUser;

    public User? CurrentUser => _currentUser;
    public bool IsLoggedIn => _api.IsLoggedIn;
    public long? UserId => _api.GetCurrentUserId();

    public event Action? LoginStateChanged;

    public AuthService(ApiClient api)
    {
        _api = api;
    }

    public async Task<(bool success, string? error)> RegisterAsync(string username, string password, string nickname)
    {
        try
        {
            var json = $$"""{"username":"{{username}}","password":"{{password}}","nickname":"{{nickname}}"}""";
            var result = await _api.PostRawJsonAsync("/api/auth/register", json, BondJsonContext.Default.ApiResultUser);
            if (result?.IsSuccess == true && result.Data?.AccessToken != null)
            {
                _api.SetTokens(result.Data.AccessToken, result.Data.RefreshToken ?? "");
                _currentUser = result.Data;
                LoginStateChanged?.Invoke();
                return (true, null);
            }
            return (false, result?.Message ?? "注册失败");
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }

    public async Task<(bool success, string? error)> LoginAsync(string username, string password, string? captchaId = null, string? captchaCode = null)
    {
        try
        {
            string json;
            if (!string.IsNullOrEmpty(captchaId) && !string.IsNullOrEmpty(captchaCode))
            {
                json = $$"""{"username":"{{username}}","password":"{{password}}","captchaId":"{{captchaId}}","captchaCode":"{{captchaCode}}"}""";
            }
            else
            {
                json = $$"""{"username":"{{username}}","password":"{{password}}"}""";
            }
            var result = await _api.PostRawJsonAsync("/api/auth/login", json, BondJsonContext.Default.ApiResultUser);
            if (result?.IsSuccess == true && result.Data?.AccessToken != null)
            {
                _api.SetTokens(result.Data.AccessToken, result.Data.RefreshToken ?? "");
                _currentUser = result.Data;
                LoginStateChanged?.Invoke();
                return (true, null);
            }
            return (false, result?.Message ?? "登录失败");
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }

    public async Task<bool> TryAutoLoginAsync()
    {
        if (!_api.IsLoggedIn) return false;
        try
        {
            var result = await _api.GetJsonAsync("/api/auth/me", BondJsonContext.Default.ApiResultUser);
            if (result?.IsSuccess == true && result.Data != null)
            {
                _currentUser = result.Data;
                LoginStateChanged?.Invoke();
                return true;
            }
        }
        catch { }
        _api.ClearTokens();
        return false;
    }

    public void Logout()
    {
        _api.ClearTokens();
        _currentUser = null;
        LoginStateChanged?.Invoke();
    }
}
