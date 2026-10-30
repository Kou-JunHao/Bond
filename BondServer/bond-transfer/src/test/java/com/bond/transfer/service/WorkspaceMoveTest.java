package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.entity.WorkspaceInvitation;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.entitlement.EntitlementResolver;
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
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * task-132 移动接口单测：同空间不动配额/对象、跨空间原子改两侧 used_size、
 * 目录递归、冲突 409、成环 400、目标目录 404、非法路径 400、非成员 403、已删 404。
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceMoveTest {

    private static final long USER = 7L;
    private static final long SOURCE_WS = 10L;
    private static final long TARGET_WS = 20L;
    private static final long FILE_ID = 1001L;
    private static final long DIR_ID = 2001L;
    private static final long CHILD_ID = 2002L;

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

    // ── 构造物料 ──

    private static Workspace workspace(long id) {
        Workspace ws = new Workspace();
        ws.setId(id);
        ws.setOwnerId(USER);
        ws.setMaxSize(10_000L);
        ws.setUsedSize(0L);
        return ws;
    }

    private static WorkspaceMember member() {
        WorkspaceMember m = new WorkspaceMember();
        m.setWorkspaceId(SOURCE_WS);
        m.setUserId(USER);
        m.setRole(1);
        return m;
    }

    private static WorkspaceFile file(long id, long ws, String parent, String name, long size) {
        WorkspaceFile f = new WorkspaceFile();
        f.setId(id);
        f.setWorkspaceId(ws);
        f.setParentPath(parent);
        f.setFileName(name);
        f.setFileSize(size);
        f.setIsDirectory(false);
        f.setMinioPath("workspaces/" + ws + "/uuid/" + name);
        f.setContentType("application/octet-stream");
        return f;
    }

    private static WorkspaceFile dir(long id, long ws, String parent, String name) {
        WorkspaceFile f = new WorkspaceFile();
        f.setId(id);
        f.setWorkspaceId(ws);
        f.setParentPath(parent);
        f.setFileName(name);
        f.setFileSize(0L);
        f.setIsDirectory(true);
        f.setMinioPath("");
        f.setContentType("httpd/unix-directory");
        return f;
    }

    /** 目标目录查找 + 冲突查找共用的 selectList 桩（顺序：先目录，后冲突）。 */
    private void stubEntryLookups(WorkspaceFile targetDirRow, List<WorkspaceFile> collisionRows) {
        when(fileMapper.selectList(any(Wrapper.class)))
                .thenReturn(targetDirRow == null ? List.<WorkspaceFile>of() : List.of(targetDirRow),
                        collisionRows);
    }

    // ── 1. 同空间：只改 parent_path，不动 used_size、不复制对象 ──

    @Test
    @DisplayName("同空间移动只改 parent_path/file_name，不碰 used_size 与对象存储")
    void sameSpaceMoveDoesNotTouchQuotaOrObjects() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        WorkspaceFile source = file(FILE_ID, SOURCE_WS, "/", "a.txt", 100);
        when(fileMapper.selectById(FILE_ID)).thenReturn(source);
        when(workspaceMapper.selectById(SOURCE_WS)).thenReturn(workspace(SOURCE_WS));
        WorkspaceFile docs = dir(DIR_ID, SOURCE_WS, "/", "docs");
        stubEntryLookups(docs, List.of());

        var dto = workspaceService.moveFile(USER, SOURCE_WS, FILE_ID, SOURCE_WS, "/docs", null);

        ArgumentCaptor<WorkspaceFile> saved = ArgumentCaptor.forClass(WorkspaceFile.class);
        verify(fileMapper).updateById(saved.capture());
        assertEquals("/docs", saved.getValue().getParentPath());
        assertEquals("a.txt", saved.getValue().getFileName());
        assertEquals(SOURCE_WS, saved.getValue().getWorkspaceId());
        assertEquals("/docs", dto.getParentPath());
        // 同空间不复制对象、不改配额（磁盘对象引用同一份）
        verifyNoInteractions(objectStorage);
        verify(workspaceMapper, never()).reserveUsedSize(anyLong(), anyLong());
        verify(workspaceMapper, never()).releaseUsedSize(anyLong(), anyLong());
    }

    @Test
    @DisplayName("同空间同位置且未改名 → 幂等返回，不写库")
    void sameSpaceSamePlaceIsNoop() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        WorkspaceFile source = file(FILE_ID, SOURCE_WS, "/a", "a.txt", 100);
        when(fileMapper.selectById(FILE_ID)).thenReturn(source);
        when(workspaceMapper.selectById(SOURCE_WS)).thenReturn(workspace(SOURCE_WS));

        var dto = workspaceService.moveFile(USER, SOURCE_WS, FILE_ID, SOURCE_WS, null, null);

        assertEquals("/a", dto.getParentPath());
        verify(fileMapper, never()).updateById(any(WorkspaceFile.class));
        verifyNoInteractions(objectStorage);
    }

    // ── 2. 跨空间：原子改两侧 used_size（先占目标、再释放源） ──

    @Test
    @DisplayName("跨空间移动：原子占用目标 + 原子释放源，行换 workspace，不复制对象")
    void crossSpaceMoveAdjustsBothSidesAtomically() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        WorkspaceFile source = file(FILE_ID, SOURCE_WS, "/", "a.txt", 100);
        when(fileMapper.selectById(FILE_ID)).thenReturn(source);
        when(workspaceMapper.selectById(TARGET_WS)).thenReturn(workspace(TARGET_WS));
        stubEntryLookups(null, List.of());
        when(workspaceMapper.reserveUsedSize(TARGET_WS, 100)).thenReturn(1);
        when(workspaceMapper.releaseUsedSize(SOURCE_WS, 100)).thenReturn(1);

        var dto = workspaceService.moveFile(USER, SOURCE_WS, FILE_ID, TARGET_WS, "/", null);

        assertEquals(TARGET_WS, dto.getWorkspaceId());
        ArgumentCaptor<WorkspaceFile> saved = ArgumentCaptor.forClass(WorkspaceFile.class);
        verify(fileMapper).updateById(saved.capture());
        assertEquals(TARGET_WS, saved.getValue().getWorkspaceId());
        assertEquals("/", saved.getValue().getParentPath());
        // 原子 SQL：先目标 +，后源 -（不读-改-写、不用 GREATEST 之外的兜底）
        InOrder order = org.mockito.Mockito.inOrder(workspaceMapper);
        order.verify(workspaceMapper).reserveUsedSize(TARGET_WS, 100);
        order.verify(workspaceMapper).releaseUsedSize(SOURCE_WS, 100);
        verifyNoInteractions(objectStorage);
    }

    @Test
    @DisplayName("跨空间配额不足 → 413，且不改行、不释放源")
    void crossSpaceQuotaExceededReturns413() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        WorkspaceFile source = file(FILE_ID, SOURCE_WS, "/", "a.txt", 100);
        when(fileMapper.selectById(FILE_ID)).thenReturn(source);
        when(workspaceMapper.selectById(TARGET_WS)).thenReturn(workspace(TARGET_WS));
        stubEntryLookups(null, List.of());
        when(workspaceMapper.reserveUsedSize(TARGET_WS, 100)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.moveFile(USER, SOURCE_WS, FILE_ID, TARGET_WS, "/", null));

        assertEquals(413, ex.getCode());
        verify(fileMapper, never()).updateById(any(WorkspaceFile.class));
        verify(workspaceMapper, never()).releaseUsedSize(anyLong(), anyLong());
    }

    @Test
    @DisplayName("目标空间非成员 → 403")
    void crossSpaceNonMemberReturns403() {
        // 源成员校验通过，目标校验失败
        when(memberMapper.selectOne(any())).thenReturn(member(), (WorkspaceMember) null);
        WorkspaceFile source = file(FILE_ID, SOURCE_WS, "/", "a.txt", 100);
        when(fileMapper.selectById(FILE_ID)).thenReturn(source);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.moveFile(USER, SOURCE_WS, FILE_ID, TARGET_WS, "/", null));

        assertEquals(403, ex.getCode());
        verify(workspaceMapper, never()).reserveUsedSize(anyLong(), anyLong());
        verify(fileMapper, never()).updateById(any(WorkspaceFile.class));
    }

    // ── 3. 目录递归 + 配额按子树总字节 ──

    @Test
    @DisplayName("目录跨空间移动：整棵子树换 workspace、保留相对结构，配额按子树总字节")
    void directoryCrossSpaceMoveRewritesSubtree() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        WorkspaceFile root = dir(DIR_ID, SOURCE_WS, "/", "d");
        when(fileMapper.selectById(DIR_ID)).thenReturn(root);
        when(workspaceMapper.selectById(TARGET_WS)).thenReturn(workspace(TARGET_WS));
        WorkspaceFile targetDir = dir(3003L, TARGET_WS, "/", "target");
        WorkspaceFile child = file(CHILD_ID, SOURCE_WS, "/d", "f.bin", 100);
        when(fileMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(targetDir), List.<WorkspaceFile>of(), List.of(child));
        when(workspaceMapper.reserveUsedSize(TARGET_WS, 100)).thenReturn(1);
        when(workspaceMapper.releaseUsedSize(SOURCE_WS, 100)).thenReturn(1);

        workspaceService.moveFile(USER, SOURCE_WS, DIR_ID, TARGET_WS, "/target", null);

        ArgumentCaptor<WorkspaceFile> saved = ArgumentCaptor.forClass(WorkspaceFile.class);
        verify(fileMapper, org.mockito.Mockito.times(2)).updateById(saved.capture());
        WorkspaceFile childSaved = saved.getAllValues().stream()
                .filter(r -> CHILD_ID == r.getId()).findFirst().orElseThrow();
        assertEquals(TARGET_WS, childSaved.getWorkspaceId());
        assertEquals("/target/d", childSaved.getParentPath());
        WorkspaceFile rootSaved = saved.getAllValues().stream()
                .filter(r -> DIR_ID == r.getId()).findFirst().orElseThrow();
        assertEquals(TARGET_WS, rootSaved.getWorkspaceId());
        assertEquals("/target", rootSaved.getParentPath());
        verify(workspaceMapper).reserveUsedSize(TARGET_WS, 100);
        verify(workspaceMapper).releaseUsedSize(SOURCE_WS, 100);
        verifyNoInteractions(objectStorage); // 移动不复制字节
    }

    // ── 4. 冲突 / 成环 / 目标不存在 / 非法路径 / 已删 ──

    @Test
    @DisplayName("目标同名（未删）→ 409")
    void conflictReturns409() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        WorkspaceFile source = file(FILE_ID, SOURCE_WS, "/", "a.txt", 100);
        when(fileMapper.selectById(FILE_ID)).thenReturn(source);
        when(workspaceMapper.selectById(SOURCE_WS)).thenReturn(workspace(SOURCE_WS));
        WorkspaceFile targetDir = dir(DIR_ID, SOURCE_WS, "/", "docs");
        WorkspaceFile collision = file(9999L, SOURCE_WS, "/docs", "a.txt", 1);
        stubEntryLookups(targetDir, List.of(collision));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.moveFile(USER, SOURCE_WS, FILE_ID, SOURCE_WS, "/docs", null));

        assertEquals(409, ex.getCode());
        verify(fileMapper, never()).updateById(any(WorkspaceFile.class));
    }

    @Test
    @DisplayName("目录移进自己的子孙目录 → 400（防环）")
    void moveDirectoryIntoOwnDescendantReturns400() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        WorkspaceFile root = dir(DIR_ID, SOURCE_WS, "/", "a");
        when(fileMapper.selectById(DIR_ID)).thenReturn(root);
        when(workspaceMapper.selectById(SOURCE_WS)).thenReturn(workspace(SOURCE_WS));
        WorkspaceFile b = dir(3004L, SOURCE_WS, "/a", "b");
        when(fileMapper.selectList(any(Wrapper.class))).thenReturn(List.of(b));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.moveFile(USER, SOURCE_WS, DIR_ID, SOURCE_WS, "/a/b", null));

        assertEquals(400, ex.getCode());
        verify(fileMapper, never()).updateById(any(WorkspaceFile.class));
    }

    @Test
    @DisplayName("目标目录不存在 → 404")
    void missingTargetParentReturns404() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        WorkspaceFile source = file(FILE_ID, SOURCE_WS, "/", "a.txt", 100);
        when(fileMapper.selectById(FILE_ID)).thenReturn(source);
        when(workspaceMapper.selectById(SOURCE_WS)).thenReturn(workspace(SOURCE_WS));
        when(fileMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.moveFile(USER, SOURCE_WS, FILE_ID, SOURCE_WS, "/x/y", null));

        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("targetParentPath 不以 / 开头或含 .. → 400")
    void invalidTargetPathReturns400() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        WorkspaceFile source = file(FILE_ID, SOURCE_WS, "/", "a.txt", 100);
        when(fileMapper.selectById(FILE_ID)).thenReturn(source);
        when(workspaceMapper.selectById(SOURCE_WS)).thenReturn(workspace(SOURCE_WS));

        BusinessException noSlash = assertThrows(BusinessException.class,
                () -> workspaceService.moveFile(USER, SOURCE_WS, FILE_ID, SOURCE_WS, "docs", null));
        assertEquals(400, noSlash.getCode());

        BusinessException dotDot = assertThrows(BusinessException.class,
                () -> workspaceService.moveFile(USER, SOURCE_WS, FILE_ID, SOURCE_WS, "/a/../b", null));
        assertEquals(400, dotDot.getCode());
    }

    @Test
    @DisplayName("源文件已进回收站 → 404")
    void deletedSourceReturns404() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        WorkspaceFile source = file(FILE_ID, SOURCE_WS, "/", "a.txt", 100);
        source.setDeletedAt(java.time.OffsetDateTime.now());
        when(fileMapper.selectById(FILE_ID)).thenReturn(source);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.moveFile(USER, SOURCE_WS, FILE_ID, SOURCE_WS, "/docs", null));

        assertEquals(404, ex.getCode());
        verify(fileMapper, never()).updateById(any(WorkspaceFile.class));
        verify(fileMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("目标文件名非法（含 /）→ 400")
    void invalidNewNameReturns400() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        WorkspaceFile source = file(FILE_ID, SOURCE_WS, "/", "a.txt", 100);
        when(fileMapper.selectById(FILE_ID)).thenReturn(source);
        when(workspaceMapper.selectById(SOURCE_WS)).thenReturn(workspace(SOURCE_WS));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.moveFile(USER, SOURCE_WS, FILE_ID, SOURCE_WS, "/", "bad/name"));

        assertEquals(400, ex.getCode());
    }
}
