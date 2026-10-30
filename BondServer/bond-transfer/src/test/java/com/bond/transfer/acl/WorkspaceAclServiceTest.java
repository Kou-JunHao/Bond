package com.bond.transfer.acl;

import com.bond.common.entity.WorkspaceAcl;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.acl.WorkspaceAclDtos.Grant;
import com.bond.transfer.acl.WorkspaceAclDtos.PutRequest;
import com.bond.transfer.acl.WorkspaceAclDtos.View;
import com.bond.transfer.mapper.WorkspaceAclMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.service.AuditService;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-183B：工作区文件级 ACL —— 路径判定边界 / 强制点 / 授权管理校验。
 *
 * <p>无 DB、无 Spring：mapper 与审计都是 mock，重点是「谁能看到/改到什么」的业务规则。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkspaceAclServiceTest {

    private static final Long WS = 100L;
    private static final Long USER = 7L;
    private static final Long OWNER = 1L;
    private static final Long ADMIN = 2L;
    private static final Long TARGET = 9L;

    @Mock
    private WorkspaceAclMapper aclMapper;
    @Mock
    private WorkspaceMemberMapper memberMapper;
    @Mock
    private WorkspaceFileMapper fileMapper;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private WorkspaceAclService service;

    @BeforeEach
    void setUp() {
        when(aclMapper.insert(any(WorkspaceAcl.class))).thenReturn(1);
        when(memberMapper.updateById(any(WorkspaceMember.class))).thenReturn(1);
    }

    // ── 范围解析 ──

    @Test
    @DisplayName("owner/admin 永远不受限（即使 access_mode=restricted），也不会去查授权表")
    void privilegedMembersAreAlwaysUnrestricted() {
        when(memberMapper.selectOne(any())).thenReturn(member(0, "restricted"));
        assertFalse(service.resolve(WS, OWNER).isRestricted());

        when(memberMapper.selectOne(any())).thenReturn(member(1, "restricted"));
        assertFalse(service.resolve(WS, ADMIN).isRestricted());
        verify(aclMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("普通成员 access_mode=all（默认）不受限 —— 不破坏现有成员体验")
    void defaultAllModeIsUnrestricted() {
        when(memberMapper.selectOne(any())).thenReturn(member(2, "all"));
        WorkspaceAccess access = service.resolve(WS, USER);
        assertFalse(access.isRestricted());
        assertTrue(access.canReadDirectory("/任意/目录"));
        assertTrue(access.canWriteFile(1L, "/任意/文件.txt"));
    }

    @Test
    @DisplayName("目录授权前缀按路径段匹配：/a 覆盖 /a 与 /a/b，但不覆盖 /ab")
    void folderPrefixBoundary() {
        stubRestrictedMember(WorkspaceAclServiceTest::folderGrant);

        WorkspaceAccess access = service.resolve(WS, USER);
        assertTrue(access.isRestricted());
        assertTrue(access.canReadDirectory("/a"));
        assertTrue(access.canReadDirectory("/a/b"));
        assertTrue(access.canReadFile(11L, "/a/b/x.txt"));
        assertFalse(access.canReadDirectory("/ab"));
        assertFalse(access.canReadDirectory("/b"));
        assertFalse(access.canReadFile(12L, "/ab/x.txt"));
        // 只读授权：不能写
        assertFalse(access.canWriteDirectory("/a"));
        assertFalse(access.canWriteFile(11L, "/a/b/x.txt"));
    }

    @Test
    @DisplayName("文件级授权只覆盖该文件，但允许向上遍历（否则无法导航到授权文件）")
    void fileGrantOnlyCoversThatFileButAllowsTraversal() {
        stubRestrictedMember(() -> fileGrant("/docs/a.txt", 11L));

        WorkspaceAccess access = service.resolve(WS, USER);
        assertTrue(access.canReadFile(11L, "/docs/a.txt"));
        assertFalse(access.canReadFile(12L, "/docs/b.txt"));
        assertTrue(access.canReadDirectory("/"));
        assertTrue(access.canReadDirectory("/docs"));
        assertFalse(access.canReadDirectory("/other"));
        assertFalse(access.canWriteFile(11L, "/docs/a.txt"));
    }

    @Test
    @DisplayName("整空间授权（/）覆盖全部路径；can_write 同时允许在子目录写入")
    void rootGrantCoversEverything() {
        stubRestrictedMember(() -> new WorkspaceAcl() {{
            setScopeType(WorkspaceAclService.SCOPE_FOLDER);
            setScopePath("/");
            setCanRead(true);
            setCanWrite(true);
        }});

        WorkspaceAccess access = service.resolve(WS, USER);
        assertTrue(access.canReadDirectory("/x/y"));
        assertTrue(access.canWriteDirectory("/x/y"));
        assertTrue(access.canWriteFile(3L, "/x/y/z.bin"));
    }

    // ── 强制点 ──

    @Test
    @DisplayName("读越权 → 404（不泄露存在性）；写越权 → 403；未授权目录不可上传")
    void enforceReadWrite() {
        stubRestrictedMember(WorkspaceAclServiceTest::folderGrant);

        service.requireReadable(WS, USER, row(21L, "a.txt", "/a", false));
        // 只读授权：可以读 /a 下的文件，但不能在 /a/b 上传/建目录
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.requireWritableDirectory(WS, USER, "/a/b")).getCode());

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.requireReadable(WS, USER, row(22L, "b.txt", "/other", false))).getCode());
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.requireWritable(WS, USER, row(23L, "a.txt", "/a", false))).getCode());
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.requireWritableDirectory(WS, USER, "/other")).getCode());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.requireReadableDirectory(WS, USER, "/other")).getCode());
    }

    @Test
    @DisplayName("分享：restricted 成员不能把「自己只被授权子目录」的父目录整包分享（403），授权目录本身可分享")
    void shareabilityRequiresCoveringFolderGrant() {
        stubRestrictedMember(WorkspaceAclServiceTest::folderGrant);

        // /a 被授权 → 可分享 /a 及其子目录
        service.requireShareable(WS, USER, row(30L, "a", "/", true));
        service.requireShareable(WS, USER, row(31L, "sub", "/a", true));
        // 根目录 / 不在授权范围（只有 /a）→ 403，避免一次导出未授权内容
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.requireShareable(WS, USER, row(32L, "root", "/", true))).getCode());
        // 文件不在授权范围 → 404（与「文件不存在」同口径）
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.requireShareable(WS, USER, row(33L, "x.txt", "/other", false))).getCode());
    }

    // ── 授权管理 ──

    @Test
    @DisplayName("管理端点：非 owner/admin 403；目标为 owner/admin 400；accessMode 非法 400")
    void managementRequiresAdminAndSaneTarget() {
        when(memberMapper.selectOne(any())).thenReturn(member(2, "all"));
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.replaceAcl(USER, WS, TARGET, new PutRequest("all", List.of()), null)).getCode());

        when(memberMapper.selectOne(any())).thenReturn(member(1, "all"), member(1, "restricted"));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.replaceAcl(ADMIN, WS, OWNER, new PutRequest("all", List.of()), null)).getCode());

        when(memberMapper.selectOne(any())).thenReturn(member(1, "all"), member(2, "all"));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.replaceAcl(ADMIN, WS, TARGET, new PutRequest("weird", List.of()), null)).getCode());
    }

    @Test
    @DisplayName("restricted + folder 授权：路径归一化、can_write 强制 can_read、同路径重复授权 OR 合并")
    void replaceRestrictedNormalizesGrants() {
        stubAdminAndTarget();
        when(fileMapper.selectCount(any())).thenReturn(1L);

        View view = service.replaceAcl(ADMIN, WS, TARGET, new PutRequest("restricted", List.of(
                new Grant("folder", "/docs/", null, true, false),
                new Grant("FOLDER", "/docs", null, false, true))), "1.2.3.4");

        assertEquals("restricted", view.accessMode());
        ArgumentCaptor<WorkspaceAcl> inserted = ArgumentCaptor.forClass(WorkspaceAcl.class);
        verify(aclMapper).insert(inserted.capture());
        WorkspaceAcl saved = inserted.getValue();
        assertEquals(WS, saved.getWorkspaceId());
        assertEquals(TARGET, saved.getUserId());
        assertEquals("folder", saved.getScopeType());
        assertEquals("/docs", saved.getScopePath(), "尾斜杠必须被归一化");
        assertTrue(saved.getCanRead(), "can_write 必须同时具备 can_read");
        assertTrue(saved.getCanWrite());
        verify(memberMapper).updateById(any(WorkspaceMember.class));
        verify(auditService).record(eq(ADMIN), any(), eq("workspace.acl.update"), eq("workspace"),
                eq(WS), anyString(), eq("1.2.3.4"));
    }

    @Test
    @DisplayName("folder 授权目录不存在 → 400；file 授权必须存在且属于该工作区")
    void replaceRejectsInvalidScopes() {
        stubAdminAndTarget();
        when(fileMapper.selectCount(any())).thenReturn(0L);
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.replaceAcl(ADMIN, WS, TARGET, new PutRequest("restricted",
                        List.of(new Grant("folder", "/missing", null, true, false))), null)).getCode());

        when(fileMapper.selectCount(any())).thenReturn(1L);
        when(fileMapper.selectById(55L)).thenReturn(null);
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.replaceAcl(ADMIN, WS, TARGET, new PutRequest("restricted",
                        List.of(new Grant("file", "/x", 55L, true, false))), null)).getCode());

        WorkspaceFile foreign = row(56L, "x.txt", "/", false);
        foreign.setWorkspaceId(999L);
        when(fileMapper.selectById(56L)).thenReturn(foreign);
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.replaceAcl(ADMIN, WS, TARGET, new PutRequest("restricted",
                        List.of(new Grant("file", "/x", 56L, true, false))), null)).getCode());
    }

    @Test
    @DisplayName("accessMode=all：清空授权并把成员切回 all；空 grants 也是合法（restricted 但什么也看不到）")
    void replaceAllClearsGrants() {
        stubAdminAndTarget();

        View view = service.replaceAcl(ADMIN, WS, TARGET, new PutRequest("all", List.of()), null);
        assertEquals("all", view.accessMode());
        assertTrue(view.grants().isEmpty());
        verify(aclMapper).delete(any());
        verify(aclMapper, never()).insert(any(WorkspaceAcl.class));

        View empty = service.replaceAcl(ADMIN, WS, TARGET, new PutRequest("restricted", null), null);
        assertEquals("restricted", empty.accessMode());
        assertTrue(empty.grants().isEmpty());
    }

    @Test
    @DisplayName("授权视图：owner/admin 读自己 = all；restricted 成员读自己返回全部授权")
    void views() {
        when(memberMapper.selectOne(any())).thenReturn(member(0, "all"));
        assertEquals("all", service.myAcl(OWNER, WS).accessMode());

        stubRestrictedMember(WorkspaceAclServiceTest::folderGrant);
        View mine = service.myAcl(USER, WS);
        assertEquals("restricted", mine.accessMode());
        assertEquals(1, mine.grants().size());
        assertEquals("/a", mine.grants().get(0).scopePath());
    }

    // ── helpers ──

    private void stubRestrictedMember(java.util.function.Supplier<WorkspaceAcl> grantFactory) {
        when(memberMapper.selectOne(any())).thenReturn(member(2, "restricted"));
        WorkspaceAcl grant = grantFactory.get();
        grant.setWorkspaceId(WS);
        grant.setUserId(USER);
        when(aclMapper.selectList(any())).thenReturn(List.of(grant));
    }

    private void stubAdminAndTarget() {
        // 每次 replaceAcl 依次查两次成员：operator（需 admin/owner）→ target
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(memberMapper.selectOne(any())).thenAnswer(invocation ->
                calls.getAndIncrement() % 2 == 0 ? member(1, "all") : member(2, "all"));
    }

    private static WorkspaceMember member(int role, String mode) {
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(WS);
        member.setUserId(USER);
        member.setRole(role);
        member.setAccessMode(mode);
        return member;
    }

    /** 目录 /a 只读授权（作为 resolve 的返回行）。 */
    private static WorkspaceAcl folderGrant() {
        WorkspaceAcl grant = new WorkspaceAcl();
        grant.setScopeType(WorkspaceAclService.SCOPE_FOLDER);
        grant.setScopePath("/a");
        grant.setCanRead(true);
        grant.setCanWrite(false);
        return grant;
    }

    private static WorkspaceAcl fileGrant(String path, Long fileId) {
        WorkspaceAcl grant = new WorkspaceAcl();
        grant.setScopeType(WorkspaceAclService.SCOPE_FILE);
        grant.setScopePath(path);
        grant.setFileId(fileId);
        grant.setCanRead(true);
        grant.setCanWrite(false);
        return grant;
    }

    private static WorkspaceFile row(long id, String name, String parent, boolean dir) {
        WorkspaceFile file = new WorkspaceFile();
        file.setId(id);
        file.setWorkspaceId(WS);
        file.setFileName(name);
        file.setParentPath(parent);
        file.setIsDirectory(dir);
        file.setFileSize(dir ? 0L : 10L);
        return file;
    }
}
