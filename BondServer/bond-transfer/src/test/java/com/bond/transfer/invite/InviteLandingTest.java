package com.bond.transfer.invite;

import com.bond.common.entity.User;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceInvitation;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceInvitationMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.WorkspaceInvitationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** task-159：邀请落地页 HTML（三态 + 转义 + 无外部资源）。 */
@ExtendWith(MockitoExtension.class)
class InviteLandingTest {

    private static final long WORKSPACE_ID = 10L;
    private static final long INVITER_ID = 7L;
    private static final String TOKEN = "0123456789abcdef0123456789abcdef"; // 32 位 UUID-hex

    @Mock
    private WorkspaceInvitationMapper invitationMapper;
    @Mock
    private WorkspaceMemberMapper memberMapper;
    @Mock
    private WorkspaceMapper workspaceMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ConfigService configService;

    @InjectMocks
    private WorkspaceInvitationService invitationService;

    @Test
    @DisplayName("有效链接：展示工作区名/邀请人/成员数 + 复制按钮，且不泄露 JSON")
    void validLinkShowsWorkspaceAndInviter() {
        stubInvitation(0, LocalDateTime.now().plusDays(7));
        stubWorkspace("研发组");
        stubInviter("张三");
        when(memberMapper.selectCount(any())).thenReturn(3L);

        String html = invitationService.landingHtml(TOKEN);

        assertTrue(html.contains("研发组"), html);
        assertTrue(html.contains("张三"), html);
        assertTrue(html.contains("3 人"), html);
        assertTrue(html.contains("复制邀请链接"), html);
        assertTrue(html.contains(TOKEN), "页面应提供手动输入兜底");
        assertTrue(html.contains("text/html") || html.contains("<!DOCTYPE html>"), html);
        assertFalse(html.contains("\"code\":200"), "不应输出 JSON 信封");
        assertFalse(html.contains("已过期"), html);
        assertFalse(html.contains("已失效"), html);
    }

    @Test
    @DisplayName("工作区名/昵称含 HTML 时全部转义，禁止原样拼接")
    void userControlledTextIsHtmlEscaped() {
        stubInvitation(0, LocalDateTime.now().plusDays(7));
        stubWorkspace("<script>alert(1)</script>");
        stubInviter("<img src=x onerror=alert(2)>");
        when(memberMapper.selectCount(any())).thenReturn(1L);

        String html = invitationService.landingHtml(TOKEN);

        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), html);
        assertTrue(html.contains("&lt;img src=x onerror=alert(2)&gt;"), html);
        assertFalse(html.contains("<script>alert(1)</script>"), "工作区名未被转义");
        assertFalse(html.contains("<img src=x"), "昵称未被转义");
    }

    @Test
    @DisplayName("已过期（status=3）→ 中文过期页，不泄露成员信息")
    void expiredByStatusShowsExpiredPage() {
        stubInvitation(3, LocalDateTime.now().minusDays(1));

        String html = invitationService.landingHtml(TOKEN);

        assertTrue(html.contains("已过期"), html);
        assertFalse(html.contains("复制邀请链接"), "过期页不应出现加入入口");
    }

    @Test
    @DisplayName("已过期（到期时间已过）→ 中文过期页")
    void expiredByTimeShowsExpiredPage() {
        stubInvitation(0, LocalDateTime.now().minusMinutes(1));

        String html = invitationService.landingHtml(TOKEN);

        assertTrue(html.contains("已过期"), html);
    }

    @Test
    @DisplayName("已使用（status=1）→ 失效页")
    void usedLinkShowsInvalidPage() {
        stubInvitation(1, LocalDateTime.now().plusDays(7));

        String html = invitationService.landingHtml(TOKEN);

        assertTrue(html.contains("已失效"), html);
    }

    @Test
    @DisplayName("已撤销（status=2）→ 失效页")
    void revokedLinkShowsInvalidPage() {
        stubInvitation(2, LocalDateTime.now().plusDays(7));

        String html = invitationService.landingHtml(TOKEN);

        assertTrue(html.contains("已失效"), html);
    }

    @Test
    @DisplayName("不存在的 token → 无效页而非抛异常")
    void missingLinkShowsInvalidPage() {
        when(invitationMapper.selectOne(any())).thenReturn(null);

        String html = invitationService.landingHtml(TOKEN);

        assertTrue(html.contains("无效"), html);
        assertFalse(html.contains("\"message\""), "不应返回 JSON");
    }

    @Test
    @DisplayName("页面不引外部 CDN/脚本（内网环境也能打开）")
    void pageHasNoExternalAssets() {
        stubInvitation(0, LocalDateTime.now().plusDays(7));
        stubWorkspace("团队");
        stubInviter("李四");
        when(memberMapper.selectCount(any())).thenReturn(2L);

        String html = invitationService.landingHtml(TOKEN);

        assertFalse(html.contains("http://"), html);
        assertFalse(html.contains("https://"), html);
        assertFalse(html.contains("cdn."), html);
    }

    private void stubInvitation(int status, LocalDateTime expiresAt) {
        WorkspaceInvitation inv = new WorkspaceInvitation();
        inv.setId(1L);
        inv.setWorkspaceId(WORKSPACE_ID);
        inv.setInviterId(INVITER_ID);
        inv.setInviteToken(TOKEN);
        inv.setStatus(status);
        inv.setExpiresAt(expiresAt);
        when(invitationMapper.selectOne(any())).thenReturn(inv);
    }

    private void stubWorkspace(String name) {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        workspace.setName(name);
        workspace.setUsedSize(1024L * 1024L);
        workspace.setMaxSize(1024L * 1024L * 512L);
        when(workspaceMapper.selectById(eq(WORKSPACE_ID))).thenReturn(workspace);
    }

    private void stubInviter(String nickname) {
        User user = new User();
        user.setId(INVITER_ID);
        user.setNickname(nickname);
        when(userMapper.selectById(eq(INVITER_ID))).thenReturn(user);
    }
}
