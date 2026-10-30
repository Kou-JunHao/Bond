package com.bond.transfer.service;

import com.bond.common.dto.WorkspaceFileDTO;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.enums.WorkspaceRole;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * task-47 回归：WebDAV MKCOL/PUT 500 的根因是「目录行 minio_path NOT NULL 未赋值」，
 * 且 WebDAV 写入必须复用 WorkspaceService 的配额/回滚语义。
 *
 * <p>这里直接对 WorkspaceService 的公共写入口做单测（不依赖 DB/S3）：
 * 目录行必须落 {@code minio_path=""}；文件上传必须落 minio_path/file_hash 并原子占配额；
 * 对象存储失败必须回滚配额与对象；配额/档位超限必须在写对象前失败。</p>
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceWebDavWriteTest {

    private static final Long WS_ID = 100L;
    private static final Long USER_ID = 7L;

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

    @Test
    void createDirectoryPersistsMinioPathAndDirectoryContentType() {
        stubMember();
        when(fileMapper.selectList(any())).thenReturn(List.of());

        WorkspaceFileDTO dto = workspaceService.createDirectory(WS_ID, USER_ID, "/", "docs");

        ArgumentCaptor<WorkspaceFile> captor = ArgumentCaptor.forClass(WorkspaceFile.class);
        verify(fileMapper).insert(captor.capture());
        WorkspaceFile row = captor.getValue();
        // 回归点：minio_path 在 DB 是 NOT NULL，目录必须落空串而不是 null
        assertNotNull(row.getMinioPath());
        assertEquals("", row.getMinioPath());
        assertEquals(WorkspaceService.CONTENT_TYPE_DIRECTORY, row.getContentType());
        assertTrue(row.getIsDirectory());
        assertEquals("/", row.getParentPath());
        assertEquals(0L, row.getFileSize());
        assertNotNull(dto);
        assertEquals("docs", dto.getFileName());
    }

    @Test
    void createDirectoryRejectsExistingNameWith405() {
        stubMember();
        WorkspaceFile existing = new WorkspaceFile();
        existing.setId(9L);
        existing.setWorkspaceId(WS_ID);
        existing.setFileName("docs");
        existing.setParentPath("/");
        existing.setIsDirectory(true);
        when(fileMapper.selectList(any())).thenReturn(List.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.createDirectory(WS_ID, USER_ID, "/", "docs"));

        assertEquals(405, ex.getCode());
        verify(fileMapper, never()).insert(any(WorkspaceFile.class));
    }

    @Test
    void createDirectoryRejectsTraversalName() {
        stubMember();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.createDirectory(WS_ID, USER_ID, "/", ".."));

        assertEquals(400, ex.getCode());
        verifyNoInteractions(fileMapper);
    }

    @Test
    void copyDirectoryAlsoPersistsMinioPath() {
        stubMember();
        WorkspaceFile source = new WorkspaceFile();
        source.setId(55L);
        source.setWorkspaceId(WS_ID);
        source.setFileName("src");
        source.setParentPath("/");
        source.setFileSize(0L);
        source.setIsDirectory(true);
        when(fileMapper.selectById(55L)).thenReturn(source);
        when(fileMapper.selectList(any())).thenReturn(List.of());

        workspaceService.copyEntry(WS_ID, USER_ID, 55L, "/", "src-copy");

        ArgumentCaptor<WorkspaceFile> captor = ArgumentCaptor.forClass(WorkspaceFile.class);
        verify(fileMapper).insert(captor.capture());
        WorkspaceFile row = captor.getValue();
        assertNotNull(row.getMinioPath());
        assertEquals("", row.getMinioPath());
        assertEquals(WorkspaceService.CONTENT_TYPE_DIRECTORY, row.getContentType());
        assertTrue(row.getIsDirectory());
        assertEquals("/src-copy", "/" + row.getFileName());
    }

    @Test
    void uploadStreamPersistsFileRowAndReservesQuota() throws Exception {
        stubMember();
        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan(1_000_000L));
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(0L, 500L));
        when(fileMapper.selectList(any())).thenReturn(List.of());
        when(workspaceMapper.reserveUsedSize(WS_ID, 5L)).thenReturn(1);
        doAnswer(invocation -> {
            InputStream in = invocation.getArgument(1);
            in.transferTo(OutputStream.nullOutputStream());
            return null;
        }).when(objectStorage).putObject(anyString(), any(InputStream.class), eq(5L), eq("text/plain"));

        WorkspaceFileDTO dto = workspaceService.uploadStream(WS_ID, USER_ID, "/", "hello.txt",
                new ByteArrayInputStream(new byte[5]), 5, "text/plain");

        verify(workspaceMapper).reserveUsedSize(WS_ID, 5L);
        ArgumentCaptor<WorkspaceFile> captor = ArgumentCaptor.forClass(WorkspaceFile.class);
        verify(fileMapper).insert(captor.capture());
        WorkspaceFile row = captor.getValue();
        assertFalse(Boolean.TRUE.equals(row.getIsDirectory()));
        assertEquals(5L, row.getFileSize());
        assertNotNull(row.getMinioPath());
        assertTrue(row.getMinioPath().startsWith("workspaces/" + WS_ID + "/"));
        assertTrue(row.getMinioPath().endsWith("hello.txt"));
        assertNotNull(row.getFileHash());
        assertEquals(64, row.getFileHash().length());
        assertNotNull(dto);
    }

    @Test
    void uploadStreamStorageFailureReleasesQuotaAndDeletesObject() {
        stubMember();
        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan(1_000_000L));
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(0L, 500L));
        when(fileMapper.selectList(any())).thenReturn(List.of());
        when(workspaceMapper.reserveUsedSize(WS_ID, 5L)).thenReturn(1);
        doThrow(new RuntimeException("s3 down")).when(objectStorage)
                .putObject(anyString(), any(InputStream.class), eq(5L), anyString());

        assertThrows(RuntimeException.class, () -> workspaceService.uploadStream(WS_ID, USER_ID, "/",
                "hello.txt", new ByteArrayInputStream(new byte[5]), 5, "text/plain"));

        verify(workspaceMapper).releaseUsedSize(WS_ID, 5L);
        verify(objectStorage).deleteObject(anyString());
        verify(fileMapper, never()).insert(any(WorkspaceFile.class));
    }

    @Test
    void uploadStreamQuotaExceededReturns413BeforeWritingObject() {
        stubMember();
        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan(1_000_000L));
        when(workspaceMapper.selectById(WS_ID)).thenReturn(workspace(498L, 500L));

        BusinessException ex = assertThrows(BusinessException.class, () -> workspaceService.uploadStream(
                WS_ID, USER_ID, "/", "hello.txt", new ByteArrayInputStream(new byte[5]), 5, "text/plain"));

        assertEquals(413, ex.getCode());
        verifyNoInteractions(objectStorage);
    }

    @Test
    void uploadStreamPlanLimitExceededReturns400BeforeWritingObject() {
        stubMember();
        when(entitlementResolver.resolve(USER_ID)).thenReturn(plan(10L));

        BusinessException ex = assertThrows(BusinessException.class, () -> workspaceService.uploadStream(
                WS_ID, USER_ID, "/", "big.bin", new ByteArrayInputStream(new byte[100]), 100, "application/octet-stream"));

        assertEquals(400, ex.getCode());
        verifyNoInteractions(objectStorage, fileMapper);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void stubMember() {
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(WS_ID);
        member.setUserId(USER_ID);
        member.setRole(WorkspaceRole.MEMBER.getValue());
        when(memberMapper.selectOne(any())).thenReturn(member);
    }

    private static Workspace workspace(long usedSize, long maxSize) {
        Workspace ws = new Workspace();
        ws.setId(WS_ID);
        ws.setUsedSize(usedSize);
        ws.setMaxSize(maxSize);
        ws.setOwnerId(USER_ID);
        return ws;
    }

    private static Entitlements plan(long maxFileSize) {
        return new Entitlements("free", new PlanLimits(
                10_000_000_000L, 5_000_000_000L, maxFileSize,
                2, 1, 3, 0L, 7, 3, 30, 5, 30), null);
    }
}
