using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.Linq;
using System.Threading.Tasks;
using Bond.Core;
using Bond.Models;
using Bond.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Bond.ViewModels;

/// <summary>
/// 工作区页（task-15）：卡片（成员数/文件数/配额/角色）+ 新建弹窗 + 管理抽屉（改名/描述/成员/邀请/删除）
/// + 待处理邀请（接受/拒绝）。
/// </summary>
public partial class WorkspacePageViewModel : ObservableObject
{
    private readonly IWorkspaceService _workspaces;
    private readonly INotificationCenter _notify;
    private bool _firstLoadDone;

    public WorkspacePageViewModel(IWorkspaceService workspaces, INotificationCenter notify)
    {
        _workspaces = workspaces;
        _notify = notify;

        _workspaces.Changed += (_, _) =>
        {
            OnPropertyChanged(nameof(IsEmpty));
            OnPropertyChanged(nameof(Workspaces));
        };

        if (_workspaces.Workspaces is INotifyCollectionChanged incc)
            incc.CollectionChanged += (_, _) => OnPropertyChanged(nameof(IsEmpty));

        _ = LoadAsync();
    }

    /// <summary>点卡片「打开」时通知外壳切到文件页（由 MainWindowViewModel 转接）。</summary>
    public event EventHandler<WorkspaceItem>? OpenRequested;

    // ── 列表 ────────────────────────────────────────────────────
    public ObservableCollection<WorkspaceItem> Workspaces => _workspaces.Workspaces;

    [ObservableProperty]
    private bool _isLoading;

    [ObservableProperty]
    private string? _errorText;

    public bool HasError => !string.IsNullOrEmpty(ErrorText);

    public bool IsEmpty => !IsLoading && Workspaces.Count == 0;

    partial void OnIsLoadingChanged(bool value) => OnPropertyChanged(nameof(IsEmpty));

    partial void OnErrorTextChanged(string? value) => OnPropertyChanged(nameof(HasError));

    [RelayCommand]
    public async Task LoadAsync()
    {
        IsLoading = true;
        ErrorText = null;
        try
        {
            if (!_firstLoadDone)
            {
                // task-43：首次加载失败按 2s/4s/8s 自动重试，成功后自动清掉错误；
                // 与文件页的断网自愈同一口径，错误态只画一次、不弹 Toast。
                var error = await LoadRetry.FirstLoadAsync(async () =>
                {
                    await _workspaces.RefreshAsync().ConfigureAwait(true);
                    return _workspaces.LastError;
                }).ConfigureAwait(true);

                _firstLoadDone = true;
                if (error is not null) ErrorText = LoadRetry.Humanize(error, "无法加载工作区，请检查网络后重试");
            }
            else
            {
                await _workspaces.RefreshAsync().ConfigureAwait(true);
                ErrorText = _workspaces.LastError;
            }

            await ReloadInvitationsAsync().ConfigureAwait(true);
        }
        catch (Exception ex)
        {
            ErrorText = LoadRetry.Humanize(ex.Message, "无法加载工作区，请检查网络后重试");
        }
        finally
        {
            IsLoading = false;
            OnPropertyChanged(nameof(IsEmpty));
        }
    }

    // ── 待处理邀请 ──────────────────────────────────────────────
    public ObservableCollection<WorkspaceInvitation> Invitations { get; } = [];

    public bool HasInvitations => Invitations.Count > 0;

    private async Task ReloadInvitationsAsync()
    {
        try
        {
            var items = await _workspaces.GetInvitationsAsync().ConfigureAwait(true);
            // task-135：不 Clear（Reset 会残留容器），逐项同步。
            CollectionSync.Apply(Invitations, items);
        }
        catch
        {
            // 邀请加载失败不影响主列表；横幅不显示即可。
        }
        OnPropertyChanged(nameof(HasInvitations));
    }

    [RelayCommand]
    private async Task AcceptInvitationAsync(WorkspaceInvitation? invitation)
    {
        if (invitation is null) return;

        // 先本地移除，避免“接受后横幅还挂着”的旧 bug；失败再放回。
        Invitations.Remove(invitation);
        OnPropertyChanged(nameof(HasInvitations));

        try
        {
            await _workspaces.AcceptInvitationAsync(invitation).ConfigureAwait(true);
            _notify.Notify($"已加入「{invitation.WorkspaceName}」", NoticeKind.Success);
        }
        catch (Exception ex)
        {
            Invitations.Insert(0, invitation);
            OnPropertyChanged(nameof(HasInvitations));
            _notify.Notify($"接受邀请失败：{ex.Message}", NoticeKind.Error);
        }

        await ReloadInvitationsAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private async Task RejectInvitationAsync(WorkspaceInvitation? invitation)
    {
        if (invitation is null) return;

        Invitations.Remove(invitation);
        OnPropertyChanged(nameof(HasInvitations));

        try
        {
            await _workspaces.RejectInvitationAsync(invitation).ConfigureAwait(true);
            _notify.Notify("已拒绝该邀请", NoticeKind.Info);
        }
        catch (Exception ex)
        {
            Invitations.Insert(0, invitation);
            OnPropertyChanged(nameof(HasInvitations));
            _notify.Notify($"拒绝邀请失败：{ex.Message}", NoticeKind.Error);
        }

        await ReloadInvitationsAsync().ConfigureAwait(true);
    }

    // ── 新建工作区弹窗 ──────────────────────────────────────────
    [ObservableProperty]
    private bool _isCreateOpen;

    [ObservableProperty]
    private string _newName = string.Empty;

    [ObservableProperty]
    private string _newDescription = string.Empty;

    [ObservableProperty]
    private bool _createBusy;

    public bool CanCreate => !CreateBusy && !string.IsNullOrWhiteSpace(NewName);

    partial void OnNewNameChanged(string value) => OnPropertyChanged(nameof(CanCreate));

    partial void OnCreateBusyChanged(bool value) => OnPropertyChanged(nameof(CanCreate));

    [RelayCommand]
    private void OpenCreate()
    {
        NewName = string.Empty;
        NewDescription = string.Empty;
        IsCreateOpen = true;
    }

    [RelayCommand]
    private void CancelCreate() => IsCreateOpen = false;

    [RelayCommand]
    private async Task SubmitCreateAsync()
    {
        if (!CanCreate) return;

        CreateBusy = true;
        try
        {
            var name = NewName.Trim();
            await _workspaces.CreateAsync(name, NewDescription).ConfigureAwait(true);
            _notify.Notify($"已创建工作区「{name}」", NoticeKind.Success);
            IsCreateOpen = false;
            OnPropertyChanged(nameof(IsEmpty));
        }
        catch (Exception ex)
        {
            _notify.Notify($"创建失败：{ex.Message}", NoticeKind.Error);
        }
        finally
        {
            CreateBusy = false;
        }
    }

    // ── 卡片操作 ────────────────────────────────────────────────
    [RelayCommand]
    public void Open(WorkspaceItem? workspace)
    {
        if (workspace is null) return;
        OpenRequested?.Invoke(this, workspace);
    }

    // ── 管理抽屉 ────────────────────────────────────────────────
    [ObservableProperty]
    private bool _isManageOpen;

    [ObservableProperty]
    private WorkspaceItem? _managing;

    [ObservableProperty]
    private string _manageName = string.Empty;

    [ObservableProperty]
    private string _manageDescription = string.Empty;

    [ObservableProperty]
    private string? _manageError;

    [ObservableProperty]
    private bool _manageBusy;

    [ObservableProperty]
    private string _inviteLink = string.Empty;

    [ObservableProperty]
    private string _inviteUserId = string.Empty;

    [ObservableProperty]
    private bool _isDeleteConfirmOpen;

    [ObservableProperty]
    private string _deleteConfirmText = string.Empty;

    public ObservableCollection<WorkspaceMemberItem> Members { get; } = [];

    public bool IsOwner => Managing?.IsOwner ?? false;

    public string ManageRoleText => Managing?.RoleText ?? "成员";

    public string ManageMetaText => Managing is null
        ? string.Empty
        : $"{Managing.MetaLine} · 已用 {FormatSize(Managing.UsedBytes)} / {FormatSize(Managing.QuotaBytes)}";

    public bool HasMembers => Members.Count > 0;

    public bool HasInviteLink => !string.IsNullOrWhiteSpace(InviteLink);

    public bool HasManageError => !string.IsNullOrEmpty(ManageError);

    public string ManageDeleteHint => Managing is null
        ? "输入工作区名称确认："
        : $"输入工作区名称「{Managing.Name}」确认：";

    public bool CanConfirmDelete => Managing is not null &&
        string.Equals(DeleteConfirmText.Trim(), Managing.Name.Trim(), StringComparison.Ordinal);

    partial void OnManagingChanged(WorkspaceItem? value)
    {
        ManageName = value?.Name ?? string.Empty;
        ManageDescription = value?.Description ?? string.Empty;
        InviteLink = string.Empty;
        InviteUserId = string.Empty;
        DeleteConfirmText = string.Empty;
        ManageError = null;
        // task-8：换工作区时不保留上一个空间的「本人受限」提示与 ACL 对话框状态。
        MyAclText = null;
        MyAclRestricted = false;
        IsAclOpen = false;
        AclMember = null;
        AclError = null;
        OnPropertyChanged(nameof(IsOwner));
        OnPropertyChanged(nameof(ManageRoleText));
        OnPropertyChanged(nameof(ManageMetaText));
        OnPropertyChanged(nameof(CanConfirmDelete));
        OnPropertyChanged(nameof(ManageDeleteHint));
    }

    partial void OnManageErrorChanged(string? value) => OnPropertyChanged(nameof(HasManageError));

    partial void OnInviteLinkChanged(string value) => OnPropertyChanged(nameof(HasInviteLink));

    partial void OnDeleteConfirmTextChanged(string value) => OnPropertyChanged(nameof(CanConfirmDelete));

    [RelayCommand]
    private async Task ManageAsync(WorkspaceItem? workspace)
    {
        if (workspace is null) return;

        Managing = workspace;
        IsManageOpen = true;
        // task-135：清空成员列表也走逐项移除（Reset 残留容器会让「成员」越刷新越多）。
        CollectionSync.Apply(Members, Array.Empty<WorkspaceMemberItem>());
        OnPropertyChanged(nameof(HasMembers));
        await RefreshMembersFullAsync(workspace).ConfigureAwait(true);
    }

    [RelayCommand]
    private void CloseManage()
    {
        IsManageOpen = false;
        IsDeleteConfirmOpen = false;
        Managing = null;
    }

    [RelayCommand]
    private async Task SaveManageAsync()
    {
        if (Managing is null || !IsOwner) return;
        if (string.IsNullOrWhiteSpace(ManageName))
        {
            ManageError = "名称不能为空";
            return;
        }

        ManageBusy = true;
        ManageError = null;
        try
        {
            await _workspaces.UpdateAsync(Managing, ManageName.Trim(), ManageDescription).ConfigureAwait(true);
            _notify.Notify("工作区信息已保存", NoticeKind.Success);
            Managing = Workspaces.FirstOrDefault(w => w.Id == Managing.Id) ?? Managing;
        }
        catch (Exception ex)
        {
            ManageError = ex.Message;
        }
        finally
        {
            ManageBusy = false;
        }
    }

    [RelayCommand]
    private async Task GenerateLinkAsync()
    {
        if (Managing is null) return;

        ManageBusy = true;
        ManageError = null;
        try
        {
            InviteLink = await _workspaces.GenerateInviteLinkAsync(Managing).ConfigureAwait(true) ?? string.Empty;
            if (!HasInviteLink) ManageError = "服务器未返回邀请链接";
        }
        catch (Exception ex)
        {
            ManageError = ex.Message;
        }
        finally
        {
            ManageBusy = false;
        }
    }

    [RelayCommand]
    private async Task InviteFriendAsync()
    {
        if (Managing is null || !IsOwner) return;
        if (string.IsNullOrWhiteSpace(InviteUserId))
        {
            ManageError = "请输入对方用户 ID";
            return;
        }

        ManageBusy = true;
        ManageError = null;
        try
        {
            await _workspaces.InviteFriendAsync(Managing, InviteUserId).ConfigureAwait(true);
            _notify.Notify("邀请已发送，等待对方接受", NoticeKind.Success);
            InviteUserId = string.Empty;
        }
        catch (Exception ex)
        {
            ManageError = ex.Message;
        }
        finally
        {
            ManageBusy = false;
        }
    }

    [RelayCommand]
    private async Task RemoveMemberAsync(WorkspaceMemberItem? member)
    {
        if (Managing is null || member is null || !member.CanRemove) return;

        try
        {
            await _workspaces.RemoveMemberAsync(Managing, member).ConfigureAwait(true);
            Members.Remove(member);
            OnPropertyChanged(nameof(HasMembers));
            _notify.Notify($"已移除成员 {member.Nickname}", NoticeKind.Success);
            Managing = Workspaces.FirstOrDefault(w => w.Id == Managing.Id) ?? Managing;
        }
        catch (Exception ex)
        {
            ManageError = ex.Message;
        }
    }

    // ── 成员角色（owner 可把成员设为管理员/普通成员） ──────────────
    [ObservableProperty]
    private bool _isRoleConfirmOpen;

    [ObservableProperty]
    private WorkspaceMemberItem? _pendingRoleMember;

    [ObservableProperty]
    private int _pendingRole;

    public string RoleConfirmTitle => PendingRoleMember is null
        ? string.Empty
        : $"把「{PendingRoleMember.Nickname}」设为{(PendingRole == 1 ? "管理员" : "普通成员")}？";

    public string RoleConfirmHint => PendingRole == 1
        ? "管理员可以邀请成员、管理文件。"
        : "改为普通成员后将失去邀请与管理权限，仅保留查看与上传。";

    partial void OnPendingRoleChanged(int value)
    {
        OnPropertyChanged(nameof(RoleConfirmTitle));
        OnPropertyChanged(nameof(RoleConfirmHint));
    }

    partial void OnPendingRoleMemberChanged(WorkspaceMemberItem? value)
    {
        OnPropertyChanged(nameof(RoleConfirmTitle));
        OnPropertyChanged(nameof(RoleConfirmHint));
    }

    /// <summary>由成员行的角色下拉调用：先弹二次确认，确认后才真正调接口。</summary>
    public void RequestMemberRole(WorkspaceMemberItem member, int role)
    {
        if (Managing is null || !IsOwner || !member.CanChangeRole) return;
        if (role is not (1 or 2) || member.Role == role) return;

        PendingRoleMember = member;
        PendingRole = role;
        IsRoleConfirmOpen = true;
    }

    [RelayCommand]
    private async Task ConfirmRoleChangeAsync()
    {
        var member = PendingRoleMember;
        var role = PendingRole;
        var managing = Managing;
        if (managing is null || member is null) return;

        ManageBusy = true;
        ManageError = null;
        try
        {
            await _workspaces.SetMemberRoleAsync(managing, member, role).ConfigureAwait(true);
            member.Role = role;
            await RefreshMembersFullAsync(managing).ConfigureAwait(true);
            _notify.Notify($"已将 {member.Nickname} 设为{(role == 1 ? "管理员" : "普通成员")}", NoticeKind.Success);
        }
        catch (Exception ex)
        {
            ManageError = ex.Message;
        }
        finally
        {
            ManageBusy = false;
            IsRoleConfirmOpen = false;
            PendingRoleMember = null;
        }
    }

    [RelayCommand]
    private void CancelRoleChange()
    {
        IsRoleConfirmOpen = false;
        PendingRoleMember = null;
    }

    private async Task RefreshMembersAsync(WorkspaceItem workspace)
    {
        try
        {
            var members = await _workspaces.GetMembersAsync(workspace).ConfigureAwait(true);
            // task-135：逐项同步，避免 Clear 的 Reset 残留成员卡片。
            CollectionSync.Apply(Members, members);
        }
        catch (Exception ex)
        {
            ManageError = ex.Message;
        }
        OnPropertyChanged(nameof(HasMembers));
    }

    /// <summary>
    /// task-8：成员列表 + 每个可管理成员的文件权限摘要 + 本人是否受限。
    /// 摘要失败只回退成「权限」入口（不谎报「全部文件」），不影响成员列表本身。
    /// </summary>
    private async Task RefreshMembersFullAsync(WorkspaceItem workspace)
    {
        await RefreshMembersAsync(workspace).ConfigureAwait(true);
        await RefreshMemberAclSummariesAsync(workspace).ConfigureAwait(true);
        await RefreshMyAclAsync(workspace).ConfigureAwait(true);
    }

    // ── 危险区 ──────────────────────────────────────────────────
    [RelayCommand]
    private void RequestDelete()
    {
        if (Managing is null || !IsOwner) return;
        DeleteConfirmText = string.Empty;
        IsDeleteConfirmOpen = true;
    }

    [RelayCommand]
    private void CancelDelete()
    {
        IsDeleteConfirmOpen = false;
        DeleteConfirmText = string.Empty;
    }

    [RelayCommand]
    private async Task ConfirmDeleteAsync()
    {
        if (Managing is null || !CanConfirmDelete) return;

        var name = Managing.Name;
        ManageBusy = true;
        ManageError = null;
        try
        {
            await _workspaces.DeleteAsync(Managing).ConfigureAwait(true);
            _notify.Notify($"已删除工作区「{name}」", NoticeKind.Success);
            IsDeleteConfirmOpen = false;
            IsManageOpen = false;
            Managing = null;
        }
        catch (Exception ex)
        {
            ManageError = ex.Message;
        }
        finally
        {
            ManageBusy = false;
        }
    }

    private static string FormatSize(long bytes)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        double value = Math.Max(0, bytes);
        var unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }
        return unit == 0 ? $"{value:0} {units[unit]}" : $"{value:0.#} {units[unit]}";
    }

    // ── 加入工作区（粘贴邀请链接/邀请码） ─────────────────────────

    [ObservableProperty]
    private bool _isJoinInviteOpen;

    [ObservableProperty]
    private string _joinInviteInput = string.Empty;

    [ObservableProperty]
    private string? _joinInviteError;

    [ObservableProperty]
    private WorkspaceInvitePreview? _joinInvitePreview;

    [ObservableProperty]
    private bool _isJoiningInvite;

    /// <summary>已成功解析/校验的邀请 token（加入按钮使用它，避免重复解析输入框）。</summary>
    private string? _verifiedInviteToken;

    public bool HasJoinInvitePreview => JoinInvitePreview is not null;

    public bool CanJoinInvite => !IsJoiningInvite && _verifiedInviteToken is not null;

    public string JoinInvitePreviewText => JoinInvitePreview?.SummaryText ?? string.Empty;

    partial void OnJoinInvitePreviewChanged(WorkspaceInvitePreview? value)
    {
        OnPropertyChanged(nameof(HasJoinInvitePreview));
        OnPropertyChanged(nameof(JoinInvitePreviewText));
        OnPropertyChanged(nameof(CanJoinInvite));
    }

    partial void OnIsJoiningInviteChanged(bool value) => OnPropertyChanged(nameof(CanJoinInvite));

    partial void OnJoinInviteInputChanged(string value)
    {
        // 输入变化后旧的校验结果立即作废，必须重新点「验证链接」。
        _verifiedInviteToken = null;
        JoinInvitePreview = null;
        JoinInviteError = null;
        OnPropertyChanged(nameof(CanJoinInvite));
    }

    [RelayCommand]
    private void OpenJoinInvite()
    {
        JoinInviteInput = string.Empty;
        JoinInviteError = null;
        JoinInvitePreview = null;
        _verifiedInviteToken = null;
        IsJoinInviteOpen = true;
    }

    [RelayCommand]
    private void CloseJoinInvite() => IsJoinInviteOpen = false;

    [RelayCommand]
    private async Task VerifyJoinInviteAsync()
    {
        if (IsJoiningInvite) return;

        var token = ExtractInviteToken(JoinInviteInput);
        if (token is null)
        {
            JoinInviteError = "请粘贴完整的邀请链接（…/i/xxxx）或 32/43 位邀请码";
            JoinInvitePreview = null;
            _verifiedInviteToken = null;
            OnPropertyChanged(nameof(CanJoinInvite));
            return;
        }

        IsJoiningInvite = true;
        JoinInviteError = null;
        try
        {
            var preview = await _workspaces.VerifyInviteLinkAsync(token).ConfigureAwait(true);
            JoinInvitePreview = preview;

            // 2026-10-29：已经在（或自己拥有的）工作区不应显示可加入——客户端先拦；
            // 后端 accept 也会对「已是成员」返回 400（双保险）。
            if (!string.IsNullOrEmpty(preview.WorkspaceId)
                && Workspaces.Any(w => string.Equals(w.Id, preview.WorkspaceId, StringComparison.Ordinal)))
            {
                _verifiedInviteToken = null;
                JoinInviteError = $"你已经是「{preview.WorkspaceName}」的成员，无需重复加入";
                OnPropertyChanged(nameof(CanJoinInvite));
                return;
            }

            _verifiedInviteToken = token;
        }
        catch (Exception ex)
        {
            JoinInviteError = LoadRetry.Humanize(ex.Message, "邀请链接无效或已过期，请向邀请人重新索取");
            JoinInvitePreview = null;
            _verifiedInviteToken = null;
        }
        finally
        {
            IsJoiningInvite = false;
        }
    }

    [RelayCommand]
    private async Task JoinInviteAsync()
    {
        if (IsJoiningInvite || _verifiedInviteToken is null) return;

        var token = _verifiedInviteToken;
        var workspaceName = JoinInvitePreview?.WorkspaceName;
        IsJoiningInvite = true;
        JoinInviteError = null;
        try
        {
            await _workspaces.AcceptInviteLinkAsync(token).ConfigureAwait(true);
            IsJoinInviteOpen = false;
            _notify.Notify(
                string.IsNullOrWhiteSpace(workspaceName) ? "已加入工作区" : $"已加入工作区「{workspaceName}」",
                NoticeKind.Success);
            await LoadAsync().ConfigureAwait(true);
        }
        catch (Exception ex)
        {
            JoinInviteError = LoadRetry.Humanize(ex.Message, "加入工作区失败，请稍后重试");
        }
        finally
        {
            IsJoiningInvite = false;
        }
    }

    /// <summary>
    /// 从用户粘贴的内容里取出邀请 token：完整链接（/i/{token} 或旧 /api/invite/{token}）、
    /// 带查询参数的链接、或直接粘贴的 32/43 位邀请码都支持。
    /// 形状不合法返回 null（不把明显错误的内容发给服务端）。
    /// </summary>
    internal static string? ExtractInviteToken(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;

        var value = raw.Trim();
        var cut = value.IndexOfAny(['?', '#']);
        if (cut >= 0) value = value[..cut];
        value = value.TrimEnd('/');

        var marker = value.LastIndexOf("/i/", StringComparison.OrdinalIgnoreCase);
        if (marker >= 0) value = value[(marker + 3)..];
        else
        {
            var legacy = value.LastIndexOf("/api/invite/", StringComparison.OrdinalIgnoreCase);
            if (legacy >= 0) value = value[(legacy + "/api/invite/".Length)..];
        }

        value = Uri.UnescapeDataString(value).Trim();
        if (value.Length is not (32 or 43)) return null;

        foreach (var c in value)
        {
            if (!char.IsAsciiLetterOrDigit(c) && c != '-' && c != '_') return null;
        }
        return value;
    }

    // ══════════════════════════════════════════════════════════════
    //  task-8：成员文件级 ACL（访问范围 all / restricted + 授权项）
    //  入口：管理抽屉 → 成员行「权限」。保存 = PUT 整包替换，成功后回填服务端归一化结果。
    // ══════════════════════════════════════════════════════════════

    [ObservableProperty]
    private bool _isAclOpen;

    [ObservableProperty]
    private WorkspaceMemberItem? _aclMember;

    /// <summary>访问范围是否为 restricted（false = 全部文件 / all，服务端默认）。</summary>
    [ObservableProperty]
    private bool _aclRestricted;

    [ObservableProperty]
    private bool _aclBusy;

    [ObservableProperty]
    private string? _aclError;

    /// <summary>授权选择器当前浏览的目录（工作区相对路径，<c>/</c> 为根）。</summary>
    [ObservableProperty]
    private string _aclBrowsePath = "/";

    /// <summary>「筛选当前目录」关键词：只过滤已加载的列表，不发请求。</summary>
    [ObservableProperty]
    private string _aclFilter = string.Empty;

    /// <summary>新增授权默认是否可写（可写会自动带可读）。</summary>
    [ObservableProperty]
    private bool _aclNewCanWrite;

    [ObservableProperty]
    private bool _aclBrowseLoading;

    [ObservableProperty]
    private string? _aclBrowseError;

    /// <summary>本人（当前登录用户）在该工作区是否为受限成员 + 提示文案。</summary>
    [ObservableProperty]
    private bool _myAclRestricted;

    [ObservableProperty]
    private string? _myAclText;

    /// <summary>待保存的授权项（保存时整包 PUT；成功后用服务端归一化结果回填）。</summary>
    public ObservableCollection<WorkspaceAclGrant> AclGrants { get; } = [];

    /// <summary>当前目录下的文件夹（点击进入下一层）。</summary>
    public ObservableCollection<NodeItem> AclFolders { get; } = [];

    /// <summary>当前目录下的文件（点击「授权」加入列表）。</summary>
    public ObservableCollection<NodeItem> AclFiles { get; } = [];

    private List<NodeItem> _aclEntries = [];

    public string AclMemberName => AclMember?.Nickname ?? string.Empty;

    public string AclTitle => string.IsNullOrEmpty(AclMemberName) ? "文件权限" : $"文件权限 · {AclMemberName}";

    public string AclBrowsePathText => AclBrowsePath;

    public bool CanAclGoUp => AclBrowsePath != "/";

    public bool HasAclGrants => AclGrants.Count > 0;

    /// <summary>restricted + 空授权 = 该成员什么都看不到（服务端允许，用于临时冻结），必须显式提醒。</summary>
    public bool AclEmptyWarning => AclRestricted && AclGrants.Count == 0;

    public string AclGrantCountText => AclGrants.Count == 0 ? "未添加授权" : $"已授权 {AclGrants.Count} 项";

    public bool HasAclError => !string.IsNullOrEmpty(AclError);

    public bool HasAclBrowseError => !string.IsNullOrEmpty(AclBrowseError);

    public bool HasMyAclHint => !string.IsNullOrEmpty(MyAclText);

    public bool HasAclFolders => AclFolders.Count > 0;

    public bool HasAclFiles => AclFiles.Count > 0;

    public bool IsAclBrowseEmpty => !AclBrowseLoading && !HasAclFolders && !HasAclFiles && !HasAclBrowseError;

    /// <summary>访问范围分段按钮的选中态（用显式属性避免 XAML 里做取反绑定）。</summary>
    public bool IsAclModeAll => !AclRestricted;

    public bool IsAclModeRestricted => AclRestricted;

    partial void OnAclMemberChanged(WorkspaceMemberItem? value)
    {
        OnPropertyChanged(nameof(AclMemberName));
        OnPropertyChanged(nameof(AclTitle));
    }

    partial void OnAclRestrictedChanged(bool value)
    {
        OnPropertyChanged(nameof(AclEmptyWarning));
        OnPropertyChanged(nameof(IsAclModeAll));
        OnPropertyChanged(nameof(IsAclModeRestricted));
    }

    partial void OnAclBrowsePathChanged(string value)
    {
        OnPropertyChanged(nameof(AclBrowsePathText));
        OnPropertyChanged(nameof(CanAclGoUp));
    }

    partial void OnAclFilterChanged(string value) => ApplyAclEntryFilter();

    partial void OnAclErrorChanged(string? value) => OnPropertyChanged(nameof(HasAclError));

    partial void OnAclBrowseErrorChanged(string? value) => OnPropertyChanged(nameof(HasAclBrowseError));

    partial void OnAclBrowseLoadingChanged(bool value) => OnPropertyChanged(nameof(IsAclBrowseEmpty));

    partial void OnMyAclTextChanged(string? value) => OnPropertyChanged(nameof(HasMyAclHint));

    private void RaiseAclGrantsChanged()
    {
        OnPropertyChanged(nameof(HasAclGrants));
        OnPropertyChanged(nameof(AclEmptyWarning));
        OnPropertyChanged(nameof(AclGrantCountText));
    }

    private void RaiseAclEntriesChanged()
    {
        OnPropertyChanged(nameof(HasAclFolders));
        OnPropertyChanged(nameof(HasAclFiles));
        OnPropertyChanged(nameof(IsAclBrowseEmpty));
    }

    /// <summary>管理抽屉 → 成员行「权限」：打开对话框并加载该成员当前范围。</summary>
    [RelayCommand]
    private async Task OpenMemberAclAsync(WorkspaceMemberItem? member)
    {
        if (Managing is null || member is null || !member.CanManageAcl) return;

        AclMember = member;
        IsAclOpen = true;
        AclRestricted = false;
        AclError = null;
        AclBrowseError = null;
        AclFilter = string.Empty;
        AclBrowsePath = "/";
        AclNewCanWrite = false;
        _aclEntries = [];
        ApplyAclEntryFilter();
        CollectionSync.Apply(AclGrants, Array.Empty<WorkspaceAclGrant>());
        RaiseAclGrantsChanged();

        var workspace = Managing;
        try
        {
            var acl = await _workspaces.GetMemberAclAsync(workspace, member).ConfigureAwait(true);
            AclRestricted = acl.IsRestricted;
            CollectionSync.Apply(AclGrants, acl.Grants);
            RaiseAclGrantsChanged();
            ApplyAclSummary(member, acl);
        }
        catch (Exception ex)
        {
            // 失败原文展示（403/404 由后端给出），不假装是「全部文件」。
            AclError = ex.Message;
        }

        await RefreshAclEntriesAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private void CloseAcl()
    {
        IsAclOpen = false;
        AclMember = null;
        AclError = null;
        AclBrowseError = null;
        _aclEntries = [];
        ApplyAclEntryFilter();
        CollectionSync.Apply(AclGrants, Array.Empty<WorkspaceAclGrant>());
        RaiseAclGrantsChanged();
    }

    /// <summary>访问范围：全部文件（all）。</summary>
    [RelayCommand]
    private void SetAclModeAll() => AclRestricted = false;

    /// <summary>访问范围：仅指定目录与文件（restricted）。</summary>
    [RelayCommand]
    private void SetAclModeRestricted() => AclRestricted = true;

    /// <summary>把当前浏览目录加进授权（目录前缀授权，覆盖其子目录）。</summary>
    [RelayCommand]
    private void AddAclFolder()
    {
        var path = string.IsNullOrWhiteSpace(AclBrowsePath) || AclBrowsePath == "/"
            ? "/"
            : AclBrowsePath.TrimEnd('/');

        // 添加授权即意味着进入受限模式（否则 all 会忽略授权表，用户会以为白点）。
        AclRestricted = true;
        AddAclGrant(new WorkspaceAclGrant
        {
            ScopeType = "folder",
            ScopePath = path,
            CanRead = true,
            CanWrite = AclNewCanWrite
        });
    }

    /// <summary>把当前目录里的某个文件加进授权（单文件授权）。</summary>
    [RelayCommand]
    private void AddAclFile(NodeItem? file)
    {
        if (file is null || file.IsFolder) return;

        AclRestricted = true;
        AddAclGrant(new WorkspaceAclGrant
        {
            ScopeType = "file",
            ScopePath = LogicalPathOf(file),
            FileId = file.Id,
            CanRead = true,
            CanWrite = AclNewCanWrite
        });
    }

    [RelayCommand]
    private void RemoveAclGrant(WorkspaceAclGrant? grant)
    {
        if (grant is null) return;
        AclGrants.Remove(grant);
        RaiseAclGrantsChanged();
    }

    [RelayCommand]
    private void ClearAclGrants()
    {
        CollectionSync.Apply(AclGrants, Array.Empty<WorkspaceAclGrant>());
        RaiseAclGrantsChanged();
    }

    /// <summary>进入子目录（授权选择器）。</summary>
    [RelayCommand]
    private async Task AclEnterFolderAsync(NodeItem? folder)
    {
        if (folder is null || !folder.IsFolder) return;
        AclBrowsePath = LogicalPathOf(folder);
        await RefreshAclEntriesAsync().ConfigureAwait(true);
    }

    /// <summary>授权选择器上一级。</summary>
    [RelayCommand]
    private async Task AclGoUpAsync()
    {
        if (AclBrowsePath == "/") return;
        var index = AclBrowsePath.LastIndexOf('/');
        AclBrowsePath = index <= 0 ? "/" : AclBrowsePath[..index];
        await RefreshAclEntriesAsync().ConfigureAwait(true);
    }

    /// <summary>保存 = PUT 整包替换；失败原文展示，成功后回填服务端归一化结果并刷新成员行摘要。</summary>
    [RelayCommand]
    private async Task SaveAclAsync()
    {
        if (Managing is null || AclMember is null || AclBusy) return;

        var workspace = Managing;
        var member = AclMember;
        var mode = AclRestricted ? "restricted" : "all";
        var grants = AclRestricted ? AclGrants.ToList() : [];

        AclBusy = true;
        AclError = null;
        try
        {
            var saved = await _workspaces
                .SetMemberAclAsync(workspace, member, mode, grants)
                .ConfigureAwait(true);

            AclRestricted = saved.IsRestricted;
            CollectionSync.Apply(AclGrants, saved.Grants);
            RaiseAclGrantsChanged();
            ApplyAclSummary(member, saved);

            _notify.Notify(
                saved.IsRestricted
                    ? $"已保存 {member.Nickname} 的文件权限：受限 {saved.Grants.Count} 项"
                    : $"已保存 {member.Nickname} 的文件权限：全部文件",
                NoticeKind.Success);
        }
        catch (Exception ex)
        {
            AclError = ex.Message;
        }
        finally
        {
            AclBusy = false;
        }
    }

    private void AddAclGrant(WorkspaceAclGrant grant)
    {
        var existing = AclGrants.FirstOrDefault(g => g.Key == grant.Key);
        if (existing is not null)
        {
            // 同路径/同文件重复添加：按 OR 合并权限（与服务端归一化口径一致），不产生重复项。
            if (grant.CanRead) existing.CanRead = true;
            if (grant.CanWrite) existing.CanWrite = true;
            AclError = null;
            _notify.Notify($"「{grant.PathText}」已在授权列表，已合并权限", NoticeKind.Info);
            return;
        }

        AclGrants.Add(grant);
        AclError = null;
        RaiseAclGrantsChanged();
    }

    private async Task RefreshAclEntriesAsync()
    {
        if (Managing is null) return;

        var workspace = Managing;
        AclBrowseLoading = true;
        AclBrowseError = null;
        try
        {
            var (entries, error) = await _workspaces
                .ListAclEntriesAsync(workspace, AclBrowsePath)
                .ConfigureAwait(true);

            if (entries is null)
            {
                // 后端原文（404「目录不存在」/403 等）直接展示，不伪造空列表。
                _aclEntries = [];
                ApplyAclEntryFilter();
                AclBrowseError = string.IsNullOrWhiteSpace(error) ? "无法加载目录" : error;
                return;
            }

            _aclEntries = entries.ToList();
            ApplyAclEntryFilter();
        }
        catch (Exception ex)
        {
            _aclEntries = [];
            ApplyAclEntryFilter();
            AclBrowseError = ex.Message;
        }
        finally
        {
            AclBrowseLoading = false;
        }
    }

    /// <summary>「筛选当前目录」：只过滤已加载的条目（不发请求）。</summary>
    private void ApplyAclEntryFilter()
    {
        var keyword = AclFilter?.Trim() ?? string.Empty;
        IEnumerable<NodeItem> source = _aclEntries;
        if (keyword.Length > 0)
        {
            source = source.Where(n => n.Name.Contains(keyword, StringComparison.CurrentCultureIgnoreCase));
        }

        var list = source.ToList();
        CollectionSync.Apply(AclFolders, list.Where(n => n.IsFolder).ToList());
        CollectionSync.Apply(AclFiles, list.Where(n => !n.IsFolder).ToList());
        RaiseAclEntriesChanged();
    }

    /// <summary>节点 → 工作区逻辑路径（<c>/a/b</c>）。服务端会以 DB 里的真实路径回填授权。</summary>
    private static string LogicalPathOf(NodeItem node)
    {
        var parent = string.IsNullOrWhiteSpace(node.ParentPath) || node.ParentPath == "/"
            ? "/"
            : node.ParentPath.TrimEnd('/');
        return parent == "/" ? $"/{node.Name}" : $"{parent}/{node.Name}";
    }

    private static void ApplyAclSummary(WorkspaceMemberItem member, WorkspaceMemberAcl acl)
    {
        member.AclRestricted = acl.IsRestricted;
        member.AclSummary = acl.SummaryText;
        member.AclLoaded = true;
    }

    /// <summary>成员行摘要：只对「我可以管理权限的普通成员」拉取（owner/admin 目标后端会直接返回 all）。</summary>
    private async Task RefreshMemberAclSummariesAsync(WorkspaceItem workspace)
    {
        var targets = Members.Where(m => m.CanManageAcl).ToList();
        if (targets.Count == 0) return;

        // 并行拉取（成员数通常个位数）；失败只影响该行摘要，不影响成员列表。
        await Task.WhenAll(targets.Select(async member =>
        {
            try
            {
                var acl = await _workspaces.GetMemberAclAsync(workspace, member).ConfigureAwait(true);
                ApplyAclSummary(member, acl);
            }
            catch
            {
                // 回退成「权限」入口，不谎报「全部文件」。
                member.AclLoaded = false;
            }
        })).ConfigureAwait(true);
    }

    /// <summary>本人是否在该工作区受限（GET …/acl/me）：受限成员在抽屉里得到明确提示。</summary>
    private async Task RefreshMyAclAsync(WorkspaceItem workspace)
    {
        try
        {
            var acl = await _workspaces.GetMyAclAsync(workspace).ConfigureAwait(true);
            MyAclRestricted = acl.IsRestricted;
            MyAclText = acl.IsRestricted
                ? $"你在该工作区是「受限成员」：只能看到被授权的 {acl.Grants.Count} 项目录/文件，其余内容在文件页不可见。"
                : null;
        }
        catch
        {
            // 老版本后端 / 网络异常：不显示提示，不阻塞管理抽屉。
            MyAclRestricted = false;
            MyAclText = null;
        }
    }
}
