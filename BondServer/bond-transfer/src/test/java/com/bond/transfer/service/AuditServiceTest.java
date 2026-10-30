package com.bond.transfer.service;

import com.bond.common.dto.AuditLogDTO;
import com.bond.common.dto.PageResult;
import com.bond.common.entity.AuditLog;
import com.bond.transfer.mapper.AuditLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计日志服务单测（任务单 #19）。
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    private static final Long ACTOR_ID = 1L;

    @Mock
    private AuditLogMapper auditLogMapper;

    @InjectMocks
    private AuditService auditService;

    private static AuditLog log(long id) {
        AuditLog log = new AuditLog();
        log.setId(id);
        log.setActorId(ACTOR_ID);
        log.setActorName("管理员");
        log.setAction("user.delete");
        log.setTargetType("user");
        log.setTargetId(9L);
        log.setDetail("{\"method\":\"deleteUser\"}");
        log.setIp("10.0.0.1");
        log.setCreatedAt(OffsetDateTime.now().minusMinutes(3));
        return log;
    }

    @Test
    void recordInsertsWithTruncation() {
        String longAction = "a".repeat(70);
        String longDetail = "d".repeat(5000);
        String longIp = "1".repeat(100);

        auditService.record(ACTOR_ID, "n".repeat(100), longAction, "t".repeat(40), 5L, longDetail, longIp);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insertLog(captor.capture());
        AuditLog saved = captor.getValue();
        assertEquals(ACTOR_ID, saved.getActorId());
        assertEquals(64, saved.getAction().length());
        assertEquals(4000, saved.getDetail().length());
        assertEquals(64, saved.getIp().length());
        assertEquals(64, saved.getActorName().length());
        assertEquals(32, saved.getTargetType().length());
        assertNotNull(saved.getId());
    }

    @Test
    void recordSkipsWhenActorOrActionMissing() {
        auditService.record(null, "管理员", "user.delete", "user", 1L, "{}", "1.1.1.1");
        auditService.record(ACTOR_ID, "管理员", "  ", "user", 1L, "{}", "1.1.1.1");

        verify(auditLogMapper, never()).insertLog(any());
    }

    @Test
    void recordNeverBreaksCallerOnDbFailure() {
        doThrow(new RuntimeException("db down")).when(auditLogMapper).insertLog(any());

        assertDoesNotThrow(() -> auditService.record(ACTOR_ID, "管理员", "user.delete", "user", 1L, "{}", "1.1.1.1"));
    }

    @Test
    void listAppliesFiltersAndClampsPagination() {
        when(auditLogMapper.selectList(any())).thenReturn(List.of(log(1L)));
        when(auditLogMapper.selectCount(any())).thenReturn(2L);

        PageResult<AuditLogDTO> page = auditService.list(0, 999, "user.delete", ACTOR_ID,
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now());

        assertEquals(1, page.getPage());
        assertEquals(100, page.getSize());
        assertEquals(2L, page.getTotal());
        assertEquals(1, page.getItems().size());
        assertEquals("user.delete", page.getItems().get(0).getAction());
        assertNotNull(page.getItems().get(0).getCreatedAtMillis());
    }

    @Test
    void listDefaultsPageAndSize() {
        when(auditLogMapper.selectList(any())).thenReturn(List.of());
        when(auditLogMapper.selectCount(any())).thenReturn(0L);

        PageResult<AuditLogDTO> page = auditService.list(-3, 0, null, null, null, null);

        assertEquals(1, page.getPage());
        assertEquals(1, page.getSize());
        assertEquals(0L, page.getTotal());
        assertEquals(List.of(), page.getItems());
    }

    @Test
    void actionsDelegates() {
        when(auditLogMapper.selectActions()).thenReturn(List.of("announcement.create", "user.delete"));

        assertEquals(List.of("announcement.create", "user.delete"), auditService.actions());
        verify(auditLogMapper).selectActions();
    }

    @Test
    void recordIgnoresUnknownActionCase() {
        auditService.record(ACTOR_ID, null, "announcement.create", null, null, null, null);

        verify(auditLogMapper).insertLog(any());
    }
}
