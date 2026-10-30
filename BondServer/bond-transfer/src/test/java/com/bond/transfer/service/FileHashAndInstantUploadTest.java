package com.bond.transfer.service;

import com.bond.common.dto.InstantUploadDTO;
import com.bond.common.dto.WorkspaceFileDTO;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.enums.WorkspaceRole;
import com.bond.common.enums.WorkspaceType;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.Entitlements;
import com.bond.transfer.entitlement.PlanLimits;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceInvitationMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.preview.ThumbnailService;
import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * task-41：单次上传服务端算 SHA-256 落库 + 秒传（instant upload）。
 *
 * <p>安全口径：秒传只在“调用者本来就有权限的工作区”里找源文件（SQL 由 {@code findInstantSource} 限定），
 * 然后走 S3 server-side CopyObject。本测试用 mock 校验复制/配额回滚/未命中回退等行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileHashAndInstantUploadTest {

    private static final long USER_ID = 7L;
    private static final long WS_ID = 9L;
    private static final long OTHER_WS_ID = 10L;

    @Mock
    private WorkspaceMapper workspaceMapper;
    @Mock
    private WorkspaceMemberMapper memberMapper;
    @Mock
    private WorkspaceFileMapper fileMapper;
    @Mock
    private WorkspaceInvitationMapper invitationMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private EntitlementResolver entitlementResolver;
    @Mock
    private UsageService usageService;
    @Mock
    private ConfigService configService;
    @Mock
    private ThumbnailService thumbnailService;

    @InjectMocks
    private WorkspaceService workspaceService;

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private void stubMember() {
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(WS_ID);
        member.setUserId(USER_ID);
        member.setRole(WorkspaceRole.OWNER.getValue());
        when(memberMapper.selectOne(any())).thenReturn(member);
    }

    private void stubEntitlements() {
        when(entitlementResolver.resolve(USER_ID))
                .thenReturn(new Entitlements("free", PlanLimits.FREE_DEFAULTS, null));
    }

    private Workspace workspace(long maxSize) {
        Workspace ws = new Workspace();
        ws.setId(WS_ID);
        ws.setOwnerId(USER_ID);
        ws.setType(WorkspaceType.TEAM.getValue());
        ws.setMaxSize(maxSize);
        ws.setUsedSize(0L);
        return ws;
    }

    private void stubStorageWrite() {
        doAnswer(invocation -> {
            InputStream in = invocation.getArgument(1);
            in.readAllBytes();
            return null;
        }).when(objectStorage).putObject(anyString(), any(InputStream.class), eq(5L), anyString());
    }

    // ── 单次上传：服务端计算 hash ──

    @Test
    @DisplayName("单次上传：file_hash 落库为服务端实际读取字节的 SHA-256")
    void singleUploadStoresServerComputedHash() throws Exception {
        stubMember();
        stubEntitlements();
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(1024L * 1024 * 1024));
        when(fileMapper.selectList(any())).thenReturn(List.of());
        when(workspaceMapper.reserveUsedSize(WS_ID, 5L)).thenReturn(1);
        stubStorageWrite();
        when(fileMapper.insert(any(WorkspaceFile.class))).thenReturn(1);

        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
        WorkspaceFileDTO dto = workspaceService.uploadFile(WS_ID, USER_ID, "/", file, null);

        assertEquals(sha256("hello".getBytes()), dto.getFileHash());
        verify(objectStorage).putObject(anyString(), any(InputStream.class), eq(5L), eq("text/plain"));
    }

    @Test
    @DisplayName("单次上传：客户端声明的 hash 只用于去重，不写库（以服务端 digest 为准）")
    void singleUploadIgnoresClientDeclaredHashForStorage() throws Exception {
        stubMember();
        stubEntitlements();
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(1024L * 1024 * 1024));
        when(fileMapper.selectList(any())).thenReturn(List.of());
        when(workspaceMapper.reserveUsedSize(WS_ID, 5L)).thenReturn(1);
        stubStorageWrite();
        when(fileMapper.insert(any(WorkspaceFile.class))).thenReturn(1);

        String forged = "f".repeat(64);
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
        WorkspaceFileDTO dto = workspaceService.uploadFile(WS_ID, USER_ID, "/", file, forged);

        assertEquals(sha256("hello".getBytes()), dto.getFileHash());
        assertNotEquals(forged, dto.getFileHash());
    }

    @Test
    @DisplayName("单次上传：对象写入失败时回滚配额并抛出")
    void singleUploadRollsBackQuotaWhenStorageFails() throws Exception {
        stubMember();
        stubEntitlements();
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(1024L * 1024 * 1024));
        when(fileMapper.selectList(any())).thenReturn(List.of());
        when(workspaceMapper.reserveUsedSize(WS_ID, 5L)).thenReturn(1);
        doThrow(new RuntimeException("s3 unavailable")).when(objectStorage)
                .putObject(anyString(), any(InputStream.class), eq(5L), anyString());

        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
        assertThrows(RuntimeException.class, () -> workspaceService.uploadFile(WS_ID, USER_ID, "/", file, null));

        verify(workspaceMapper).releaseUsedSize(WS_ID, 5L);
    }

    // ── 秒传 ──

    private WorkspaceFile sourceFile(String key) {
        WorkspaceFile f = new WorkspaceFile();
        f.setId(100L);
        f.setWorkspaceId(OTHER_WS_ID);
        f.setFileName("old.bin");
        f.setFileSize(42L);
        f.setMinioPath(key);
        f.setContentType("application/octet-stream");
        f.setIsDirectory(false);
        return f;
    }

    @Test
    @DisplayName("秒传：命中后 CopyObject 出独立对象、建行、不动流量")
    void instantUploadCopiesObjectAndCreatesRow() throws Exception {
        String hash = "a".repeat(64);
        stubMember();
        stubEntitlements();
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(1024L * 1024 * 1024));
        WorkspaceMember membership = new WorkspaceMember();
        membership.setWorkspaceId(OTHER_WS_ID);
        membership.setUserId(USER_ID);
        when(memberMapper.selectList(any())).thenReturn(List.of(membership));
        when(workspaceMapper.selectList(any())).thenReturn(List.of());
        WorkspaceFile source = sourceFile("workspaces/10/old.uuid/old.bin");
        when(fileMapper.selectList(any())).thenReturn(List.of(source), List.of());
        when(workspaceMapper.reserveUsedSize(WS_ID, 42L)).thenReturn(1);
        when(fileMapper.insert(any(WorkspaceFile.class))).thenReturn(1);

        WorkspaceFileDTO dto = workspaceService.instantUpload(WS_ID, USER_ID, "/", "new.bin", hash, 42L, null);

        assertNotNull(dto);
        assertNotNull(dto.getId());
        verify(objectStorage).copyObject(eq("workspaces/10/old.uuid/old.bin"),
                argThat(key -> key.startsWith("workspaces/" + WS_ID + "/") && key.endsWith("/new.bin")));
        ArgumentCaptor<WorkspaceFile> inserted = ArgumentCaptor.forClass(WorkspaceFile.class);
        verify(fileMapper).insert(inserted.capture());
        assertEquals(hash, inserted.getValue().getFileHash());
        assertEquals("new.bin", inserted.getValue().getFileName());
        verifyNoInteractions(usageService);
    }

    @Test
    @DisplayName("秒传：没有可用源文件 → 404（客户端回退普通上传）")
    void instantUploadMissReturns404() {
        stubMember();
        stubEntitlements();
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(1024L * 1024 * 1024));
        when(memberMapper.selectList(any())).thenReturn(List.of());
        when(workspaceMapper.selectList(any())).thenReturn(List.of());
        when(fileMapper.selectList(any())).thenReturn(List.of());

        BusinessException e = assertThrows(BusinessException.class,
                () -> workspaceService.instantUpload(WS_ID, USER_ID, "/", "new.bin", "a".repeat(64), 42L, null));

        assertEquals(404, e.getCode());
        verify(objectStorage, never()).copyObject(anyString(), anyString());
        verifyNoInteractions(usageService);
    }

    @Test
    @DisplayName("秒传：hash 格式非法 → 400 且不查库")
    void instantUploadRejectsMalformedHash() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> workspaceService.instantUpload(WS_ID, USER_ID, "/", "new.bin", "xyz", 42L, null));

        assertEquals(400, e.getCode());
        verifyNoInteractions(workspaceMapper, memberMapper, fileMapper, objectStorage);
    }

    @Test
    @DisplayName("秒传：目标空间剩余配额不足 → 413")
    void instantUploadQuotaShortageReturns413() {
        stubMember();
        stubEntitlements();
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(100L));

        BusinessException e = assertThrows(BusinessException.class,
                () -> workspaceService.instantUpload(WS_ID, USER_ID, "/", "new.bin", "a".repeat(64), 200L, null));

        assertEquals(413, e.getCode());
        verify(objectStorage, never()).copyObject(anyString(), anyString());
    }

    @Test
    @DisplayName("秒传：CopyObject 失败 → 回滚配额并抛错")
    void instantUploadRollsBackWhenCopyFails() {
        stubMember();
        stubEntitlements();
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(1024L * 1024 * 1024));
        when(memberMapper.selectList(any())).thenReturn(List.of());
        when(workspaceMapper.selectList(any())).thenReturn(List.of());
        when(fileMapper.selectList(any())).thenReturn(List.of(sourceFile("workspaces/10/old.uuid/old.bin")));
        when(workspaceMapper.reserveUsedSize(WS_ID, 42L)).thenReturn(1);
        doThrow(new RuntimeException("copy failed")).when(objectStorage)
                .copyObject(anyString(), anyString());

        assertThrows(RuntimeException.class, () -> workspaceService
                .instantUpload(WS_ID, USER_ID, "/", "new.bin", "a".repeat(64), 42L, null));

        verify(workspaceMapper).releaseUsedSize(WS_ID, 42L);
        verify(objectStorage).copyObject(eq("workspaces/10/old.uuid/old.bin"), anyString());
    }

    @Test
    @DisplayName("秒传：非空间成员 → 403")
    void instantUploadRejectsNonMember() {
        when(memberMapper.selectOne(any())).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> workspaceService.instantUpload(WS_ID, USER_ID, "/", "new.bin", "a".repeat(64), 42L, null));

        assertEquals(403, e.getCode());
        verifyNoInteractions(objectStorage);
    }

    // ── 秒传安全边界：只有目标 + 成员 + 自有工作区，绝不全站 ──

    @Test
    @DisplayName("秒传查找范围 = 目标工作区 + 成员工作区 + 自己拥有的工作区")
    void instantScopeLimitedToTargetMembershipsAndOwnedWorkspaces() {
        WorkspaceMember membership = new WorkspaceMember();
        membership.setWorkspaceId(OTHER_WS_ID);
        membership.setUserId(USER_ID);
        when(memberMapper.selectList(any())).thenReturn(List.of(membership));
        Workspace owned = new Workspace();
        owned.setId(11L);
        owned.setOwnerId(USER_ID);
        when(workspaceMapper.selectList(any())).thenReturn(List.of(owned));

        java.util.Set<Long> scope = workspaceService.instantScopeWorkspaceIds(WS_ID, USER_ID);

        assertEquals(java.util.Set.of(WS_ID, OTHER_WS_ID, 11L), scope);
        org.junit.jupiter.api.Assertions.assertFalse(scope.contains(99L), "不能包含无权限工作区");
    }

    @Test
    @DisplayName("秒传响应 DTO 固定 instant=true（与普通上传区分）")
    void instantUploadDTOAlwaysFlaggedInstant() {
        assertTrue(new InstantUploadDTO().getInstant());
        assertEquals(Boolean.TRUE, new InstantUploadDTO().getInstant());
    }
}
