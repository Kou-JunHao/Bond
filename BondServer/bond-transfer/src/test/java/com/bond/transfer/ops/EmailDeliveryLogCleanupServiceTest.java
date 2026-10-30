package com.bond.transfer.ops;

import com.bond.transfer.mapper.EmailDeliveryLogMapper;
import com.bond.transfer.service.ConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-80：投递日志 30 天清理 job（走 OpsJobRegistry 留痕）。
 */
@ExtendWith(MockitoExtension.class)
class EmailDeliveryLogCleanupServiceTest {

    @Mock
    private EmailDeliveryLogMapper emailDeliveryLogMapper;

    @Mock
    private ConfigService configService;

    @Test
    @DisplayName("按保留天数删除，并在 ops job 快照中记 processed")
    void cleanupDeletesAndRecordsJob() {
        when(configService.getInt(eq("email.delivery_log.retention_days"), eq(30))).thenReturn(30);
        when(emailDeliveryLogMapper.deleteOlderThan(any(OffsetDateTime.class))).thenReturn(5);

        OpsJobRegistry registry = new OpsJobRegistry();
        EmailDeliveryLogCleanupService service =
                new EmailDeliveryLogCleanupService(emailDeliveryLogMapper, configService, registry);

        service.cleanupExpired();

        verify(emailDeliveryLogMapper).deleteOlderThan(any(OffsetDateTime.class));
        assertEquals(1, registry.snapshot().size());
        OpsJobRegistry.JobSnapshot snapshot = registry.snapshot().get(0);
        assertEquals(EmailDeliveryLogCleanupService.JOB_NAME, snapshot.name());
        assertTrue(snapshot.status().startsWith("COMPLETED"));
        assertEquals(5, snapshot.processed());
    }

    @Test
    @DisplayName("非法保留天数回退 30 天")
    void invalidRetentionFallsBack() {
        when(configService.getInt(eq("email.delivery_log.retention_days"), eq(30))).thenReturn(0);
        when(emailDeliveryLogMapper.deleteOlderThan(any(OffsetDateTime.class))).thenReturn(0);

        OpsJobRegistry registry = new OpsJobRegistry();
        EmailDeliveryLogCleanupService service =
                new EmailDeliveryLogCleanupService(emailDeliveryLogMapper, configService, registry);

        service.cleanupExpired();

        assertNotNull(registry.snapshot().get(0));
        assertEquals(0, registry.snapshot().get(0).processed());
    }
}
