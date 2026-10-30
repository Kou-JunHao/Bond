using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Bond.Models;
using Bond.Services.Api;

namespace Bond.Services;

/// <summary>
/// HTTP 实现的工作区管理（task-15）：创建/改名/删除、成员、邀请链接、好友邀请、待处理邀请。
/// 只补 UI 需要的调用，不新增后端接口。
/// </summary>
public sealed partial class HttpBondStore
{
    /// <summary>创建工作区（名称 + 可选描述）。</summary>
    public async Task CreateAsync(string name, string? description = null)
    {
        var created = await _api.PostAsync<WorkspaceDto>("/api/workspaces", new
        {
            name,
            description = string.IsNullOrWhiteSpace(description) ? null : description.Trim()
        }).ConfigureAwait(false);

        if (created?.IsOk != true)
            throw new InvalidOperationException(created?.Message ?? _api.LastError ?? "创建工作区失败");

        await RefreshWorkspacesAsync().ConfigureAwait(false);
    }

    /// <summary>重命名 / 改描述（PUT /api/workspaces/{id}，owner 才能调用）。</summary>
    public async Task UpdateAsync(WorkspaceItem workspace, string name, string? description)
    {
        var updated = await _api.PutAsync<WorkspaceDto>($"/api/workspaces/{workspace.Id}", new
        {
            name,
            description = string.IsNullOrWhiteSpace(description) ? null : description.Trim()
        }).ConfigureAwait(false);

        if (updated?.IsOk != true)
            throw new InvalidOperationException(updated?.Message ?? _api.LastError ?? "保存工作区失败");

        await RefreshWorkspacesAsync().ConfigureAwait(false);
    }

    /// <summary>删除工作区（owner 才能调用）。</summary>
    public async Task DeleteAsync(WorkspaceItem workspace)
    {
        var deleted = await _api.DeleteAsync($"/api/workspaces/{workspace.Id}").ConfigureAwait(false);
        if (deleted is null || deleted.Code != 200)
            throw new InvalidOperationException(deleted?.Message ?? _api.LastError ?? "删除工作区失败");

        await RefreshWorkspacesAsync().ConfigureAwait(false);
    }

    /// <summary>成员列表（GET /api/workspaces/{id}/members）。</summary>
    public async Task<IReadOnlyList<WorkspaceMemberItem>> GetMembersAsync(WorkspaceItem workspace)
    {
        var result = await _api.GetAsync<List<WorkspaceMemberDto>>(
            $"/api/workspaces/{workspace.Id}/members").ConfigureAwait(false);

        if (result?.Data is null)
            throw new InvalidOperationException(result?.Message ?? _api.LastError ?? "无法加载成员");

        var me = _currentUserId;
        // task-8：/api/workspaces 的 DTO 不带「我在该空间的角色」（只有 ownerId），无法区分 owner 与 admin；
        // 成员列表里一定有我自己那一行，真实 role 以它为准（0=owner 1=admin 2=member）。
        var myRole = result.Data.FirstOrDefault(x => x.UserId == me)?.Role ?? (workspace.IsOwner ? 0 : 2);

        return result.Data
            .Select(m => new WorkspaceMemberItem
            {
                UserId = m.UserId.ToString(),
                Nickname = string.IsNullOrWhiteSpace(m.Nickname) ? m.Username : m.Nickname,
                Role = m.Role,
                CanRemove = workspace.IsOwner && m.Role != 0 && m.UserId != me,
                CanChangeRole = workspace.IsOwner && m.Role != 0 && m.UserId != me,
                // task-8：只有 owner/admin 能给「普通成员」设置文件权限；owner/admin 本人永远全权（后端 400），
                // 所以这两类成员行不显示权限入口。
                CanManageAcl = myRole <= 1 && m.Role > 1 && m.UserId != me
            })
            .ToList();
    }

    /// <summary>调整成员角色（PUT /api/workspaces/{id}/members/{userId}，1=管理员 2=成员）。</summary>
    public async Task SetMemberRoleAsync(WorkspaceItem workspace, WorkspaceMemberItem member, int role)
    {
        var updated = await _api.PutAsync<object>(
            $"/api/workspaces/{workspace.Id}/members/{member.UserId}", new { role }).ConfigureAwait(false);

        if (updated is null || updated.Code != 200)
            throw new InvalidOperationException(updated?.Message ?? _api.LastError ?? "调整成员角色失败");
    }

    /// <summary>移除成员（DELETE /api/workspaces/{id}/members/{userId}）。</summary>
    public async Task RemoveMemberAsync(WorkspaceItem workspace, WorkspaceMemberItem member)
    {
        var deleted = await _api.DeleteAsync(
            $"/api/workspaces/{workspace.Id}/members/{member.UserId}").ConfigureAwait(false);

        if (deleted is null || deleted.Code != 200)
            throw new InvalidOperationException(deleted?.Message ?? _api.LastError ?? "移除成员失败");
    }

    /// <summary>
    /// 生成邀请链接（POST /api/workspaces/{id}/invite/link）。
    /// <para>task-159 起后端直接返回完整的落地页 URL（<c>{invite.public_base_url}/i/{token}</c>）；
    /// 旧版只返回裸 token。这里两者都兼容：绝对 URL 原样使用，裸 token 再拼 <c>/i/{token}</c>。
    /// **不要**再拼 <c>/api/invite/</c>——那是返回 JSON 的旧端点，不是浏览器可读的邀请页，
    /// 也不该把完整 URL 当 token 塞进路径（会变成 <c>/api/invite/https%3A%2F%2F…</c> 并 401）。</para>
    /// </summary>
    public async Task<string?> GenerateInviteLinkAsync(WorkspaceItem workspace)
    {
        var created = await _api.PostAsync<string>(
            $"/api/workspaces/{workspace.Id}/invite/link", new { }).ConfigureAwait(false);

        if (created?.IsOk != true || string.IsNullOrWhiteSpace(created.Data))
            throw new InvalidOperationException(created?.Message ?? _api.LastError ?? "生成邀请链接失败");

        var value = created.Data.Trim();
        if (Uri.TryCreate(value, UriKind.Absolute, out var absolute)
            && (absolute.Scheme == Uri.UriSchemeHttps || absolute.Scheme == Uri.UriSchemeHttp))
        {
            return absolute.ToString();
        }

        return $"{_api.BaseUrl.TrimEnd('/')}/i/{Uri.EscapeDataString(value)}";
    }

    /// <summary>邀请好友（POST /api/workspaces/{id}/invite/friend，按用户 ID）。</summary>
    public async Task InviteFriendAsync(WorkspaceItem workspace, string userIdInput)
    {
        if (!long.TryParse(userIdInput?.Trim(), out var inviteeId))
            throw new InvalidOperationException("请输入数字用户 ID");

        var created = await _api.PostAsync<object>(
            $"/api/workspaces/{workspace.Id}/invite/friend", new { inviteeId }).ConfigureAwait(false);

        if (created?.IsOk != true)
            throw new InvalidOperationException(created?.Message ?? _api.LastError ?? "邀请好友失败");
    }

    /// <summary>待处理邀请（GET /api/workspaces/invitations）。</summary>
    public async Task<IReadOnlyList<WorkspaceInvitation>> GetInvitationsAsync()
    {
        var result = await _api.GetAsync<List<InvitationDto>>("/api/workspaces/invitations").ConfigureAwait(false);
        if (result?.IsOk == false)
            throw new InvalidOperationException(result.Message ?? _api.LastError ?? "无法加载待处理邀请");

        if (result?.Data is null) return Array.Empty<WorkspaceInvitation>();

        return result.Data
            .Select(i => new WorkspaceInvitation
            {
                Id = i.Id.ToString(),
                WorkspaceName = i.WorkspaceName,
                InviterName = i.InviterName,
                Role = i.Role,
                CreatedAt = i.CreatedAt
            })
            .ToList();
    }

    /// <summary>接受邀请（POST /api/workspaces/invitations/{invId}/accept）。</summary>
    public async Task AcceptInvitationAsync(WorkspaceInvitation invitation)
    {
        var result = await _api.PostAsync<object>(
            $"/api/workspaces/invitations/{invitation.Id}/accept", new { }).ConfigureAwait(false);

        if (result?.IsOk != true)
            throw new InvalidOperationException(result?.Message ?? _api.LastError ?? "接受邀请失败");

        await RefreshWorkspacesAsync().ConfigureAwait(false);
    }

    /// <summary>拒绝邀请（POST /api/workspaces/invitations/{invId}/reject）。</summary>
    public async Task RejectInvitationAsync(WorkspaceInvitation invitation)
    {
        var result = await _api.PostAsync<object>(
            $"/api/workspaces/invitations/{invitation.Id}/reject", new { }).ConfigureAwait(false);

        if (result?.IsOk != true)
            throw new InvalidOperationException(result?.Message ?? _api.LastError ?? "拒绝邀请失败");
    }

    /// <summary>校验邀请链接/邀请码（GET /api/invite/{token}，匿名可读）。</summary>
    public async Task<WorkspaceInvitePreview> VerifyInviteLinkAsync(string token)
    {
        var result = await _api
            .GetAsync<InvitationDto>($"/api/invite/{Uri.EscapeDataString(token)}")
            .ConfigureAwait(false);

        if (result?.IsOk != true || result.Data is null)
            throw new InvalidOperationException(result?.Message ?? _api.LastError ?? "邀请链接无效或已过期");

        return new WorkspaceInvitePreview
        {
            WorkspaceId = result.Data.WorkspaceId.ToString(),
            WorkspaceName = result.Data.WorkspaceName,
            InviterName = result.Data.InviterName,
            ExpiresAtMillis = result.Data.ExpiresAt
        };
    }

    /// <summary>通过邀请链接加入工作区（POST /api/invite/{token}/accept）。</summary>
    public async Task AcceptInviteLinkAsync(string token)
    {
        var result = await _api
            .PostAsync<object>($"/api/invite/{Uri.EscapeDataString(token)}/accept", new { })
            .ConfigureAwait(false);

        if (result?.IsOk != true)
            throw new InvalidOperationException(result?.Message ?? _api.LastError ?? "加入工作区失败");

        await RefreshWorkspacesAsync().ConfigureAwait(false);
    }

    // ══════════════════════════════════════════════════════════════
    //  task-8：工作区成员文件级 ACL（Backend V34）
    //  GET  /api/workspaces/{id}/members/{userId}/acl   （owner/admin）
    //  PUT  同路径，body { accessMode, grants[] }       整包替换
    //  GET  /api/workspaces/{id}/acl/me                 （任意成员，本人有效范围）
    //  语义：all=不受限（默认）；restricted=只能访问被授权目录前缀/指定文件；
    //        owner/admin 永远 all 且不可被设置（PUT 目标为 owner/admin → 400）。
    // ══════════════════════════════════════════════════════════════

    private static string WorkspaceBase(WorkspaceItem workspace)
        => $"/api/workspaces/{Uri.EscapeDataString(workspace.Id)}";

    /// <summary>读取某成员的 ACL（owner/admin）。</summary>
    public async Task<WorkspaceMemberAcl> GetMemberAclAsync(WorkspaceItem workspace,
        WorkspaceMemberItem member, CancellationToken ct = default)
    {
        var result = await _api
            .GetAsync<WorkspaceAclDto>(
                $"{WorkspaceBase(workspace)}/members/{Uri.EscapeDataString(member.UserId)}/acl")
            .ConfigureAwait(false);

        if (result?.Data is null)
            throw new InvalidOperationException(
                result?.Message ?? _api.LastError ?? "无法读取该成员的文件权限");

        return MapAcl(result.Data, workspace.Id, member.UserId);
    }

    /// <summary>整包替换某成员的 ACL，返回服务端归一化后的视图。</summary>
    public async Task<WorkspaceMemberAcl> SetMemberAclAsync(WorkspaceItem workspace,
        WorkspaceMemberItem member, string accessMode, IReadOnlyList<WorkspaceAclGrant> grants,
        CancellationToken ct = default)
    {
        var normalizedMode = string.Equals(accessMode, "restricted", StringComparison.OrdinalIgnoreCase)
            ? "restricted"
            : "all";

        // all 语义 = 忽略授权表（服务端会清空）；仍然显式传空数组，避免歧义。
        var payload = normalizedMode == "all"
            ? new List<WorkspaceAclGrantDto>()
            : grants.Select(g => new WorkspaceAclGrantDto
            {
                ScopeType = g.IsFolder ? "folder" : "file",
                ScopePath = g.ScopePath,
                // 服务端 Grant.fileId 是 Long；雪花 id 直接按数字发（服务端按 long 解析，无精度问题）。
                FileId = long.TryParse(g.FileId, out var fileId) ? fileId : null,
                CanRead = g.CanRead,
                CanWrite = g.CanWrite
            }).ToList();

        var result = await _api
            .PutAsync<WorkspaceAclDto>(
                $"{WorkspaceBase(workspace)}/members/{Uri.EscapeDataString(member.UserId)}/acl",
                new { accessMode = normalizedMode, grants = payload })
            .ConfigureAwait(false);

        if (result?.Data is null)
            throw new InvalidOperationException(
                result?.Message ?? _api.LastError ?? "保存文件权限失败");

        return MapAcl(result.Data, workspace.Id, member.UserId);
    }

    /// <summary>本人在该工作区的有效范围（GET …/acl/me）。</summary>
    public async Task<WorkspaceMemberAcl> GetMyAclAsync(WorkspaceItem workspace, CancellationToken ct = default)
    {
        var result = await _api
            .GetAsync<WorkspaceAclDto>($"{WorkspaceBase(workspace)}/acl/me")
            .ConfigureAwait(false);

        if (result?.Data is null)
            throw new InvalidOperationException(
                result?.Message ?? _api.LastError ?? "无法读取你在该工作区的文件权限");

        return MapAcl(result.Data, workspace.Id, _currentUserId.ToString());
    }

    /// <summary>ACL 授权选择器：列出工作区某目录下的文件夹与文件（目录在前）。</summary>
    public async Task<(IReadOnlyList<NodeItem>? Entries, string? Error)> ListAclEntriesAsync(
        WorkspaceItem workspace, string path, CancellationToken ct = default)
    {
        var normalized = string.IsNullOrWhiteSpace(path) || path == "/" ? "/" : path.TrimEnd('/');
        var url = $"{WorkspaceBase(workspace)}/files?parentPath={Uri.EscapeDataString(normalized)}";

        var result = await _api.GetAsync<List<WorkspaceFileDto>>(url).ConfigureAwait(false);
        if (result?.Data is null)
            return (null, result?.Message ?? _api.LastError ?? "无法加载目录，请重试");

        IReadOnlyList<NodeItem> entries = result.Data
            .Select(f => MapFile(f, workspace.Id))
            .OrderBy(n => n.IsFolder ? 0 : 1)
            .ThenBy(n => n.Name, StringComparer.CurrentCultureIgnoreCase)
            .ToList();
        return (entries, null);
    }

    /// <summary>DTO → 领域模型。accessMode 与 scopeType 统一小写，避免服务端大小写漂移影响界面判断。</summary>
    private static WorkspaceMemberAcl MapAcl(WorkspaceAclDto dto, string workspaceId, string userId) => new()
    {
        WorkspaceId = dto.WorkspaceId?.ToString() ?? workspaceId,
        UserId = dto.UserId?.ToString() ?? userId,
        AccessMode = string.IsNullOrWhiteSpace(dto.AccessMode) ? "all" : dto.AccessMode.Trim().ToLowerInvariant(),
        Grants = (dto.Grants ?? [])
            .Select(g => new WorkspaceAclGrant
            {
                ScopeType = string.IsNullOrWhiteSpace(g.ScopeType) ? "folder" : g.ScopeType.Trim().ToLowerInvariant(),
                ScopePath = string.IsNullOrWhiteSpace(g.ScopePath) ? "/" : g.ScopePath,
                FileId = g.FileId?.ToString(),
                CanRead = g.CanRead,
                CanWrite = g.CanWrite
            })
            .ToList()
    };
}
