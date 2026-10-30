package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * task-118：REST 建目录 + 上传自动补父目录。
 *
 * <p>本地无 PG，全部用 Mockito 断言「服务层行为」：目录行不占配额、父目录缺失 404、
 * 重名 409、非法名 400、上传时逐级补目录（multipart 与分片 complete 两条路径）。</p>
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceFolderTest {

    private static final Long USER = 1L;
    private static final Long WS = 5L;
    private static final String DIR_CONTENT_TYPE = "httpd/unix-directory";

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

    @BeforeAll
    static void initLambdaCache() {
        // 纯单测里没有 MyBatis 启动流程，LambdaQueryWrapper 需要手动初始化 TableInfo 缓存
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test-lambda-cache");
        TableInfoHelper.initTableInfo(assistant, WorkspaceFile.class);
        TableInfoHelper.initTableInfo(assistant, Workspace.class);
    }

    private static WorkspaceMember member() {
        WorkspaceMember m = new WorkspaceMember();
        m.setWorkspaceId(WS);
        m.setUserId(USER);
        m.setRole(0);
        return m;
    }

    private static Workspace workspace(long usedSize, long maxSize) {
        Workspace ws = new Workspace();
        ws.setId(WS);
        ws.setOwnerId(USER);
        ws.setUsedSize(usedSize);
        ws.setMaxSize(maxSize);
        return ws;
    }

    private static WorkspaceFile row(Long id, Long wsId, String parent, String name,
                                     long size, boolean dir) {
        WorkspaceFile f = new WorkspaceFile();
        f.setId(id);
        f.setWorkspaceId(wsId);
        f.setParentPath(parent);
        f.setFileName(name);
        f.setFileSize(size);
        f.setIsDirectory(dir);
        f.setMinioPath(dir ? "" : "workspaces/5/x/" + name);
        if (dir) {
            f.setContentType(DIR_CONTENT_TYPE);
        }
        return f;
    }

    @Test
    @DisplayName("建目录成功：目录行 isDirectory=true / size=0 / unix-directory，且不占配额")
    void createFolderCreatesDirectoryRowWithoutQuota() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        when(fileMapper.selectList(any())).thenReturn(List.of());
        when(fileMapper.insert(any(WorkspaceFile.class))).thenReturn(1);

        var dto = workspaceService.createFolder(WS, USER, "/", "docs");

        assertNotNull(dto);
        assertEquals("docs", dto.getFileName());
        assertEquals("/", dto.getParentPath());
        assertTrue(dto.getIsDirectory());
        assertEquals(0L, dto.getFileSize());
        assertEquals(DIR_CONTENT_TYPE, dto.getContentType());
        assertEquals("", dto.getMinioPath());

        ArgumentCaptor<WorkspaceFile> captor = ArgumentCaptor.forClass(WorkspaceFile.class);
        verify(fileMapper).insert(captor.capture());
        WorkspaceFile inserted = captor.getValue();
        assertTrue(Boolean.TRUE.equals(inserted.getIsDirectory()));
        assertEquals("", inserted.getMinioPath());
        assertEquals(DIR_CONTENT_TYPE, inserted.getContentType());
        verify(workspaceMapper, never()).reserveUsedSize(any(), anyLong());
        verify(workspaceMapper, never()).releaseUsedSize(any(), anyLong());
    }

    @Test
    @DisplayName("建目录重名（未删除）→ 409")
    void createFolderDuplicateActive409() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        when(fileMapper.selectList(any())).thenReturn(List.of(row(7L, WS, "/", "docs", 0L, true)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.createFolder(WS, USER, "/", "docs"));
        assertEquals(409, ex.getCode());
        verify(fileMapper, never()).insert(any(WorkspaceFile.class));
    }

    @Test
    @DisplayName("建目录非法名（. / .. / 分隔符 / Windows 非法字符 / 控制字符）→ 400")
    void createFolderInvalidName400() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        for (String bad : List.of(".", "..", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\tb")) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> workspaceService.createFolder(WS, USER, "/", bad), "应当拒绝：" + bad);
            assertEquals(400, ex.getCode(), "应当 400：" + bad);
        }
        verify(fileMapper, never()).insert(any(WorkspaceFile.class));
    }

    @Test
    @DisplayName("建目录父路径不存在 → 404（不静默逐级创建）")
    void createFolderParentMissing404() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        when(fileMapper.selectList(any())).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.createFolder(WS, USER, "/zz-probe/sub", "docs"));
        assertEquals(404, ex.getCode());
        verify(fileMapper, never()).insert(any(WorkspaceFile.class));
    }

    @Test
    @DisplayName("建目录非成员 → 403")
    void createFolderNonMember403() {
        when(memberMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.createFolder(WS, USER, "/", "docs"));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("并发重名：DB 唯一键冲突映射 409")
    void createFolderDuplicateKeyMaps409() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        when(fileMapper.selectList(any())).thenReturn(List.of());
        when(fileMapper.insert(any(WorkspaceFile.class))).thenThrow(new DuplicateKeyException("ux_workspace_files_dir_name"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.createFolder(WS, USER, "/", "docs"));
        assertEquals(409, ex.getCode());
    }

    @Test
    @DisplayName("multipart 上传深路径：逐级补 /x、/x/y 目录后再落文件行，文件 parentPath=/x/y")
    void uploadFileAutoCreatesParentDirectories() throws Exception {
        when(memberMapper.selectOne(any())).thenReturn(member());
        when(entitlementResolver.resolve(USER)).thenReturn(new Entitlements("free", PlanLimits.FREE_DEFAULTS, null));
        when(workspaceMapper.selectById(WS)).thenReturn(workspace(0L, 10_000_000_000L));
        when(fileMapper.selectList(any())).thenReturn(List.of());
        when(fileMapper.insert(any(WorkspaceFile.class))).thenReturn(1);
        when(workspaceMapper.reserveUsedSize(any(), anyLong())).thenReturn(1);

        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());

        var dto = workspaceService.uploadFile(WS, USER, "/x/y", file, null);

        assertEquals("/x/y", dto.getParentPath());
        assertEquals("a.txt", dto.getFileName());
        assertEquals(5L, dto.getFileSize());
        verify(workspaceMapper).reserveUsedSize(WS, 5L);

        ArgumentCaptor<WorkspaceFile> captor = ArgumentCaptor.forClass(WorkspaceFile.class);
        verify(fileMapper, times(3)).insert(captor.capture());
        List<WorkspaceFile> inserted = captor.getAllValues();
        assertEquals("x", inserted.get(0).getFileName());
        assertTrue(Boolean.TRUE.equals(inserted.get(0).getIsDirectory()));
        assertEquals("/", inserted.get(0).getParentPath());
        assertEquals("y", inserted.get(1).getFileName());
        assertTrue(Boolean.TRUE.equals(inserted.get(1).getIsDirectory()));
        assertEquals("/x", inserted.get(1).getParentPath());
        assertEquals("a.txt", inserted.get(2).getFileName());
        assertEquals("/x/y", inserted.get(2).getParentPath());
        assertTrue(Boolean.FALSE.equals(inserted.get(2).getIsDirectory()));
    }

    @Test
    @DisplayName("分片 complete：同样逐级补目录（createUploadedFile 路径）")
    void chunkCompleteAutoCreatesParentDirectories() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        when(fileMapper.selectList(any())).thenReturn(List.of());
        when(fileMapper.insert(any(WorkspaceFile.class))).thenReturn(1);

        var dto = workspaceService.createUploadedFile(WS, USER, "/deep/a", "f.bin", 3L,
                "workspaces/5/uuid/f.bin", "application/octet-stream", null);

        assertEquals("/deep/a", dto.getParentPath());
        ArgumentCaptor<WorkspaceFile> captor = ArgumentCaptor.forClass(WorkspaceFile.class);
        verify(fileMapper, times(3)).insert(captor.capture());
        assertEquals("deep", captor.getAllValues().get(0).getFileName());
        assertEquals("a", captor.getAllValues().get(1).getFileName());
        assertEquals("/deep", captor.getAllValues().get(1).getParentPath());
        assertEquals("f.bin", captor.getAllValues().get(2).getFileName());
        verify(workspaceMapper, never()).reserveUsedSize(any(), anyLong());
    }

    @Test
    @DisplayName("并发补目录：insert 撞唯一键视为已存在，不抛异常，继续补下一级")
    void ensureDirectoryToleratesConcurrentCreation() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        when(fileMapper.selectList(any())).thenReturn(List.of());
        when(fileMapper.insert(any(WorkspaceFile.class)))
                .thenThrow(new DuplicateKeyException("ux_workspace_files_dir_name"))
                .thenReturn(1);

        String path = workspaceService.ensureDirectoryPath(WS, USER, "/c1/c2");

        assertEquals("/c1/c2", path);
        verify(fileMapper, times(2)).insert(any(WorkspaceFile.class));
    }

    @Test
    @DisplayName("非法路径段在上传前被拦截（400），不会把脏路径落库")
    void uploadRejectsInvalidPathSegment() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        when(entitlementResolver.resolve(USER)).thenReturn(new Entitlements("free", PlanLimits.FREE_DEFAULTS, null));
        when(workspaceMapper.selectById(WS)).thenReturn(workspace(0L, 10_000_000_000L));

        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.uploadFile(WS, USER, "/ok/bad:name", file, null));
        assertEquals(400, ex.getCode());
        verify(fileMapper, never()).insert(any(WorkspaceFile.class));
        verify(workspaceMapper, never()).reserveUsedSize(any(), anyLong());
    }

    @Test
    @DisplayName("列表接口对目录也返回 isDirectory=true（客户端据此区分）")
    void listFilesReturnsDirectoryFlag() {
        when(memberMapper.selectOne(any())).thenReturn(member());
        when(fileMapper.selectList(any())).thenReturn(List.of(
                row(7L, WS, "/", "docs", 0L, true),
                row(8L, WS, "/", "a.txt", 12L, false)));

        var files = workspaceService.listFiles(WS, USER, "/");

        assertEquals(2, files.size());
        assertTrue(files.get(0).getIsDirectory());
        assertEquals(DIR_CONTENT_TYPE, files.get(0).getContentType());
        assertTrue(Boolean.FALSE.equals(files.get(1).getIsDirectory()));
        assertEquals(12L, files.get(1).getFileSize());
    }
}
