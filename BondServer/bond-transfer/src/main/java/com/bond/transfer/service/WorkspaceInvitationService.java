package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.dto.InvitationDTO;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceInvitation;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.enums.WorkspaceRole;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.SnowflakeId;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceInvitationMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkspaceInvitationService {

    private final WorkspaceInvitationMapper invitationMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final WorkspaceMapper workspaceMapper;
    private final UserMapper userMapper;
    private final ConfigService configService;

    public String generateInviteLink(Long workspaceId, Long inviterId) {
        checkMemberRole(workspaceId, inviterId);

        int maxJoin = configService.getInt("workspace.max_join", 10);
        long joinCount = memberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getUserId, inviterId));
        // we only check target user limit on accept, not on link generation

        String token = newInviteToken();
        int expireDays = configService.getInt("workspace.invite_expire_days", 7);

        WorkspaceInvitation inv = new WorkspaceInvitation();
        inv.setId(SnowflakeId.nextId());
        inv.setWorkspaceId(workspaceId);
        inv.setInviterId(inviterId);
        inv.setInviteeId(null); // generic link, no specific invitee
        inv.setInviteToken(token);
        inv.setRole(WorkspaceRole.MEMBER.getValue());
        inv.setStatus(0);
        inv.setExpiresAt(LocalDateTime.now().plusDays(expireDays));
        invitationMapper.insert(inv);

        String base = configService.get("invite.public_base_url", "https://bond-api.sg.skkk.uno");
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/i/" + token;
    }

    /**
     * 邀请 token：32 字节 {@link java.security.SecureRandom} → base64url（无填充，43 字符）。
     *
     * <p>口径与分享链接一致（不可枚举，不用可预测的 UUID hex）；存量 32 位 UUID-hex 链接仍按原
     * token 查询，落地页与白名单同时保留 32/43 两种精确长度以兼容旧链接。</p>
     */
    private static String newInviteToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public InvitationDTO verifyInviteLink(String token) {
        WorkspaceInvitation inv = invitationMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceInvitation>()
                        .eq(WorkspaceInvitation::getInviteToken, token)
                        .eq(WorkspaceInvitation::getStatus, 0));
        if (inv == null) throw new BusinessException(404, "邀请链接无效或已过期");
        if (inv.getExpiresAt().isBefore(LocalDateTime.now())) {
            inv.setStatus(3); // expired
            invitationMapper.updateById(inv);
            throw new BusinessException(400, "邀请链接已过期");
        }

        Workspace ws = workspaceMapper.selectById(inv.getWorkspaceId());
        var inviter = userMapper.selectById(inv.getInviterId());
        InvitationDTO dto = new InvitationDTO();
        dto.setId(inv.getId());
        dto.setWorkspaceId(inv.getWorkspaceId());
        dto.setWorkspaceName(ws != null ? ws.getName() : "");
        dto.setInviterId(inv.getInviterId());
        dto.setInviterNickname(inviter != null ? inviter.getNickname() : "");
        dto.setInviteToken(token);
        dto.setRole(inv.getRole());
        dto.setStatus(inv.getStatus());
        dto.setExpiresAt(inv.getExpiresAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        return dto;
    }

    public void acceptInviteLink(String token, Long userId) {
        WorkspaceInvitation inv = invitationMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceInvitation>()
                        .eq(WorkspaceInvitation::getInviteToken, token)
                        .eq(WorkspaceInvitation::getStatus, 0));
        if (inv == null) throw new BusinessException(404, "邀请链接无效");
        if (inv.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(400, "邀请链接已过期");
        }

        // 2026-10-29：已是成员（含 owner 用自己工作区的链接）时不能再「加入」一次。
        // 旧实现由 joinWorkspace 静默跳过仍返回 200，客户端会显示「已加入」的假成功，
        // 且把链接错误标记为已使用；现在直接 400 并保持链接可用。
        ensureNotMember(inv.getWorkspaceId(), userId);

        joinWorkspace(inv.getWorkspaceId(), userId, inv.getRole());

        inv.setStatus(1);
        invitationMapper.updateById(inv);
    }

    public void inviteFriend(Long workspaceId, Long inviterId, Long inviteeId, int role) {
        checkMemberRole(workspaceId, inviterId);

        // Check if already member
        WorkspaceMember existing = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, inviteeId));
        if (existing != null) throw new BusinessException(400, "该用户已是工作区成员");

        int expireDays = configService.getInt("workspace.invite_expire_days", 7);

        // Check for existing pending invitation
        WorkspaceInvitation pending = invitationMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceInvitation>()
                        .eq(WorkspaceInvitation::getWorkspaceId, workspaceId)
                        .eq(WorkspaceInvitation::getInviteeId, inviteeId)
                        .eq(WorkspaceInvitation::getStatus, 0));
        if (pending != null) throw new BusinessException(400, "已发送过邀请，请等待对方处理");

        WorkspaceInvitation inv = new WorkspaceInvitation();
        inv.setId(SnowflakeId.nextId());
        inv.setWorkspaceId(workspaceId);
        inv.setInviterId(inviterId);
        inv.setInviteeId(inviteeId);
        inv.setInviteToken(newInviteToken());
        inv.setRole(role);
        inv.setStatus(0);
        inv.setExpiresAt(LocalDateTime.now().plusDays(expireDays));
        invitationMapper.insert(inv);
    }

    public List<InvitationDTO> getPendingInvitations(Long userId) {
        List<WorkspaceInvitation> list = invitationMapper.selectList(
                new LambdaQueryWrapper<WorkspaceInvitation>()
                        .eq(WorkspaceInvitation::getInviteeId, userId)
                        .eq(WorkspaceInvitation::getStatus, 0)
                        .orderByDesc(WorkspaceInvitation::getCreatedAt));

        return list.stream().map(inv -> {
            Workspace ws = workspaceMapper.selectById(inv.getWorkspaceId());
            var inviter = userMapper.selectById(inv.getInviterId());
            InvitationDTO dto = new InvitationDTO();
            dto.setId(inv.getId());
            dto.setWorkspaceId(inv.getWorkspaceId());
            dto.setWorkspaceName(ws != null ? ws.getName() : "");
            dto.setInviterId(inv.getInviterId());
            dto.setInviterNickname(inviter != null ? inviter.getNickname() : "");
            dto.setRole(inv.getRole());
            dto.setStatus(inv.getStatus());
            dto.setExpiresAt(inv.getExpiresAt() != null
                    ? inv.getExpiresAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
            dto.setCreatedAt(inv.getCreatedAt() != null
                    ? inv.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
            return dto;
        }).collect(Collectors.toList());
    }

    public void acceptInvitation(Long invitationId, Long userId) {
        WorkspaceInvitation inv = invitationMapper.selectById(invitationId);
        if (inv == null || !inv.getInviteeId().equals(userId)) {
            throw new BusinessException(404, "邀请不存在");
        }
        if (inv.getStatus() != 0) throw new BusinessException(400, "该邀请已处理");
        if (inv.getExpiresAt().isBefore(LocalDateTime.now())) {
            inv.setStatus(3);
            invitationMapper.updateById(inv);
            throw new BusinessException(400, "邀请已过期");
        }

        ensureNotMember(inv.getWorkspaceId(), userId);

        joinWorkspace(inv.getWorkspaceId(), userId, inv.getRole());
        inv.setStatus(1);
        invitationMapper.updateById(inv);
    }

    /** 已加入（含 owner 自己）时返回 400，避免「接受邀请」静默成功。 */
    private void ensureNotMember(Long workspaceId, Long userId) {
        WorkspaceMember existing = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, userId));
        if (existing != null) {
            throw new BusinessException(400, "你已经是该工作区的成员，无需重复加入");
        }
    }

    public void rejectInvitation(Long invitationId, Long userId) {
        WorkspaceInvitation inv = invitationMapper.selectById(invitationId);
        if (inv == null || !inv.getInviteeId().equals(userId)) {
            throw new BusinessException(404, "邀请不存在");
        }
        inv.setStatus(2);
        invitationMapper.updateById(inv);
    }

    private void joinWorkspace(Long workspaceId, Long userId, int role) {
        // Check join limit
        int maxJoin = configService.getInt("workspace.max_join", 10);
        long joinCount = memberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getUserId, userId));
        if (joinCount >= maxJoin) {
            throw new BusinessException(400, "已达到最大加入工作区数量(" + maxJoin + ")");
        }

        // Check member limit
        int maxMembers = configService.getInt("workspace.max_members", 20);
        long memberCount = memberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getWorkspaceId, workspaceId));
        if (memberCount >= maxMembers) {
            throw new BusinessException(400, "工作区成员已满(" + maxMembers + ")");
        }

        // Check not already member
        WorkspaceMember existing = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, userId));
        if (existing != null) return; // already a member, silently skip

        WorkspaceMember member = new WorkspaceMember();
        member.setId(SnowflakeId.nextId());
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(role);
        memberMapper.insert(member);
    }

    private void checkMemberRole(Long workspaceId, Long userId) {
        WorkspaceMember member = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, userId));
        if (member == null) throw new BusinessException(403, "不是工作区成员");
        if (member.getRole() > WorkspaceRole.ADMIN.getValue()) {
            throw new BusinessException(403, "需要管理员权限");
        }
    }

    // ------------------------------------------------------------------
    // 公开落地页（task-159）：GET /i/{token}，以及浏览器访问 /api/invite/{token} 时
    // 按 Accept: text/html 返回同一页面。所有数据库文本一律 HTML 转义，禁止原样拼接。
    // ------------------------------------------------------------------

    /** 生成邀请落地页 HTML；任何状态都不抛异常（浏览器要看到可读页面而不是 JSON 报错）。 */
    public String landingHtml(String token) {
        WorkspaceInvitation inv = invitationMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceInvitation>()
                        .eq(WorkspaceInvitation::getInviteToken, token)
                        .last("LIMIT 1"));
        if (inv == null) {
            return invalidPage("邀请链接无效", "链接不存在或已被删除，请向工作区管理员重新索取邀请。");
        }
        String statusText;
        if (inv.getStatus() != null && inv.getStatus() == 1) {
            return invalidPage("邀请链接已失效", "该链接已被使用；如需再次加入，请让管理员重新生成邀请链接。");
        }
        if (inv.getStatus() != null && inv.getStatus() == 2) {
            return invalidPage("邀请链接已失效", "该邀请已被撤回；如需加入，请让管理员重新发送邀请。");
        }
        if ((inv.getStatus() != null && inv.getStatus() == 3)
                || (inv.getExpiresAt() != null && inv.getExpiresAt().isBefore(LocalDateTime.now()))) {
            return invalidPage("邀请链接已过期", "邀请链接已超过有效期；请让管理员重新生成一条新的链接。");
        }

        Workspace ws = workspaceMapper.selectById(inv.getWorkspaceId());
        if (ws == null) {
            return invalidPage("邀请链接无效", "目标工作区不存在或已被删除。");
        }
        var inviter = userMapper.selectById(inv.getInviterId());
        long memberCount = memberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getWorkspaceId, ws.getId()));
        String workspaceName = htmlEscape(ws.getName() == null ? "未命名工作区" : ws.getName());
        String inviterName = htmlEscape(inviter == null || inviter.getNickname() == null
                ? "未知用户" : inviter.getNickname());
        String sizeText = formatSize(ws.getUsedSize()) + " / " + formatSize(ws.getMaxSize());
        String expiry = inv.getExpiresAt() == null
                ? "长期有效"
                : "有效期至 " + inv.getExpiresAt().toLocalDate();

        String body = """
                <div class="card">
                  <h1>加入工作区</h1>
                  <div class="ws">%s</div>
                  <div class="meta">邀请人：%s<br/>成员：%d 人 · 已用空间：%s<br/>%s</div>
                  <button type="button" id="copy">复制邀请链接</button>
                  <a href="javascript:void(0)" id="open" style="display:inline-block;margin-left:12px;color:#E57153;">在客户端中打开</a>
                  <div class="tip">客户端加入方式：Bond → 工作区 → 加入工作区 → 粘贴邀请码（点「在客户端中打开」会自动复制邀请码）。</div>
                  <div class="tip">在 Bond 客户端的「加入工作区」中粘贴本链接即可加入；<br/>
                  暂不支持网页直接加入（当前没有 Web 登录页）。<br/>
                  手动输入邀请码：<code>%s</code></div>
                </div>
                <script>
                (function(){
                  var btn=document.getElementById('copy');
                  if(!btn)return;
                  btn.addEventListener('click',function(){
                    var link=window.location.origin+window.location.pathname;
                    var done=function(){btn.textContent='已复制';};
                    if(navigator.clipboard&&navigator.clipboard.writeText){
                      navigator.clipboard.writeText(link).then(done,function(){});
                    }else{
                      var input=document.createElement('textarea');
                      input.value=link;document.body.appendChild(input);input.select();
                      try{document.execCommand('copy');done();}catch(e){}
                      document.body.removeChild(input);
                    }
                  });
                  var openBtn=document.getElementById('open');
                  if(openBtn){openBtn.addEventListener('click',function(){
                    var codeEl=document.querySelector('code');
                    var code=codeEl?codeEl.textContent:'';
                    var ok=function(){openBtn.textContent='邀请码已复制';};
                    if(navigator.clipboard&&navigator.clipboard.writeText){
                      navigator.clipboard.writeText(code).then(ok,function(){});
                    }else{
                      var i=document.createElement('textarea');
                      i.value=code;document.body.appendChild(i);i.select();
                      try{document.execCommand('copy');ok();}catch(e){}
                      document.body.removeChild(i);
                    }
                  });}
                })();
                </script>
                """.formatted(workspaceName, inviterName, memberCount, sizeText, expiry, htmlEscape(token));
        return page("加入工作区 - Bond", body);
    }

    private String invalidPage(String title, String message) {
        String body = """
                <div class="card">
                  <h1>%s</h1>
                  <div class="meta">%s</div>
                </div>
                """.formatted(htmlEscape(title), htmlEscape(message));
        return page(title + " - Bond", body);
    }

    private String page(String title, String body) {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <meta name="color-scheme" content="light" />
                  <meta name="robots" content="noindex,nofollow" />
                  <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
                  <link rel="apple-touch-icon" href="/favicon.svg" />
                  <meta name="theme-color" content="#E57153" />
                  <title>%s</title>
                  <style>
                    :root{color-scheme:light}
                    *{box-sizing:border-box}
                    body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",sans-serif;background:#f5f6f8;color:#1f2329}
                    .card{max-width:420px;margin:12vh auto;background:#fff;border-radius:12px;padding:28px 24px;box-shadow:0 4px 20px rgba(0,0,0,.08)}
                    h1{font-size:18px;margin:0 0 18px}
                    .ws{font-weight:600;font-size:16px;word-break:break-all;margin-bottom:8px}
                    .meta{color:#8a8f99;font-size:13px;margin-bottom:18px;line-height:1.7}
                    button{display:inline-block;padding:10px 18px;border:0;border-radius:8px;background:#E57153;color:#fff;font-size:14px;cursor:pointer}
                    button:hover{background:#D46A4C}
                    .tip{margin-top:16px;color:#8a8f99;font-size:12px;line-height:1.7}
                    code{background:#f0f1f4;border-radius:4px;padding:1px 5px;word-break:break-all}
                    @media (max-width:480px){
                      .card{margin:8vh 12px;padding:22px 18px;border-radius:10px}
                      h1{font-size:17px}
                    }
                  </style>
                </head>
                <body>
                  %s
                </body>
                </html>
                """.formatted(htmlEscape(title), body);
    }

    private static String htmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String formatSize(Long bytes) {
        long value = bytes == null ? 0L : Math.max(0L, bytes);
        if (value < 1024) {
            return value + " B";
        }
        double kb = value / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0);
    }
}
