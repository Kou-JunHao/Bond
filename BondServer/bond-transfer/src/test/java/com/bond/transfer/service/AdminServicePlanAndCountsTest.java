package com.bond.transfer.service;

import com.bond.common.dto.AdminUserDTO;
import com.bond.common.dto.WorkspaceDTO;
import com.bond.common.email.EmailGateway;
import com.bond.common.entity.User;
import com.bond.common.entity.Workspace;
import com.bond.transfer.entitlement.PlanCatalog;
import com.bond.transfer.mapper.DeviceMapper;
import com.bond.transfer.mapper.TransferChunkMapper;
import com.bond.transfer.mapper.TransferTaskMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-102 A 段：管理端列表字段。
 *
 * <p>要点不是「能不能返回」，而是**不引入 N+1**：档位名走 PlanCatalog 本地缓存，
 * 成员数/文件数走当前页两条 GROUP BY 聚合（本测试显式断言不逐行 selectCount）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminServicePlanAndCountsTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private DeviceMapper deviceMapper;
    @Mock
    private TransferTaskMapper transferTaskMapper;
    @Mock
    private TransferChunkMapper transferChunkMapper;
    @Mock
    private WorkspaceMapper workspaceMapper;
    @Mock
    private WorkspaceMemberMapper memberMapper;
    @Mock
    private WorkspaceFileMapper fileMapper;
    @Mock
    private ConfigService configService;
    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private EmailGateway emailGateway;
    @Mock
    private PlanCatalog planCatalog;

    @InjectMocks
    private AdminService adminService;

    // ── A1：用户列表档位字段 ──

    @Test
    @DisplayName("用户列表：planCode/planName/planExpiresAt 补齐；plan_code 空回退 free")
    void listUsersFillsPlanFields() {
        User plus = user(1L, "plus");
        plus.setPlanExpiresAt(OffsetDateTime.of(2030, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        User free = user(2L, null);
        when(userMapper.selectList(any())).thenReturn(List.of(plus, free));
        when(planCatalog.name("plus")).thenReturn("Plus");
        when(planCatalog.name("free")).thenReturn("Free");

        List<AdminUserDTO> users = adminService.listUsers(1, 20, null);

        assertEquals("plus", users.get(0).getPlanCode());
        assertEquals("Plus", users.get(0).getPlanName());
        assertEquals(plus.getPlanExpiresAt().toInstant().toEpochMilli(), users.get(0).getPlanExpiresAt());

        assertEquals("free", users.get(1).getPlanCode());
        assertEquals("Free", users.get(1).getPlanName());
        assertNull(users.get(1).getPlanExpiresAt());
    }

    @Test
    @DisplayName("用户详情：同样补齐档位字段")
    void getUserDetailFillsPlanFields() {
        User user = user(9L, "team");
        when(userMapper.selectById(9L)).thenReturn(user);
        when(planCatalog.name("team")).thenReturn("Team");

        AdminUserDTO dto = adminService.getUserDetail(9L);

        assertEquals("team", dto.getPlanCode());
        assertEquals("Team", dto.getPlanName());
    }

    // ── A2：工作区列表成员数/文件数 ──

    @Test
    @DisplayName("工作区列表：两条 GROUP BY 聚合补齐 memberCount/fileCount，不逐行 COUNT")
    void listWorkspacesFillsCountsViaAggregates() {
        when(workspaceMapper.selectList(any())).thenReturn(List.of(workspace(100L), workspace(200L)));
        when(memberMapper.countByWorkspaceIds(any()))
                .thenReturn(List.of(row(100L, "member_count", 3L)));
        when(fileMapper.countActiveFilesByWorkspaceIds(any()))
                .thenReturn(List.of(row(200L, "file_count", 7L)));

        List<WorkspaceDTO> list = adminService.listWorkspaces(1, 20);

        assertEquals(2, list.size());
        assertEquals(3L, list.get(0).getMemberCount());
        assertEquals(0L, list.get(0).getFileCount());
        assertEquals(0L, list.get(1).getMemberCount());
        assertEquals(7L, list.get(1).getFileCount());

        // 证明没有逐行 COUNT（N+1）
        verify(memberMapper, never()).selectCount(any());
        verify(fileMapper, never()).selectCount(any());
    }

    // ── helpers ──

    private static User user(Long id, String planCode) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setNickname("用户" + id);
        user.setStatus(1);
        user.setIsAdmin(false);
        user.setPlanCode(planCode);
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    private static Workspace workspace(Long id) {
        Workspace ws = new Workspace();
        ws.setId(id);
        ws.setName("ws-" + id);
        ws.setOwnerId(1L);
        ws.setMaxSize(1024L);
        ws.setUsedSize(0L);
        ws.setType("team");
        ws.setCreatedAt(LocalDateTime.now());
        return ws;
    }

    private static Map<String, Object> row(Long workspaceId, String column, Long count) {
        return Map.of("workspace_id", workspaceId, column, count);
    }
}
