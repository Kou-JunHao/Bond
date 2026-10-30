package com.bond.transfer.service;

import com.bond.common.dto.AdminLoginBlockDTO;
import com.bond.common.dto.AdminLoginGuardDTO;
import com.bond.common.dto.AdminLoginLogDTO;
import com.bond.common.dto.AdminLoginLogStatsDTO;
import com.bond.common.dto.PageResult;
import com.bond.common.entity.AdminLoginBlock;
import com.bond.common.entity.AdminLoginLog;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.AdminLoginBlockQueryMapper;
import com.bond.transfer.mapper.AdminLoginLogQueryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-144：管理端登录审计查询/策略服务（bond-transfer 侧）单元测试。
 */
@ExtendWith(MockitoExtension.class)
class AdminLoginAuditAdminServiceTest {

    @Mock
    private AdminLoginLogQueryMapper logMapper;
    @Mock
    private AdminLoginBlockQueryMapper blockMapper;
    @Mock
    private ConfigService configService;

    private AdminLoginAuditAdminService service;

    @BeforeEach
    void setUp() {
        service = new AdminLoginAuditAdminService(logMapper, blockMapper, configService);
        lenient().when(configService.getBoolean(anyString(), eq(true))).thenReturn(true);
        lenient().when(configService.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(configService.get(anyString(), anyString())).thenReturn("");
    }

    private static AdminLoginLog row(String ip, String username, String outcome) {
        AdminLoginLog row = new AdminLoginLog();
        row.setId(1L);
        row.setUserId(7L);
        row.setUsername(username);
        row.setIp(ip);
        row.setOutcome(outcome);
        row.setCreatedAt(OffsetDateTime.now());
        return row;
    }

    // ------------------------------------------------------------------ 日志列表

    @Test
    @DisplayName("日志分页：当前生效封禁的 IP 行标记 blockedNow=true，其它为 false")
    void pageLogsMarksBlockedNow() {
        AdminLoginLog blocked = row("1.2.3.4", "admin", "bad_credentials");
        AdminLoginLog normal = row("9.9.9.9", "admin", "success");
        when(logMapper.selectPage(any(), any(), isNull(), isNull(), isNull(), eq(20), eq(0L)))
                .thenReturn(List.of(blocked, normal));
        when(logMapper.countPage(any(), any(), isNull(), isNull(), isNull())).thenReturn(2L);
        when(logMapper.selectActiveScopes())
                .thenReturn(List.of(Map.of("scope", "ip", "value", "1.2.3.4")));

        PageResult<AdminLoginLogDTO> result = service.pageLogs(1, 20, null, null, null, null, null);

        assertEquals(2, result.getItems().size());
        assertEquals(2L, result.getTotal());
        assertTrue(result.getItems().get(0).isBlockedNow());
        assertFalse(result.getItems().get(1).isBlockedNow());
    }

    @Test
    @DisplayName("分页参数收敛：page<1 → 1；size>100 → 100，offset 由 page 推导")
    void pageLogsClampsPaging() {
        when(logMapper.selectPage(any(), any(), any(), any(), any(), eq(100), eq(0L)))
                .thenReturn(List.of());
        when(logMapper.countPage(any(), any(), any(), any(), any())).thenReturn(0L);
        when(logMapper.selectActiveScopes()).thenReturn(List.of());

        PageResult<AdminLoginLogDTO> result = service.pageLogs(0, 1000, null, null, null, null, null);

        assertEquals(1, result.getPage());
        assertEquals(100, result.getSize());
    }

    @Test
    @DisplayName("非法 outcome / from / to → 400")
    void pageLogsRejectsInvalidFilters() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.pageLogs(1, 20, null, null, "boom", null, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.pageLogs(1, 20, null, null, null, "2026-13-99", null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.pageLogs(1, 20, null, null, null, null, "not-a-date")).getCode());
    }

    @Test
    @DisplayName("合法 from/to（yyyy-MM-dd 与 ISO-8601）透传到 SQL")
    void pageLogsParsesDates() {
        when(logMapper.selectPage(any(), any(), any(), any(), any(), anyInt(), anyLong()))
                .thenReturn(List.of());
        when(logMapper.countPage(any(), any(), any(), any(), any())).thenReturn(0L);
        when(logMapper.selectActiveScopes()).thenReturn(List.of());

        service.pageLogs(1, 20, null, null, null, "2026-10-01", "2026-10-26T12:00:00+08:00");

        verify(logMapper).selectPage(any(), any(), isNull(), isNull(), isNull(), eq(20), eq(0L));
    }

    // ------------------------------------------------------------------ 统计

    @Test
    @DisplayName("统计聚合：total/success/failed、按 outcome、TOP IP/账号、按天")
    void statsAggregates() {
        when(logMapper.totals(any())).thenReturn(Map.of("total", 10L, "success", 7L));
        when(logMapper.countByOutcome(any()))
                .thenReturn(List.of(Map.of("outcome", "bad_credentials", "cnt", 3L)));
        when(logMapper.topIps(any(), eq(10)))
                .thenReturn(List.of(Map.of("value", "1.2.3.4", "cnt", 5L, "failed", 3L)));
        when(logMapper.topUsers(any(), eq(10)))
                .thenReturn(List.of(Map.of("value", "admin", "cnt", 6L, "failed", 2L)));
        when(logMapper.daily(any()))
                .thenReturn(List.of(Map.of("day", "2026-10-26", "success", 4L, "failed", 2L)));

        AdminLoginLogStatsDTO stats = service.stats(7);

        assertEquals(7, stats.getDays());
        assertEquals(10L, stats.getTotal());
        assertEquals(7L, stats.getSuccess());
        assertEquals(3L, stats.getFailed());
        assertEquals(3L, stats.getByOutcome().get("bad_credentials"));
        assertEquals("1.2.3.4", stats.getTopIps().get(0).getIp());
        assertEquals(3L, stats.getTopIps().get(0).getFailed());
        assertEquals("admin", stats.getTopUsers().get(0).getUsername());
        assertEquals("2026-10-26", stats.getDaily().get(0).getDate());
    }

    @Test
    @DisplayName("统计天数收敛：0 → 1，999 → 90")
    void statsClampsDays() {
        when(logMapper.totals(any())).thenReturn(Map.of("total", 0L, "success", 0L));
        when(logMapper.countByOutcome(any())).thenReturn(List.of());
        when(logMapper.topIps(any(), anyInt())).thenReturn(List.of());
        when(logMapper.topUsers(any(), anyInt())).thenReturn(List.of());
        when(logMapper.daily(any())).thenReturn(List.of());

        assertEquals(1, service.stats(0).getDays());
        assertEquals(90, service.stats(999).getDays());
    }

    // ------------------------------------------------------------------ 封禁

    @Test
    @DisplayName("封禁列表：生效行 active=true 且 remainingSeconds ≈ 到期剩余；已解除行 active=false")
    void pageBlocksComputesRemainingSeconds() {
        AdminLoginBlock active = new AdminLoginBlock();
        active.setId(1L);
        active.setScope("ip");
        active.setValue("1.2.3.4");
        active.setBannedAt(OffsetDateTime.now().minusSeconds(600));
        active.setExpiresAt(OffsetDateTime.now().plusSeconds(120));

        AdminLoginBlock lifted = new AdminLoginBlock();
        lifted.setId(2L);
        lifted.setScope("user");
        lifted.setValue("admin");
        lifted.setBannedAt(OffsetDateTime.now().minusSeconds(3600));
        lifted.setExpiresAt(OffsetDateTime.now().minusSeconds(60));
        lifted.setLiftedAt(OffsetDateTime.now().minusSeconds(30));

        when(blockMapper.selectPage(eq(true), isNull(), isNull(), eq(20), eq(0L)))
                .thenReturn(List.of(active, lifted));
        when(blockMapper.countPage(eq(true), isNull(), isNull())).thenReturn(2L);

        PageResult<AdminLoginBlockDTO> result = service.pageBlocks(true, 1, 20, null, null);

        AdminLoginBlockDTO first = result.getItems().get(0);
        assertTrue(first.isActive());
        assertTrue(first.getRemainingSeconds() >= 118 && first.getRemainingSeconds() <= 120,
                "remaining 应约 120 秒，实际 " + first.getRemainingSeconds());
        assertFalse(result.getItems().get(1).isActive());
        assertEquals(0L, result.getItems().get(1).getRemainingSeconds());
    }

    @Test
    @DisplayName("解除封禁：id 非法 400；不存在/已解除 404；成功则落库")
    void liftValidation() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.lift(0L, 7L)).getCode());

        when(blockMapper.lift(eq(9L), eq(7L))).thenReturn(0);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.lift(9L, 7L)).getCode());

        when(blockMapper.lift(eq(10L), eq(7L))).thenReturn(1);
        service.lift(10L, 7L);
        verify(blockMapper).lift(10L, 7L);
    }

    // ------------------------------------------------------------------ 策略

    @Test
    @DisplayName("策略读取：配置值 + 白名单 CSV 解析（忽略空白项）")
    void guardReadsConfigAndParsesAllowlist() {
        when(configService.getBoolean(eq("admin_guard.enabled"), eq(true))).thenReturn(false);
        when(configService.getInt(eq("admin_guard.fail_window_seconds"), anyInt())).thenReturn(120);
        when(configService.getInt(eq("admin_guard.fail_threshold_ip"), anyInt())).thenReturn(6);
        when(configService.getInt(eq("admin_guard.fail_threshold_user"), anyInt())).thenReturn(8);
        when(configService.getInt(eq("admin_guard.ban_seconds"), anyInt())).thenReturn(600);
        when(configService.get(eq("admin_guard.ip_allowlist"), eq(""))).thenReturn(" 10.0.0.1 , ,10.0.0.2 ");

        AdminLoginGuardDTO dto = service.guard();

        assertFalse(dto.isEnabled());
        assertEquals(120, dto.getFailWindowSeconds());
        assertEquals(6, dto.getFailThresholdIp());
        assertEquals(8, dto.getFailThresholdUser());
        assertEquals(600, dto.getBanSeconds());
        assertEquals(List.of("10.0.0.1", "10.0.0.2"), dto.getIpAllowlist());
    }

    @Test
    @DisplayName("策略更新：类型/范围/白名单非法 → 400 且不写库")
    void updateGuardRejectsInvalidValues() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.updateGuard(Map.of("enabled", "yes"), 7L)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.updateGuard(Map.of("failThresholdIp", 0), 7L)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.updateGuard(Map.of("failThresholdIp", 2), 7L)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.updateGuard(Map.of("banSeconds", 999999), 7L)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.updateGuard(Map.of("ipAllowlist", List.of("1.2.3.4", "not-an-ip")), 7L)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.updateGuard(Map.of("failWindowSeconds", "abc"), 7L)).getCode());
        verify(configService, never()).batchUpdate(anyMap(), anyLong());
    }

    @Test
    @DisplayName("策略更新：合法 partial 只写出现的键，并批量落库后回读")
    void updateGuardWritesPartialValues() {
        service.updateGuard(Map.of(
                "enabled", true,
                "failWindowSeconds", 120,
                "ipAllowlist", List.of("10.0.0.1", "10.0.0.2")), 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(configService).batchUpdate(captor.capture(), eq(7L));
        Map<String, String> updates = captor.getValue();
        assertEquals(3, updates.size());
        assertEquals("true", updates.get("admin_guard.enabled"));
        assertEquals("120", updates.get("admin_guard.fail_window_seconds"));
        assertEquals("10.0.0.1,10.0.0.2", updates.get("admin_guard.ip_allowlist"));

        AdminLoginGuardDTO dto = service.guard();
        assertNotNull(dto);
    }

    @Test
    @DisplayName("策略更新：没有可识别字段时不写库")
    void updateGuardEmptyBodyNoWrite() {
        service.updateGuard(Map.of("whatever", 1), 7L);

        verify(configService, never()).batchUpdate(anyMap(), anyLong());
    }
}
