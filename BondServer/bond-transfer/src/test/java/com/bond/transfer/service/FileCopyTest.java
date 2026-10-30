package com.bond.transfer.service;

import com.bond.common.dto.WorkspaceFileDTO;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.entity.WorkspaceMember;
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

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-102 D 段：文件转存（复制到目标目录）。
 *
 * <p>核心口径：服务端 S3 CopyObject <b>不产生流量</b>（不得调用计量），但目标空间照常原子占配额。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileCopyTest {

    private static final Long USER_ID = 7L;
    private static final Long WS_ID = 100L;
    private static final Long TARGET_WS_ID = 200L;
    private static final Long FILE_ID = 500L;
    private static final long SIZE = 2048L;
    private static final String HASH = "ab".repeat(32);

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

    @BeforeEach
    void setUp() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        when(workspaceMapper.selectById(any())).thenAnswer(inv ->
                workspace(((Number) inv.getArgument(0)).longValue()));
        when(entitlementResolver.resolve(USER_ID)).thenReturn(entitlements());
        when(fileMapper.selectList(any())).thenReturn(List.of());
        when(workspaceMapper.reserveUsedSize(anyLong(), anyLong())).thenReturn(1);
        when(fileMapper.insert(any(WorkspaceFile.class))).thenReturn(1);
    }

    @Test
    @DisplayName("同空间转存：S3 CopyObject 复制对象、建新行，且不产生下行计量")
    void copyFileCopiesObjectWithoutMeteringTraffic() {
        when(fileMapper.selectById(FILE_ID)).thenReturn(sourceFile());

        WorkspaceFileDTO dto = workspaceService.copyFile(
                USER_ID, WS_ID, FILE_ID, null, "/backup", "report-copy.pdf");

        assertEquals("report-copy.pdf", dto.getFileName());
        assertEquals(WS_ID, dto.getWorkspaceId());
        assertEquals("/backup", dto.getParentPath());
        assertEquals(SIZE, dto.getFileSize());
        assertEquals(HASH, dto.getFileHash());
        assertEquals(USER_ID, dto.getUploadedBy());

        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(objectStorage, times(1)).copyObject(from.capture(), to.capture());
        assertEquals("workspaces/100/uuid/report.pdf", from.getValue());
        assertTrue(to.getValue().startsWith("workspaces/100/"));
        assertTrue(to.getValue().endsWith("/report-copy.pdf"));

        // 转存 = 服务端对象复制，不读回字节、不计流量
        verify(usageService, never()).meterAndThrottleDownload(any(), any());
        verify(objectStorage, never()).getObject(anyString());
        verify(workspaceMapper, times(1)).reserveUsedSize(WS_ID, SIZE);
    }

    @Test
    @DisplayName("跨工作区转存：目标为另一个工作区，配额记在目标空间")
    void copyFileAcrossWorkspaces() {
        when(fileMapper.selectById(FILE_ID)).thenReturn(sourceFile());

        WorkspaceFileDTO dto = workspaceService.copyFile(
                USER_ID, WS_ID, FILE_ID, TARGET_WS_ID, "/", "moved.pdf");

        assertEquals(TARGET_WS_ID, dto.getWorkspaceId());
        verify(workspaceMapper).reserveUsedSize(TARGET_WS_ID, SIZE);
        verify(usageService, never()).meterAndThrottleDownload(any(), any());
    }

    @Test
    @DisplayName("目标空间配额不足：413，且不复制对象")
    void copyFileQuotaExceededReturns413() {
        when(fileMapper.selectById(FILE_ID)).thenReturn(sourceFile());
        when(workspaceMapper.reserveUsedSize(anyLong(), anyLong())).thenReturn(0);

        BusinessException e = assertThrows(BusinessException.class,
                () -> workspaceService.copyFile(USER_ID, WS_ID, FILE_ID, null, "/", "copy.pdf"));

        assertEquals(413, e.getCode());
        verify(objectStorage, never()).copyObject(anyString(), anyString());
    }

    @Test
    @DisplayName("目标目录已有同名文件：400，不静默覆盖")
    void copyFileDuplicateNameReturns400() {
        when(fileMapper.selectById(FILE_ID)).thenReturn(sourceFile());
        when(fileMapper.selectList(any())).thenReturn(List.of(existingEntry()));

        BusinessException e = assertThrows(BusinessException.class,
                () -> workspaceService.copyFile(USER_ID, WS_ID, FILE_ID, null, "/", null));

        assertEquals(400, e.getCode());
        verify(workspaceMapper, never()).reserveUsedSize(anyLong(), anyLong());
        verify(objectStorage, never()).copyObject(anyString(), anyString());
    }

    @Test
    @DisplayName("目录不支持转存：400")
    void copyFileDirectoryReturns400() {
        WorkspaceFile directory = sourceFile();
        directory.setIsDirectory(true);
        when(fileMapper.selectById(FILE_ID)).thenReturn(directory);

        BusinessException e = assertThrows(BusinessException.class,
                () -> workspaceService.copyFile(USER_ID, WS_ID, FILE_ID, null, "/", "dir-copy"));

        assertEquals(400, e.getCode());
        verify(objectStorage, never()).copyObject(anyString(), anyString());
    }

    @Test
    @DisplayName("来源文件不存在 / 已在回收站：404（不泄露存在性）")
    void copyFileMissingOrTrashedReturns404() {
        when(fileMapper.selectById(FILE_ID)).thenReturn(null);
        BusinessException missing = assertThrows(BusinessException.class,
                () -> workspaceService.copyFile(USER_ID, WS_ID, FILE_ID, null, "/", "copy.pdf"));
        assertEquals(404, missing.getCode());

        WorkspaceFile trashed = sourceFile();
        trashed.setDeletedAt(OffsetDateTime.now());
        when(fileMapper.selectById(FILE_ID)).thenReturn(trashed);
        BusinessException trash = assertThrows(BusinessException.class,
                () -> workspaceService.copyFile(USER_ID, WS_ID, FILE_ID, null, "/", "copy.pdf"));
        assertEquals(404, trash.getCode());
    }

    @Test
    @DisplayName("目标工作区不可写：403")
    void copyFileTargetNotMemberReturns403() {
        when(fileMapper.selectById(FILE_ID)).thenReturn(sourceFile());
        when(memberMapper.selectOne(any())).thenReturn(member()).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> workspaceService.copyFile(USER_ID, WS_ID, FILE_ID, TARGET_WS_ID, "/", "x.pdf"));

        assertEquals(403, e.getCode());
        verify(objectStorage, never()).copyObject(anyString(), anyString());
    }

    // ── helpers ──

    private static WorkspaceFile sourceFile() {
        WorkspaceFile file = new WorkspaceFile();
        file.setId(FILE_ID);
        file.setWorkspaceId(WS_ID);
        file.setFileName("report.pdf");
        file.setFileSize(SIZE);
        file.setMinioPath("workspaces/100/uuid/report.pdf");
        file.setContentType("application/pdf");
        file.setParentPath("/docs");
        file.setIsDirectory(false);
        file.setFileHash(HASH);
        file.setUploadedBy(USER_ID);
        return file;
    }

    private static WorkspaceFile existingEntry() {
        WorkspaceFile file = new WorkspaceFile();
        file.setId(999L);
        file.setWorkspaceId(WS_ID);
        file.setFileName("report.pdf");
        file.setParentPath("/");
        file.setIsDirectory(false);
        return file;
    }

    private static WorkspaceMember member() {
        WorkspaceMember member = new WorkspaceMember();
        member.setId(1L);
        member.setWorkspaceId(WS_ID);
        member.setUserId(USER_ID);
        member.setRole(0);
        return member;
    }

    private static Workspace workspace(long id) {
        Workspace ws = new Workspace();
        ws.setId(id);
        ws.setName("ws-" + id);
        ws.setOwnerId(USER_ID);
        ws.setType("team");
        ws.setMaxSize(1_000_000_000L);
        ws.setUsedSize(0L);
        return ws;
    }

    private static Entitlements entitlements() {
        PlanLimits limits = new PlanLimits(
                10L * 1024 * 1024 * 1024,
                100L * 1024 * 1024 * 1024,
                2L * 1024 * 1024 * 1024,
                2, 20, 50, 0L, 0, 3, 0, 0, 30);
        return new Entitlements("team", limits, null);
    }
}
