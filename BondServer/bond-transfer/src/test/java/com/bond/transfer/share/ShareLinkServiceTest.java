package com.bond.transfer.share;

import com.bond.common.entity.ShareLink;
import com.bond.common.entity.User;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.Entitlements;
import com.bond.transfer.entitlement.PlanLimits;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.mapper.ShareLinkMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.preview.PreviewService;
import com.bond.transfer.service.AuditService;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.storage.ObjectStorage;
import com.bond.transfer.share.ShareDtos.CreateShareRequest;
import com.bond.transfer.share.ShareDtos.PatchShareRequest;
import com.bond.transfer.share.ShareDtos.ShareLinkView;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分享链接服务：权限 / 计费归属 / 次数语义 / 提取码 / 状态码。
 *
 * <p>只做 mock 单测（无 DB、无 S3）：mapper 与存储都是 mock，重点是业务规则与调用契约。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShareLinkServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER = 8L;
    private static final Long PERSONAL_ID = 100L;
    private static final Long TEAM_ID = 200L;
    private static final Long FILE_ID = 999L;
    private static final long FILE_SIZE = 1000L;

    @Mock
    private ShareLinkMapper shareLinkMapper;
    @Mock
    private WorkspaceFileMapper workspaceFileMapper;
    @Mock
    private WorkspaceMapper workspaceMapper;
    @Mock
    private WorkspaceMemberMapper workspaceMemberMapper;
    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private UsageService usageService;
    @Mock
    private EntitlementResolver entitlementResolver;
    @Mock
    private ConfigService configService;
    @Mock
    private AuditService auditService;
    @Mock
    private UserMapper userMapper;
    @Mock
    private com.bond.transfer.acl.WorkspaceAclService workspaceAclService;

    @InjectMocks
    private ShareLinkService service;

    @BeforeEach
    void setUp() {
        when(configService.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(configService.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(configService.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        when(usageService.meterAndThrottleDownload(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(objectStorage.getObject(anyString())).thenReturn(new ByteArrayInputStream(new byte[16]));
        when(objectStorage.getObject(anyString(), anyLong(), anyLong()))
                .thenReturn(new ByteArrayInputStream(new byte[16]));
        when(shareLinkMapper.insert(any(ShareLink.class))).thenAnswer(inv -> {
            ShareLink link = inv.getArgument(0);
            link.setId(1L);
            return 1;
        });
        User account = new User();
        account.setId(USER_ID);
        account.setNickname("tester");
        when(userMapper.selectById(anyLong())).thenReturn(account);
    }

    // ── 创建 ──

    @Test
    @DisplayName("个人空间文件：创建成功，默认档位有效期 7 天，URL 指向 /s/{token}")
    void createPersonalLink() {
        stubPersonalFile();
        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan("free", 5L * 1024 * 1024 * 1024, 7, 5, 30));
        when(shareLinkMapper.countActiveByCreator(USER_ID)).thenReturn(0);

        ShareLinkView view = service.create(USER_ID, new CreateShareRequest(FILE_ID, null, false, null, null, null));

        assertNotNull(view.token());
        assertEquals(43, view.token().length());
        assertTrue(view.url().endsWith("/s/" + view.token()));
        assertTrue(view.url().startsWith("http"));
        assertFalse(view.requiresCode());
        assertNotNull(view.expiresAt());
        assertTrue(view.expiresAt().isBefore(OffsetDateTime.now().plusDays(7).plusMinutes(1)));
        assertEquals(0, view.maxDownloads());
    }

    @Test
    @DisplayName("档位活跃链接上限：达到上限创建被拒（403）")
    void createRejectsWhenActiveLinkLimitReached() {
        stubPersonalFile();
        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan("free", 5L * 1024 * 1024 * 1024, 7, 5, 30));
        when(shareLinkMapper.countActiveByCreator(USER_ID)).thenReturn(5);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(USER_ID, new CreateShareRequest(FILE_ID, null, false, null, null, null)));
        assertEquals(403, e.getCode());
    }

    @Test
    @DisplayName("别人的个人空间文件：统一 404（不泄露存在性）")
    void createRejectsThirdPartyPersonalFile() {
        WorkspaceFile file = file();
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(file);
        Workspace ws = workspace("personal", OTHER_USER);
        ws.setId(PERSONAL_ID);
        when(workspaceMapper.selectById(PERSONAL_ID)).thenReturn(ws);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(USER_ID, new CreateShareRequest(FILE_ID, null, false, null, null, null)));
        assertEquals(404, e.getCode());
        verify(shareLinkMapper, never()).insert(any(ShareLink.class));
    }

    @Test
    @DisplayName("团队空间非成员：创建被拒（403）")
    void createRejectsTeamNonMember() {
        WorkspaceFile file = file();
        file.setWorkspaceId(TEAM_ID);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(file);
        Workspace ws = workspace("team", OTHER_USER);
        ws.setId(TEAM_ID);
        when(workspaceMapper.selectById(TEAM_ID)).thenReturn(ws);
        when(workspaceMemberMapper.selectOne(any())).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(USER_ID, new CreateShareRequest(FILE_ID, null, false, null, null, null)));
        assertEquals(403, e.getCode());
    }

    @Test
    @DisplayName("提取码：库中只存 64 位 hex 摘要，不存明文")
    void createWithCodeStoresHashOnly() {
        stubPersonalFile();
        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan("free", 5L * 1024 * 1024 * 1024, 7, 5, 30));
        when(shareLinkMapper.countActiveByCreator(USER_ID)).thenReturn(0);

        // 用 8 位提取码：64 位 hex 摘要随机出现该子串的概率可忽略，避免 flaky。
        String code = "91827364";
        service.create(USER_ID, new CreateShareRequest(FILE_ID, null, true, code, null, null));

        ArgumentCaptor<ShareLink> captor = ArgumentCaptor.forClass(ShareLink.class);
        verify(shareLinkMapper).insert(captor.capture());
        ShareLink saved = captor.getValue();
        assertTrue(saved.getRequiresCode());
        assertNotNull(saved.getCodeHash());
        assertEquals(64, saved.getCodeHash().length());
        assertTrue(saved.getCodeHash().matches("[0-9a-f]{64}"));
        assertFalse(saved.getCodeHash().contains(code));
    }

    @Test
    @DisplayName("永久链接：缺省下载次数 100（付费档 cap 1000 不收敛）")
    void createPermanentLinkDefaultsTo100MaxDownloads() {
        stubPersonalFile();
        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan("team", 100L * 1024 * 1024 * 1024, 0, 50, 1000));
        when(shareLinkMapper.countActiveByCreator(USER_ID)).thenReturn(0);

        ShareLinkView view = service.create(USER_ID, new CreateShareRequest(FILE_ID, null, false, null, 0, null));

        assertNull(view.expiresAt());
        assertEquals(100, view.maxDownloads());
    }

    @Test
    @DisplayName("免费档也允许永久链接（task-102）：缺省 100 按档位上限 30 收敛")
    void createPermanentLinkForFreePlan() {
        stubPersonalFile();
        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan("free", 5L * 1024 * 1024 * 1024, 7, 5, 30));
        when(shareLinkMapper.countActiveByCreator(USER_ID)).thenReturn(0);

        ShareLinkView view = service.create(USER_ID, new CreateShareRequest(FILE_ID, null, false, null, 0, null));

        assertNull(view.expiresAt());
        assertEquals(30, view.maxDownloads());
    }

    @Test
    @DisplayName("档位下载次数不限（cap=0）时永久链接缺省 100 不被收敛")
    void createPermanentLinkUnlimitedCapKeeps100() {
        stubPersonalFile();
        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan("plus", 50L * 1024 * 1024 * 1024, 30, 20, 0));
        when(shareLinkMapper.countActiveByCreator(USER_ID)).thenReturn(0);

        ShareLinkView view = service.create(USER_ID, new CreateShareRequest(FILE_ID, null, false, null, 0, null));

        assertNull(view.expiresAt());
        assertEquals(100, view.maxDownloads());
    }

    // ── 提取码校验 ──

    @Test
    @DisplayName("提取码错误：+1 失败并返回 403")
    void verifyWrongCodeRecordsFailure() {
        ShareLink link = createCodeProtectedLink();
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.verify(link.getToken(), "0000", "1.2.3.4"));
        assertEquals(403, e.getCode());
        verify(shareLinkMapper).recordCodeFailure(eq(link.getId()), eq(5), eq(10));
    }

    @Test
    @DisplayName("提取码正确：清零失败计数")
    void verifyCorrectCodeResetsFailures() {
        ShareLink link = createCodeProtectedLink();
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);

        service.verify(link.getToken(), "1234", "1.2.3.4");

        verify(shareLinkMapper).resetCodeFailures(link.getId());
    }

    // ── 匿名访问状态码 ──

    @Test
    @DisplayName("已撤销链接：落地页 410")
    void revokedLinkLandingReturns410() {
        ShareLink link = baseLink();
        link.setStatus(1);
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);

        BusinessException e = assertThrows(BusinessException.class, () -> service.landingHtml(link.getToken()));
        assertEquals(410, e.getCode());
    }

    @Test
    @DisplayName("过期链接：410")
    void expiredLinkReturns410() {
        ShareLink link = baseLink();
        link.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);

        BusinessException e = assertThrows(BusinessException.class, () -> service.landingHtml(link.getToken()));
        assertEquals(410, e.getCode());
    }

    @Test
    @DisplayName("未知 token：404；畸形 token 直接 404（不查库）")
    void unknownTokenReturns404() {
        when(shareLinkMapper.selectByToken(anyString())).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.landingHtml(ShareCode.newToken())).getCode());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.landingHtml("short-token")).getCode());
        verify(shareLinkMapper, never()).selectByToken("short-token");
    }

    @Test
    @DisplayName("落地页：包含文件名/大小/下载入口，且不包含 token 以外的敏感信息")
    void landingHtmlContainsFileInfo() {
        ShareLink link = baseLink();
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(file());

        String html = service.landingHtml(link.getToken());

        assertTrue(html.contains("hello.txt"));
        assertTrue(html.contains("/s/" + link.getToken() + "/download"));
        assertFalse(html.contains("code_hash"));
        // task-163：新版落地页（视觉优化）—— BOM 标题带文件名，移动端 viewport 带刘海适配，深浅色跟随系统
        assertTrue(html.contains("<title>Bond 文件分享 - hello.txt</title>"));
        assertTrue(html.contains("viewport-fit=cover"));
        assertTrue(html.contains("prefers-color-scheme:dark"));
        // 页内展示大小/有效期/剩余次数
        assertTrue(html.contains("有效期至"));
        assertTrue(html.contains("下载次数不限"));
        // 品牌色（暖橙）
        assertTrue(html.contains("#E57153"));
        assertTrue(html.contains("#D46A4C"));
        // 站内静态资源，不引第三方 CDN、不内联 base64
        assertTrue(html.contains("<link rel=\"icon\" type=\"image/svg+xml\" href=\"/favicon.svg\" />"));
        assertTrue(html.contains("<meta name=\"speculationrules\"") || !html.contains("<script src="));
        assertTrue(html.contains("<meta name=\"robots\" content=\"noindex,nofollow\" />"));
        assertFalse(html.contains("<script src="));
        assertFalse(html.contains("data:image/svg+xml;base64,"));
    }

    @Test
    @DisplayName("落地页内联 JS 容错：4xx/5xx 空 body 或 HTML 提示「服务暂时不可用（HTTP xxx）」，只有 fetch 失败才说网络不可达")
    void landingPageVerifyJsDistinguishesHttpErrorFromNetworkError() {
        ShareLink link = baseLink();
        link.setRequiresCode(true);
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(file());

        String html = service.landingHtml(link.getToken());

        // 仍走 POST /s/{token}/verify，成功后才跳下载（原有逻辑不变）
        assertTrue(html.contains("/s/" + link.getToken() + "/verify"));
        assertTrue(html.contains("/s/" + link.getToken() + "/download?code="));
        // 先看 content-type 再解析 JSON
        assertTrue(html.contains("content-type"));
        assertTrue(html.contains("indexOf('json')"));
        // 不可解析的错误响应按 HTTP 状态报，而不是“网络异常”
        assertTrue(html.contains("服务暂时不可用（HTTP "));
        // 只有 fetch reject 才提示网络不可达
        assertTrue(html.contains("网络不可达，请检查网络后重试"));
        assertFalse(html.contains("网络异常，请稍后重试"));
    }

    // ── 下载与次数/计费语义 ──

    @Test
    @DisplayName("完整下载：次数 +1，按空间所有者计费（meterAndThrottleDownload 归属正确）")
    void fullDownloadIncrementsCountAndBillsOwner() {
        ShareLink link = baseLink();
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(file());
        when(shareLinkMapper.incrementDownloadCount(link.getId())).thenReturn(1);

        ShareLinkService.ShareDownload download = service.prepareDownload(link.getToken(), null, null, "1.2.3.4");

        assertEquals(FILE_SIZE, download.totalSize());
        assertFalse(download.partial());
        verify(shareLinkMapper).incrementDownloadCount(link.getId());
        verify(usageService).meterAndThrottleDownload(eq(USER_ID), any(InputStream.class));
    }

    @Test
    @DisplayName("Range 续传（offset>0）：不重复计次，但计费仍按实际字节（同一流）")
    void rangeDownloadDoesNotIncrementCount() {
        ShareLink link = baseLink();
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(file());

        ShareLinkService.ShareDownload download =
                service.prepareDownload(link.getToken(), null, "bytes=100-199", "1.2.3.4");

        assertTrue(download.partial());
        assertEquals(100L, download.offset());
        assertEquals(100L, download.length());
        verify(shareLinkMapper, never()).incrementDownloadCount(anyLong());
        verify(usageService).meterAndThrottleDownload(eq(USER_ID), any(InputStream.class));
    }

    @Test
    @DisplayName("次数用尽（原子 SQL 返回 0）：410")
    void exhaustedDownloadReturns410() {
        ShareLink link = baseLink();
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(file());
        when(shareLinkMapper.incrementDownloadCount(link.getId())).thenReturn(0);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.prepareDownload(link.getToken(), null, null, "1.2.3.4"));
        assertEquals(410, e.getCode());
    }

    @Test
    @DisplayName("Range 越界：抛 UnsatisfiableRangeException（controller 映射 416）")
    void unsatisfiableRangeThrows() {
        ShareLink link = baseLink();
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(file());

        assertThrows(PreviewService.UnsatisfiableRangeException.class,
                () -> service.prepareDownload(link.getToken(), null, "bytes=5000-", "1.2.3.4"));
    }

    // ── 撤销 ──

    @Test
    @DisplayName("撤销：创建者可撤销；非成员被拒（403）")
    void revokeAuthorization() {
        ShareLink link = baseLink();
        when(shareLinkMapper.selectById(1L)).thenReturn(link);
        service.revoke(USER_ID, 1L);
        verify(shareLinkMapper).revokeById(1L);

        when(workspaceMapper.selectById(TEAM_ID)).thenReturn(workspace("team", OTHER_USER));
        when(workspaceMemberMapper.selectOne(any())).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () -> service.revoke(99L, 1L));
        assertEquals(403, e.getCode());
    }

    @Test
    @DisplayName("列表：不返回 code_hash（避免摘要外泄被离线爆破）")
    void listHidesCodeHash() {
        ShareLink link = baseLink();
        when(shareLinkMapper.selectList(any())).thenReturn(List.of(link));
        when(workspaceFileMapper.selectBatchIds(any())).thenReturn(List.of(file()));

        List<ShareLinkView> views = service.list(USER_ID);

        assertEquals(1, views.size());
        assertTrue(views.get(0).url().endsWith("/s/" + link.getToken()));
    }

    @Test
    @DisplayName("列表：workspaceId 过滤生效（避免控制器收下参数却静默忽略）")
    void listFiltersByWorkspaceId() {
        ShareLink personal = baseLink();
        personal.setId(1L);
        personal.setWorkspaceId(PERSONAL_ID);
        ShareLink team = baseLink();
        team.setId(2L);
        team.setToken(ShareCode.newToken());
        team.setWorkspaceId(TEAM_ID);
        when(shareLinkMapper.selectList(any())).thenReturn(List.of(personal, team));
        when(workspaceFileMapper.selectBatchIds(any())).thenReturn(List.of(file()));

        assertEquals(1, service.list(USER_ID, TEAM_ID).size());
        assertEquals(2, service.list(USER_ID, null).size());
        assertEquals(0, service.list(USER_ID, 999L).size());
    }

    // ── 回收站（V6 软删）：对外等同不存在 ──

    @Test
    @DisplayName("回收站中的文件：创建分享 404（先恢复再分享）")
    void createRejectsTrashedFile() {
        WorkspaceFile trashed = file();
        trashed.setDeletedAt(OffsetDateTime.now());
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(trashed);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(USER_ID, new CreateShareRequest(FILE_ID, null, false, null, null, null)));
        assertEquals(404, e.getCode());
        verify(shareLinkMapper, never()).insert(any(ShareLink.class));
    }

    @Test
    @DisplayName("文件进回收站：落地页 410（链接行保留，彻底删除时才连带删）")
    void landingHtmlReturns410ForTrashedFile() {
        ShareLink link = baseLink();
        WorkspaceFile trashed = file();
        trashed.setDeletedAt(OffsetDateTime.now());
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(trashed);

        BusinessException e = assertThrows(BusinessException.class, () -> service.landingHtml(link.getToken()));
        assertEquals(410, e.getCode());
    }

    @Test
    @DisplayName("文件进回收站：下载入口 410（不消耗下载次数）")
    void prepareDownloadReturns410ForTrashedFile() {
        ShareLink link = baseLink();
        WorkspaceFile trashed = file();
        trashed.setDeletedAt(OffsetDateTime.now());
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(trashed);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.prepareDownload(link.getToken(), null, null, "1.2.3.4"));
        assertEquals(410, e.getCode());
        verify(shareLinkMapper, never()).incrementDownloadCount(anyLong());
    }

    // ── 编辑链接 / 失效落地页（task-163） ──

    @Test
    @DisplayName("PATCH：空请求 400、不存在 404、非创建者 403、已撤销 400")
    void patchValidatesRequestAndOwnership() {
        BusinessException empty = assertThrows(BusinessException.class,
                () -> service.patch(USER_ID, 1L, new PatchShareRequest(null, null, null), "1.2.3.4"));
        assertEquals(400, empty.getCode());

        when(shareLinkMapper.selectById(1L)).thenReturn(null);
        BusinessException missing = assertThrows(BusinessException.class,
                () -> service.patch(USER_ID, 1L, new PatchShareRequest(NullNode.instance, null, null), "1.2.3.4"));
        assertEquals(404, missing.getCode());

        ShareLink foreign = baseLink();
        foreign.setCreatorId(OTHER_USER);
        when(shareLinkMapper.selectById(1L)).thenReturn(foreign);
        BusinessException forbidden = assertThrows(BusinessException.class,
                () -> service.patch(USER_ID, 1L, new PatchShareRequest(NullNode.instance, null, null), "1.2.3.4"));
        assertEquals(403, forbidden.getCode());

        ShareLink revoked = baseLink();
        revoked.setStatus(1);
        when(shareLinkMapper.selectById(1L)).thenReturn(revoked);
        BusinessException revokedError = assertThrows(BusinessException.class,
                () -> service.patch(USER_ID, 1L, new PatchShareRequest(NullNode.instance, null, null), "1.2.3.4"));
        assertEquals(400, revokedError.getCode());
        verify(shareLinkMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("PATCH 提取码三态：null=取消、4-8 位=替换、非法=400；每次写入都记审计")
    void patchExtractCodeThreeState() {
        ShareLink link = baseLink();
        link.setRequiresCode(true);
        link.setCodeHash("old-hash");
        when(shareLinkMapper.selectById(1L)).thenReturn(link);
        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan("free", 0L, 7, 5, 30));

        ShareLinkView cancelled = service.patch(USER_ID, 1L,
                new PatchShareRequest(NullNode.instance, null, null), "1.2.3.4");
        assertFalse(cancelled.requiresCode());
        assertNull(link.getCodeHash());

        ShareLinkView replaced = service.patch(USER_ID, 1L,
                new PatchShareRequest(TextNode.valueOf("8A9B"), null, null), "1.2.3.4");
        assertTrue(replaced.requiresCode());
        assertNotNull(link.getCodeHash());
        assertNotEquals("old-hash", link.getCodeHash());

        BusinessException badCode = assertThrows(BusinessException.class,
                () -> service.patch(USER_ID, 1L, new PatchShareRequest(TextNode.valueOf("!!"), null, null), "1.2.3.4"));
        assertEquals(400, badCode.getCode());

        verify(auditService, times(2)).record(eq(USER_ID), eq("tester"), eq("share.update"),
                eq("share"), eq(1L), anyString(), eq("1.2.3.4"));
    }

    @Test
    @DisplayName("PATCH 有效期：免费档不许永久/超 7 天；付费档可永久；过期链接改期复位 status=0")
    void patchExpiryRules() {
        ShareLink link = baseLink();
        when(shareLinkMapper.selectById(1L)).thenReturn(link);
        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan("free", 0L, 7, 5, 30));

        BusinessException permanentFree = assertThrows(BusinessException.class,
                () -> service.patch(USER_ID, 1L, new PatchShareRequest(null, NullNode.instance, null), "1.2.3.4"));
        assertEquals(400, permanentFree.getCode());

        long tooFar = OffsetDateTime.now().plusDays(30).toInstant().toEpochMilli();
        BusinessException beyond = assertThrows(BusinessException.class,
                () -> service.patch(USER_ID, 1L, new PatchShareRequest(null, LongNode.valueOf(tooFar), null), "1.2.3.4"));
        assertEquals(400, beyond.getCode());

        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan("plus", 0L, 30, 20, 200));
        link.setStatus(2);
        link.setExpiresAt(OffsetDateTime.now().minusDays(1));
        ShareLinkView permanent = service.patch(USER_ID, 1L,
                new PatchShareRequest(null, NullNode.instance, null), "1.2.3.4");
        assertNull(permanent.expiresAt());
        assertEquals(0, link.getStatus());
        assertEquals(0, permanent.status());
    }

    @Test
    @DisplayName("PATCH 下载次数：null=不限、低于已下载 400、超过档位上限 400、合法值写入且不动其它字段")
    void patchMaxDownloadsRules() {
        ShareLink link = baseLink();
        link.setRequiresCode(true);
        link.setCodeHash("keep");
        link.setDownloadCount(3);
        when(shareLinkMapper.selectById(1L)).thenReturn(link);
        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan("free", 0L, 7, 5, 30));

        BusinessException below = assertThrows(BusinessException.class,
                () -> service.patch(USER_ID, 1L, new PatchShareRequest(null, null, LongNode.valueOf(2)), "1.2.3.4"));
        assertEquals(400, below.getCode());

        BusinessException overCap = assertThrows(BusinessException.class,
                () -> service.patch(USER_ID, 1L, new PatchShareRequest(null, null, LongNode.valueOf(99)), "1.2.3.4"));
        assertEquals(400, overCap.getCode());

        ShareLinkView view = service.patch(USER_ID, 1L,
                new PatchShareRequest(null, null, LongNode.valueOf(10)), "1.2.3.4");
        assertEquals(10, view.maxDownloads());
        assertTrue(link.getRequiresCode());
        assertEquals("keep", link.getCodeHash());

        ShareLinkView unlimited = service.patch(USER_ID, 1L,
                new PatchShareRequest(null, null, NullNode.instance), "1.2.3.4");
        assertEquals(0, unlimited.maxDownloads());
    }

    @Test
    @DisplayName("失效落地页：撤销/过期/次数用尽/不存在都给中文 HTML，不泄露文件名，状态码 404/410")
    void invalidLandingHtmlDistinguishesReasons() {
        String revoked = service.invalidLandingHtml(new BusinessException(410, "分享链接已被撤销"));
        assertTrue(revoked.contains("分享已被取消"));
        assertTrue(revoked.contains("<!DOCTYPE html>"));
        assertFalse(revoked.contains("hello.txt"));

        String expired = service.invalidLandingHtml(new BusinessException(410, "分享链接已过期"));
        assertTrue(expired.contains("分享已过期"));

        String exhausted = service.invalidLandingHtml(new BusinessException(410, "下载次数已用尽"));
        assertTrue(exhausted.contains("下载次数已用完"));

        String missing = service.invalidLandingHtml(new BusinessException(404, "分享链接不存在"));
        assertTrue(missing.contains("分享链接不存在"));

        assertEquals(404, service.landingStatus(new BusinessException(404, "x")));
        assertEquals(410, service.landingStatus(new BusinessException(410, "x")));
        assertEquals(410, service.landingStatus(null));
    }

    // ── task-183：文件夹分享（ZIP） ──

    @Test
    @DisplayName("文件夹分享：允许为目录创建链接，视图 isDirectory=true（不再 400「目录不支持分享」）")
    void createFolderShare() {
        WorkspaceFile dir = folder();
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(dir);
        when(workspaceMapper.selectById(PERSONAL_ID)).thenReturn(workspace("personal", USER_ID));
        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan("free", 5L * 1024 * 1024 * 1024, 7, 5, 30));
        when(shareLinkMapper.countActiveByCreator(USER_ID)).thenReturn(0);

        ShareLinkView view = service.create(USER_ID, new CreateShareRequest(FILE_ID, null, false, null, null, null));

        assertTrue(view.isDirectory());
        assertEquals("docs", view.fileName());
    }

    @Test
    @DisplayName("文件夹落地页：展示文件夹名 + 首层子项（目录带 /）+「下载全部（ZIP）」，不泄露成员信息")
    void folderLandingListsChildren() {
        ShareLink link = baseLink();
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(folder());
        WorkspaceFile child1 = file();
        child1.setId(501L);
        child1.setFileName("a.txt");
        child1.setParentPath("/docs");
        WorkspaceFile sub = folder();
        sub.setId(502L);
        sub.setFileName("sub");
        sub.setParentPath("/docs");
        WorkspaceFile child2 = file();
        child2.setId(503L);
        child2.setFileName("pic.png");
        child2.setParentPath("/docs");
        child2.setFileSize(2048L);
        when(workspaceFileMapper.selectList(any())).thenReturn(List.of(sub, child1, child2));

        String html = service.landingHtml(link.getToken());

        assertTrue(html.contains("Bond 文件夹分享"));
        assertTrue(html.contains("docs/"));
        assertTrue(html.contains("a.txt"));
        assertTrue(html.contains("pic.png"));
        assertTrue(html.contains("sub/"));
        assertTrue(html.contains("首层 3 项"));
        assertTrue(html.contains("下载全部（ZIP）"));
        assertTrue(html.contains("/s/" + link.getToken() + "/download"));
        assertFalse(html.contains("501"));
        assertFalse(html.contains("uploadedBy"));
    }

    @Test
    @DisplayName("文件夹落地页：空目录给明确提示，ZIP 入口仍在")
    void emptyFolderLanding() {
        ShareLink link = baseLink();
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(folder());
        when(workspaceFileMapper.selectList(any())).thenReturn(List.of());

        String html = service.landingHtml(link.getToken());

        assertTrue(html.contains("这个文件夹还是空的"));
        assertTrue(html.contains("下载全部（ZIP）"));
    }

    @Test
    @DisplayName("文件夹已进回收站：落地页 410（与单文件同语义）")
    void folderLandingReturns410WhenTrashed() {
        ShareLink link = baseLink();
        WorkspaceFile trashed = folder();
        trashed.setDeletedAt(OffsetDateTime.now());
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(trashed);

        BusinessException e = assertThrows(BusinessException.class, () -> service.landingHtml(link.getToken()));
        assertEquals(410, e.getCode());
    }

    @Test
    @DisplayName("ZIP 下载准备：递归列出目录下全部文件，相对路径不带外层目录名，目录条目以 / 结尾，计次 1 次")
    void prepareZipDownloadCollectsSubtree() {
        ShareLink link = baseLink();
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(folder());
        when(shareLinkMapper.incrementDownloadCount(link.getId())).thenReturn(1);
        when(workspaceMapper.selectById(PERSONAL_ID)).thenReturn(workspace("personal", USER_ID));

        WorkspaceFile direct = file();
        direct.setId(501L);
        direct.setFileName("a.txt");
        direct.setParentPath("/docs");
        direct.setMinioPath("k/a");
        WorkspaceFile nested = file();
        nested.setId(502L);
        nested.setFileName("b.bin");
        nested.setParentPath("/docs/sub");
        nested.setMinioPath("k/b");
        nested.setFileSize(9L);
        WorkspaceFile subDir = folder();
        subDir.setId(503L);
        subDir.setFileName("sub");
        subDir.setParentPath("/docs");
        // 越界行（逻辑上不属于该目录）必须被 FileTree.isUnder 二次过滤丢弃
        WorkspaceFile outsider = file();
        outsider.setId(504L);
        outsider.setFileName("evil.txt");
        outsider.setParentPath("/docs2");
        when(workspaceFileMapper.selectList(any())).thenReturn(List.of(direct, nested, subDir, outsider));

        ShareLinkService.ShareZipDownload zip = service.prepareZipDownload(link.getToken(), null, "1.2.3.4");

        assertEquals("docs", zip.folderName());
        assertEquals(USER_ID, zip.billingUserId());
        assertEquals(List.of("a.txt", "sub/", "sub/b.bin"),
                zip.entries().stream().map(ShareLinkService.ShareZipEntry::path).toList());
        assertNull(zip.entries().get(1).minioPath(), "目录条目没有对象键");
        verify(shareLinkMapper).incrementDownloadCount(link.getId());
    }

    @Test
    @DisplayName("ZIP 下载准备：文件夹已删除 410 且不消耗次数")
    void prepareZipDownloadRejectsMissingFolder() {
        ShareLink link = baseLink();
        WorkspaceFile trashed = folder();
        trashed.setDeletedAt(OffsetDateTime.now());
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(trashed);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.prepareZipDownload(link.getToken(), null, "1.2.3.4"));
        assertEquals(410, e.getCode());
        verify(shareLinkMapper, never()).incrementDownloadCount(anyLong());
    }

    @Test
    @DisplayName("ZIP 条目超过上限：400（拒绝一次拉爆整棵大树）")
    void prepareZipDownloadRejectsTooManyEntries() {
        ShareLink link = baseLink();
        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(folder());
        when(shareLinkMapper.incrementDownloadCount(link.getId())).thenReturn(1);
        List<WorkspaceFile> many = new java.util.ArrayList<>();
        for (int i = 0; i <= ShareLinkService.MAX_ZIP_ENTRIES; i++) {
            WorkspaceFile f = file();
            f.setId(10_000L + i);
            f.setFileName("f" + i + ".bin");
            f.setParentPath("/docs");
            many.add(f);
        }
        when(workspaceFileMapper.selectList(any())).thenReturn(many);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.prepareZipDownload(link.getToken(), null, "1.2.3.4"));
        assertEquals(400, e.getCode());
    }

    @Test
    @DisplayName("ZIP 写流：生成可解压的 zip，内容来自对象存储，逐文件计量（关闭计量流时才累加）")
    void writeZipStreamsEntriesAndMetersEachFile() throws Exception {
        when(objectStorage.getObject("k/a")).thenReturn(new ByteArrayInputStream("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(objectStorage.getObject("k/b")).thenReturn(new ByteArrayInputStream("world".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ShareLinkService.ShareZipDownload zip = new ShareLinkService.ShareZipDownload("docs", List.of(
                new ShareLinkService.ShareZipEntry("a.txt", 5L, "k/a"),
                new ShareLinkService.ShareZipEntry("sub/", 0L, null),
                new ShareLinkService.ShareZipEntry("sub/b.bin", 5L, "k/b"),
                // 对象在打包时被删（getObject 抛异常）：只跳过该条，不中断整包
                new ShareLinkService.ShareZipEntry("gone.txt", 1L, "k/gone")), USER_ID);
        when(objectStorage.getObject("k/gone")).thenThrow(new IllegalStateException("object not found"));

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        service.writeZip(zip, out);

        java.util.Map<String, String> contents = new java.util.HashMap<>();
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new ByteArrayInputStream(out.toByteArray()), java.nio.charset.StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    contents.put(entry.getName(), new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    contents.put(entry.getName(), "");
                }
            }
        }
        assertEquals("hello", contents.get("a.txt"));
        assertEquals("world", contents.get("sub/b.bin"));
        assertEquals("", contents.get("sub/"));
        assertFalse(contents.containsKey("gone.txt"));
        // 每个真实文件各自包一层计量流（close 时按实际读出的字节落账）
        verify(usageService, times(2)).meterAndThrottleDownload(eq(USER_ID), any(InputStream.class));
    }

    @Test
    @DisplayName("下载规划：目录链接走 ZIP、文件链接走单文件流（互斥）")
    void planDownloadRoutesByTargetType() {
        ShareLink link = baseLink();
        when(shareLinkMapper.incrementDownloadCount(link.getId())).thenReturn(1);
        when(workspaceMapper.selectById(PERSONAL_ID)).thenReturn(workspace("personal", USER_ID));

        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(folder());
        when(workspaceFileMapper.selectList(any())).thenReturn(List.of());
        ShareLinkService.ShareDownloadPlan folderPlan = service.planDownload(link.getToken(), null, null, "1.2.3.4");
        assertNull(folderPlan.file());
        assertNotNull(folderPlan.zip());

        when(shareLinkMapper.selectByToken(link.getToken())).thenReturn(link);
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(file());
        ShareLinkService.ShareDownloadPlan filePlan = service.planDownload(link.getToken(), null, null, "1.2.3.4");
        assertNotNull(filePlan.file());
        assertNull(filePlan.zip());
    }

    // ── helpers ──

    private void stubPersonalFile() {
        when(workspaceFileMapper.selectById(FILE_ID)).thenReturn(file());
        when(workspaceMapper.selectById(PERSONAL_ID)).thenReturn(workspace("personal", USER_ID));
    }

    private WorkspaceFile file() {
        WorkspaceFile file = new WorkspaceFile();
        file.setId(FILE_ID);
        file.setWorkspaceId(PERSONAL_ID);
        file.setFileName("hello.txt");
        file.setFileSize(FILE_SIZE);
        file.setMinioPath("uploads/hello.txt");
        file.setContentType("text/plain");
        file.setIsDirectory(false);
        return file;
    }

    /** task-183：文件夹行（FileTree.childBase = "/docs"）。 */
    private WorkspaceFile folder() {
        WorkspaceFile dir = new WorkspaceFile();
        dir.setId(FILE_ID);
        dir.setWorkspaceId(PERSONAL_ID);
        dir.setFileName("docs");
        dir.setFileSize(0L);
        dir.setMinioPath("");
        dir.setContentType("httpd/unix-directory");
        dir.setParentPath("/");
        dir.setIsDirectory(true);
        return dir;
    }

    private Workspace workspace(String type, Long ownerId) {
        Workspace workspace = new Workspace();
        workspace.setId(PERSONAL_ID);
        workspace.setType(type);
        workspace.setOwnerId(ownerId);
        workspace.setName("ws");
        return workspace;
    }

    /** 用服务自身创建一次带提取码的链接，拿到与 pepper 一致的摘要（避免测试里复制 pepper）。 */
    private ShareLink createCodeProtectedLink() {
        stubPersonalFile();
        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan("free", 5L * 1024 * 1024 * 1024, 7, 5, 30));
        when(shareLinkMapper.countActiveByCreator(USER_ID)).thenReturn(0);
        service.create(USER_ID, new CreateShareRequest(FILE_ID, null, true, "1234", null, null));
        ArgumentCaptor<ShareLink> captor = ArgumentCaptor.forClass(ShareLink.class);
        verify(shareLinkMapper).insert(captor.capture());
        return captor.getValue();
    }

    private ShareLink baseLink() {
        ShareLink link = new ShareLink();
        link.setId(1L);
        link.setToken(ShareCode.newToken());
        link.setFileId(FILE_ID);
        link.setWorkspaceId(PERSONAL_ID);
        link.setCreatorId(USER_ID);
        link.setRequiresCode(false);
        link.setMaxDownloads(0);
        link.setDownloadCount(0);
        link.setStatus(0);
        link.setExpiresAt(OffsetDateTime.now().plusDays(7));
        link.setCreatedAt(OffsetDateTime.now());
        return link;
    }

    /** 12 参 PlanLimits：shareExpiresDays / graceDays / shareMaxDownloads / shareMaxLinks / trashRetentionDays。 */
    private Entitlements plan(String code, long storage, int shareExpiresDays, int shareMaxLinks, int shareMaxDownloads) {
        PlanLimits limits = new PlanLimits(
                10L * 1024 * 1024 * 1024, storage, 2L * 1024 * 1024 * 1024,
                2, 1, 3, 0L,
                shareExpiresDays, 3, shareMaxDownloads, shareMaxLinks, 30);
        return new Entitlements(code, limits, null);
    }
}
