package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bond.common.dto.StorageQuotaDTO;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.enums.WorkspaceRole;
import com.bond.common.enums.WorkspaceType;
import com.bond.transfer.controller.PersonalFileController;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.Entitlements;
import com.bond.transfer.entitlement.PlanLimits;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.preview.PreviewService;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceInvitationMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * task-18：GET /api/files/quota 的配额解析。
 * 重点：maxBytes 必须来自档位实时解析，而不是 workspaces.max_size 的历史值。
 */
@ExtendWith(MockitoExtension.class)
class PersonalQuotaTest {

    private static final long FREE_STORAGE = PlanLimits.FREE_DEFAULTS.storageBytes(); // 5GB
    private static final long PLUS_STORAGE = 200L * 1024 * 1024 * 1024;                // 200GB

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

    @InjectMocks
    private WorkspaceService workspaceService;

    @Test
    @DisplayName("free 档：maxBytes 按档位实时解析，覆盖 workspaces.max_size 的历史死值")
    void quotaUsesFreshPlanInsteadOfStaleWorkspaceMaxSize() {
        Workspace personal = personalWorkspace(123L, 7L, 123_456_789L, 999L * 1024 * 1024 * 1024);
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(ownerMember());
        when(workspaceMapper.selectById(123L)).thenReturn(personal);
        when(entitlementResolver.resolveFresh(7L)).thenReturn(
                new Entitlements("free", PlanLimits.FREE_DEFAULTS, null));
        when(fileMapper.selectCount(any(Wrapper.class))).thenReturn(16L);

        StorageQuotaDTO quota = workspaceService.getQuota(123L, 7L);

        assertEquals(123_456_789L, quota.getUsedBytes());
        assertEquals(FREE_STORAGE, quota.getMaxBytes(), "must use plan storage_bytes, not workspace.max_size");
        assertEquals(16L, quota.getFileCount());
        assertEquals("free", quota.getPlanCode());
    }

    @Test
    @DisplayName("档位切换后 maxBytes 立即变化（plus = 200GB）")
    void quotaReflectsPlanUpgradeImmediately() {
        Workspace personal = personalWorkspace(123L, 7L, 10L, FREE_STORAGE);
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(ownerMember());
        when(workspaceMapper.selectById(123L)).thenReturn(personal);
        PlanLimits plus = new PlanLimits(200L * 1024 * 1024 * 1024, PLUS_STORAGE,
                10L * 1024 * 1024 * 1024, 4, 5, 10, 20L * 1024 * 1024, 30, 3, 200, 20, 60);
        when(entitlementResolver.resolveFresh(7L)).thenReturn(new Entitlements("plus", plus, OffsetDateTime.now().plusMonths(1)));
        when(fileMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        StorageQuotaDTO quota = workspaceService.getQuota(123L, 7L);

        assertEquals(PLUS_STORAGE, quota.getMaxBytes());
        assertEquals("plus", quota.getPlanCode());
        assertEquals(0L, quota.getFileCount());
    }

    @Test
    @DisplayName("无个人空间时控制器先由 PersonalSpaceService 自动创建（懒创建），再返回 free 档配额")
    void controllerCreatesPersonalSpaceThenReturnsQuota() {
        PersonalSpaceService personalSpaceService = org.mockito.Mockito.mock(PersonalSpaceService.class);
        WorkspaceService service = org.mockito.Mockito.mock(WorkspaceService.class);
        when(personalSpaceService.resolveWorkspaceId(7L)).thenReturn(123L);
        StorageQuotaDTO expected = new StorageQuotaDTO();
        expected.setUsedBytes(0L);
        expected.setMaxBytes(FREE_STORAGE);
        expected.setFileCount(0L);
        expected.setPlanCode("free");
        when(service.getQuota(123L, 7L)).thenReturn(expected);

        PersonalFileController controller = new PersonalFileController(personalSpaceService, service,
                org.mockito.Mockito.mock(PreviewService.class),
                org.mockito.Mockito.mock(com.bond.transfer.service.FileSearchService.class));
        var response = controller.quota(7L);

        assertNotNull(response);
        assertEquals(200, response.getCode());
        assertEquals(FREE_STORAGE, response.getData().getMaxBytes());
        assertEquals("free", response.getData().getPlanCode());
    }

    private static Workspace personalWorkspace(Long id, Long ownerId, long used, long staleMax) {
        Workspace ws = new Workspace();
        ws.setId(id);
        ws.setOwnerId(ownerId);
        ws.setType(WorkspaceType.PERSONAL.getValue());
        ws.setUsedSize(used);
        ws.setMaxSize(staleMax);
        ws.setName("个人空间");
        return ws;
    }

    private static WorkspaceMember ownerMember() {
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(123L);
        member.setUserId(7L);
        member.setRole(WorkspaceRole.OWNER.getValue());
        return member;
    }
}
