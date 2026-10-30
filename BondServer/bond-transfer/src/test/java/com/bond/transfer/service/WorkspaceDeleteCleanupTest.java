package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.entity.WorkspaceMember;
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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 删除语义回归（task-37 引入、task-88 改配额口径）。
 *
 * <p>方案 B：删除 = 软删进回收站，物理对象保留；**回收站期间继续占用 used_size**（不释放），
 * 保留期按「工作区 owner 生效档位」写入 {@code trash_expires_at}。</p>
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceDeleteCleanupTest {

    private static final Long USER = 1L;
    private static final Long WS = 5L;

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

    private static WorkspaceMember member(Long workspaceId, Long userId) {
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(2);
        return member;
    }

    private static Workspace workspace(Long id, Long ownerId) {
        Workspace ws = new Workspace();
        ws.setId(id);
        ws.setOwnerId(ownerId);
        ws.setUsedSize(100L);
        ws.setMaxSize(1000L);
        return ws;
    }

    private static WorkspaceFile row(Long id, Long ws, String parent, String name, long size, boolean dir) {
        WorkspaceFile f = new WorkspaceFile();
        f.setId(id);
        f.setWorkspaceId(ws);
        f.setParentPath(parent);
        f.setFileName(name);
        f.setFileSize(size);
        f.setIsDirectory(dir);
        f.setMinioPath("workspaces/5/x/" + name);
        return f;
    }

    private static Entitlements planWithTrashDays(int days) {
        PlanLimits limits = new PlanLimits(
                10_737_418_240L, 5_368_709_120L, 2_147_483_648L,
                2, 1, 3, 5_242_880L, 7, 3, 30, 5, days);
        return new Entitlements("free", limits, null);
    }

    @BeforeAll
    static void initLambdaCache() {
        // 纯单测里没有 MyBatis 启动流程，LambdaUpdateWrapper 需要手动初始化 TableInfo 缓存
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test-lambda-cache");
        TableInfoHelper.initTableInfo(assistant, WorkspaceFile.class);
    }

    @Test
    @DisplayName("删文件 → 软删标记 + 立即释放配额（task-162 起回收站不占配额）")
    void deleteFileIsSoftDeleteAndReleasesQuota() {
        when(memberMapper.selectOne(any())).thenReturn(member(WS, USER));
        when(fileMapper.selectById(9L)).thenReturn(row(9L, WS, "/", "a.pdf", 10L, false));
        when(workspaceMapper.selectById(WS)).thenReturn(workspace(WS, USER));
        when(entitlementResolver.resolve(USER)).thenReturn(planWithTrashDays(30));

        workspaceService.deleteFile(WS, USER, 9L);

        verify(workspaceMapper).releaseUsedSize(WS, 10L);
        verify(fileMapper, never()).deleteById(anyLong());
        verify(objectStorage, never()).deleteObject(any());
        verify(thumbnailService, never()).evictFile(any());
        LambdaUpdateWrapper<WorkspaceFile> wrapper = captureUpdate(1);
        assertTrue(wrapper.getSqlSet().contains("deleted_at"));
        assertTrue(wrapper.getSqlSet().contains("trash_expires_at"));
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(params.containsValue(USER));
        OffsetDateTime expires = firstOffsetDateTime(params);
        assertNotNull(expires);
        assertEquals(30, daysBetweenNowAnd(expires), 1);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("删目录 → 递归软删整棵子树、按文件后代释放配额、每个后代都写 trash_expires_at")
    void deleteDirectorySoftDeletesSubtree() {
        WorkspaceFile docs = row(10L, WS, "/", "docs", 0L, true);
        WorkspaceFile a = row(11L, WS, "/docs", "a.pdf", 10L, false);
        WorkspaceFile b = row(12L, WS, "/docs", "b.pdf", 20L, false);
        when(memberMapper.selectOne(any())).thenReturn(member(WS, USER));
        when(fileMapper.selectById(10L)).thenReturn(docs);
        when(fileMapper.selectList(any())).thenReturn(List.of(a, b));
        when(workspaceMapper.selectById(WS)).thenReturn(workspace(WS, USER));
        when(entitlementResolver.resolve(USER)).thenReturn(planWithTrashDays(30));

        workspaceService.deleteFile(WS, USER, 10L);

        verify(workspaceMapper).releaseUsedSize(WS, 30L);
        verify(fileMapper, times(3)).update(isNull(), any());
        verify(objectStorage, never()).deleteObject(any());

        // task-110：目录与子项共用同一删除时刻与同一保留期 → 所有行的 trash_expires_at 完全一致
        ArgumentCaptor<LambdaUpdateWrapper<WorkspaceFile>> captor =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(fileMapper, times(3)).update(isNull(), captor.capture());
        List<OffsetDateTime> expiries = captor.getAllValues().stream()
                .map(w -> firstOffsetDateTime(w.getParamNameValuePairs()))
                .toList();
        assertEquals(3, expiries.size());
        assertNotNull(expiries.get(0));
        assertEquals(expiries.get(0), expiries.get(1));
        assertEquals(expiries.get(0), expiries.get(2));
    }

    @Test
    @DisplayName("保留期按 owner 档位：plus 档 60 天，写入 trash_expires_at ≈ now+60d")
    void retentionFollowsOwnerPlanTier() {
        when(memberMapper.selectOne(any())).thenReturn(member(WS, USER));
        when(fileMapper.selectById(9L)).thenReturn(row(9L, WS, "/", "a.pdf", 10L, false));
        when(workspaceMapper.selectById(WS)).thenReturn(workspace(WS, USER));
        when(entitlementResolver.resolve(USER)).thenReturn(planWithTrashDays(60));

        workspaceService.deleteFile(WS, USER, 9L);

        LambdaUpdateWrapper<WorkspaceFile> wrapper = captureUpdate(1);
        OffsetDateTime expires = firstOffsetDateTime(wrapper.getParamNameValuePairs());
        assertNotNull(expires);
        assertEquals(60, daysBetweenNowAnd(expires), 1);
    }

    @Test
    @DisplayName("owner 档位解析不到时回退 trash.retention_days（默认 7 天）")
    void retentionFallsBackToDefaultConfig() {
        when(memberMapper.selectOne(any())).thenReturn(member(WS, USER));
        when(fileMapper.selectById(9L)).thenReturn(row(9L, WS, "/", "a.pdf", 10L, false));
        when(workspaceMapper.selectById(WS)).thenReturn(workspace(WS, null));
        when(configService.getInt("trash.retention_days", 7)).thenReturn(7);

        workspaceService.deleteFile(WS, USER, 9L);

        LambdaUpdateWrapper<WorkspaceFile> wrapper = captureUpdate(1);
        OffsetDateTime expires = firstOffsetDateTime(wrapper.getParamNameValuePairs());
        assertNotNull(expires);
        assertEquals(7, daysBetweenNowAnd(expires), 1);
    }

    @Test
    @DisplayName("已在回收站的文件再删 → 404，不重复释放")
    void deleteTrashedFileReturns404() {
        WorkspaceFile trashed = row(9L, WS, "/", "a.pdf", 10L, false);
        trashed.setDeletedAt(OffsetDateTime.now().minusDays(1));
        when(memberMapper.selectOne(any())).thenReturn(member(WS, USER));
        when(fileMapper.selectById(9L)).thenReturn(trashed);

        assertThrows(com.bond.common.exception.BusinessException.class,
                () -> workspaceService.deleteFile(WS, USER, 9L));

        verify(workspaceMapper, never()).releaseUsedSize(any(), anyLong());
        verify(fileMapper, never()).update(isNull(), any());
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<WorkspaceFile> captureUpdate(int calls) {
        ArgumentCaptor<LambdaUpdateWrapper<WorkspaceFile>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(fileMapper, times(calls)).update(isNull(), captor.capture());
        return captor.getValue();
    }

    private static OffsetDateTime firstOffsetDateTime(Map<String, Object> params) {
        return params.values().stream()
                .filter(v -> v instanceof OffsetDateTime)
                .map(v -> (OffsetDateTime) v)
                .findFirst()
                .orElse(null);
    }

    private static long daysBetweenNowAnd(OffsetDateTime time) {
        return Math.abs(Duration.between(OffsetDateTime.now(), time).toDays());
    }
}
