package com.bond.auth.service;

import com.bond.auth.mapper.AdminLoginBlockMapper;
import com.bond.auth.mapper.AdminLoginLogMapper;
import com.bond.auth.mapper.UserMapper;
import com.bond.common.entity.AdminLoginBlock;
import com.bond.common.entity.User;
import com.bond.common.exception.RateLimitedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * task-144：管理端登录 fail2ban 守卫的单元测试（只测守卫本身，不启动 Spring）。
 */
@ExtendWith(MockitoExtension.class)
class AdminLoginGuardServiceTest {

    @Mock
    private ConfigService configService;
    @Mock
    private UserMapper userMapper;
    @Mock
    private AdminLoginLogMapper logMapper;
    @Mock
    private AdminLoginBlockMapper blockMapper;
    @Mock
    private AdminLoginAuditWriter writer;

    private AdminLoginGuardService service;

    @BeforeEach
    void setUp() {
        service = new AdminLoginGuardService(configService, userMapper, logMapper, blockMapper, writer);
        lenient().when(configService.getBoolean(eq("admin_guard.enabled"), eq(true))).thenReturn(true);
        lenient().when(configService.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(configService.get(anyString(), anyString())).thenReturn("");
    }

    @Test
    @DisplayName("X-Client-App: bond-admin（大小写/空白不敏感）判定为管理端客户端")
    void isAdminClientMatchesHeader() {
        assertTrue(service.isAdminClient("bond-admin"));
        assertTrue(service.isAdminClient("  Bond-Admin  "));
        assertFalse(service.isAdminClient("bond"));
        assertFalse(service.isAdminClient(null));
        assertFalse(service.isAdminClient(" "));
    }

    @Test
    @DisplayName("管理端登录判定：客户端头优先，其次目标账号 is_admin=true；普通用户不算")
    void adminAttemptDetection() {
        User normal = new User();
        normal.setIsAdmin(false);
        User admin = new User();
        admin.setIsAdmin(true);

        assertTrue(service.isAdminAttempt(true, null));
        assertTrue(service.isAdminAttempt(false, admin));
        assertFalse(service.isAdminAttempt(false, normal));
        assertFalse(service.isAdminAttempt(false, null));
    }

    @Test
    @DisplayName("命中生效封禁：写 blocked 日志并抛 429（带 Retry-After 秒数）")
    void enforceActiveBlockThrows429() {
        AdminLoginBlock block = new AdminLoginBlock();
        block.setScope("ip");
        block.setValue("1.2.3.4");
        block.setExpiresAt(OffsetDateTime.now().plusSeconds(300));
        when(blockMapper.selectActive(eq("ip"), eq("1.2.3.4"), any())).thenReturn(block);

        RateLimitedException ex = assertThrows(RateLimitedException.class,
                () -> service.enforceBlockIfActive("1.2.3.4", "admin"));

        assertTrue(ex.getRetryAfterSeconds() >= 295 && ex.getRetryAfterSeconds() <= 300,
                "retryAfter 应约为 300 秒，实际 " + ex.getRetryAfterSeconds());
        ArgumentCaptor<com.bond.common.entity.AdminLoginLog> logCaptor =
                ArgumentCaptor.forClass(com.bond.common.entity.AdminLoginLog.class);
        verify(writer).writeLog(logCaptor.capture());
        assertEquals("blocked", logCaptor.getValue().getOutcome());
        assertEquals("1.2.3.4", logCaptor.getValue().getIp());
    }

    @Test
    @DisplayName("开关关闭时不查库、不拦截")
    void disabledGuardSkipsEnforcement() {
        when(configService.getBoolean(eq("admin_guard.enabled"), eq(true))).thenReturn(false);

        service.enforceBlockIfActive("1.2.3.4", "admin");

        verifyNoInteractions(blockMapper);
    }

    @Test
    @DisplayName("同 IP 窗口内失败达到阈值 → 写入 ip 封禁；账号未达阈值不封")
    void recordFailureBansIpWhenThresholdReached() {
        when(logMapper.countFailuresByIp(eq("1.2.3.4"), any())).thenReturn(5L);
        when(logMapper.countFailuresByUsername(eq("admin"), any())).thenReturn(1L);

        service.recordFailure("bad_credentials", "1.2.3.4", "admin", 7L, "JUnit", "密码错误");

        ArgumentCaptor<AdminLoginBlock> captor = ArgumentCaptor.forClass(AdminLoginBlock.class);
        verify(writer).createBlock(captor.capture());
        AdminLoginBlock created = captor.getValue();
        assertEquals("ip", created.getScope());
        assertEquals("1.2.3.4", created.getValue());
        assertEquals(5, created.getFailCount());
    }

    @Test
    @DisplayName("账号失败达到 user 阈值 → 写 user 封禁（scope=user）")
    void recordFailureBansUserWhenThresholdReached() {
        when(logMapper.countFailuresByIp(eq("5.6.7.8"), any())).thenReturn(1L);
        when(logMapper.countFailuresByUsername(eq("admin"), any())).thenReturn(10L);

        service.recordFailure("bad_credentials", "5.6.7.8", "admin", 7L, "JUnit", "密码错误");

        ArgumentCaptor<AdminLoginBlock> captor = ArgumentCaptor.forClass(AdminLoginBlock.class);
        verify(writer).createBlock(captor.capture());
        assertEquals("user", captor.getValue().getScope());
        assertEquals("admin", captor.getValue().getValue());
    }

    @Test
    @DisplayName("回环地址永不被封（即使失败次数超阈值）")
    void loopbackNeverBlocked() {
        when(logMapper.countFailuresByIp(eq("127.0.0.1"), any())).thenReturn(50L);
        when(logMapper.countFailuresByUsername(eq("admin"), any())).thenReturn(0L);

        service.recordFailure("bad_credentials", "127.0.0.1", "admin", null, null, "x");

        verify(writer, never()).createBlock(any());
    }

    @Test
    @DisplayName("captcha_required 只记录不参与计数/封禁")
    void captchaRequiredNotCounted() {
        service.recordFailure("captcha_required", "1.2.3.4", "admin", null, null, "验证码错误");

        verify(writer, never()).createBlock(any());
        verify(logMapper, never()).countFailuresByIp(anyString(), any());
        verify(writer).writeLog(any());
    }

    @Test
    @DisplayName("存在历史封禁时封禁时长指数退避（900s × 2^recent，上限 24h）")
    void banDurationBacksOffExponentially() {
        when(logMapper.countFailuresByIp(eq("1.2.3.4"), any())).thenReturn(5L);
        when(logMapper.countFailuresByUsername(eq("admin"), any())).thenReturn(0L);
        when(blockMapper.countRecentBlocks(eq("ip"), eq("1.2.3.4"), any())).thenReturn(2);

        service.recordFailure("bad_credentials", "1.2.3.4", "admin", null, null, "x");

        ArgumentCaptor<AdminLoginBlock> captor = ArgumentCaptor.forClass(AdminLoginBlock.class);
        verify(writer).createBlock(captor.capture());
        AdminLoginBlock created = captor.getValue();
        long seconds = Duration.between(created.getBannedAt(), created.getExpiresAt()).getSeconds();
        assertEquals(3600L, seconds);
    }

    @Test
    @DisplayName("成功登录写 success 日志且 attemptNo 为空")
    void recordSuccessWritesLog() {
        service.recordSuccess("1.2.3.4", "admin", 7L, "JUnit");

        ArgumentCaptor<com.bond.common.entity.AdminLoginLog> captor =
                ArgumentCaptor.forClass(com.bond.common.entity.AdminLoginLog.class);
        verify(writer).writeLog(captor.capture());
        com.bond.common.entity.AdminLoginLog row = captor.getValue();
        assertEquals("success", row.getOutcome());
        assertEquals(7L, row.getUserId());
        assertEquals(null, row.getAttemptNo());
    }

    @Test
    @DisplayName("内部地址（网关/nginx 自身）自动豁免封禁，不写 block（task-146）")
    void internalAddressNeverBlocked() {
        when(logMapper.countFailuresByIp(eq("172.18.0.1"), any())).thenReturn(50L);
        when(logMapper.countFailuresByUsername(eq("admin"), any())).thenReturn(0L);

        assertTrue(service.isIpAllowlisted("172.18.0.1"));
        service.recordFailure("bad_credentials", "172.18.0.1", "admin", null, null, "x");

        verify(writer, never()).createBlock(any());
    }

    @Test
    @DisplayName("system_config 白名单命中则永不封禁")
    void configurableAllowlistSkipsBan() {
        when(configService.get(eq("admin_guard.ip_allowlist"), eq(""))).thenReturn("203.0.113.50, 203.0.113.51");
        when(logMapper.countFailuresByIp(eq("203.0.113.50"), any())).thenReturn(50L);
        when(logMapper.countFailuresByUsername(eq("admin"), any())).thenReturn(0L);

        assertTrue(service.isIpAllowlisted("203.0.113.50"));
        assertTrue(service.isIpAllowlisted("203.0.113.51"));
        service.recordFailure("bad_credentials", "203.0.113.50", "admin", null, null, "x");

        verify(writer, never()).createBlock(any());
        assertEquals(List.of("203.0.113.50", "203.0.113.51"), List.copyOf(service.allowlistEntries()));
    }
}
