using System.Threading.Tasks;

namespace Bond.Services.Api;

/// <summary>
/// task-153：个人资料与账户安全相关的认证接口（后端 bond-auth 已上线）。
/// <para>PUT /api/auth/profile  body {nickname, avatarUrl} → UserDTO</para>
/// <para>PUT /api/auth/password body {oldPassword, newPassword} → Void</para>
/// <para>改邮箱的三个接口原本散在 SettingsPageViewModel 里直接拼路径，这里补上同族封装，
/// 新代码不要再在页面里拼 URL。</para>
/// </summary>
public sealed partial class BondApiClient
{
    /// <summary>修改昵称 / 头像地址（头像接口 task-152 上线前 avatarUrl 传 null 即不改）。</summary>
    public Task<ApiResult<UserDto>?> UpdateProfileAsync(string nickname, string? avatarUrl)
        => PutAsync<UserDto>("/api/auth/profile", new { nickname, avatarUrl });

    /// <summary>修改密码：需要旧密码；失败时后端返回 400（原密码错误 / 新密码太短）或 401。</summary>
    public Task<ApiResult<System.Collections.Generic.Dictionary<string, object>>?> ChangePasswordAsync(
        string oldPassword, string newPassword)
        => PutAsync<System.Collections.Generic.Dictionary<string, object>>(
            "/api/auth/password", new { oldPassword, newPassword });
}
