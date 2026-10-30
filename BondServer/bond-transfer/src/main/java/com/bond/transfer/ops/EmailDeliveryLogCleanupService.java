package com.bond.transfer.ops;

import com.bond.transfer.mapper.EmailDeliveryLogMapper;
import com.bond.transfer.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 邮件投递日志保留期清理（task-80）。
 *
 * <p>默认保留 30 天（{@code email.delivery_log.retention_days}），每天 03:40 跑一次；
 * 通过 {@link OpsJobRegistry} 记录最近一次运行结果，管理端 {@code /api/admin/ops/jobs} 可查。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailDeliveryLogCleanupService {

    public static final String JOB_NAME = "email-delivery-log-cleanup";
    private static final int DEFAULT_RETENTION_DAYS = 30;

    private final EmailDeliveryLogMapper emailDeliveryLogMapper;
    private final ConfigService configService;
    private final OpsJobRegistry opsJobRegistry;

    @Scheduled(cron = "0 40 3 * * *")
    public void cleanupExpired() {
        opsJobRegistry.run(JOB_NAME, () -> {
            int days = configService.getInt("email.delivery_log.retention_days", DEFAULT_RETENTION_DAYS);
            if (days < 1) {
                days = DEFAULT_RETENTION_DAYS;
            }
            OffsetDateTime cutOff = OffsetDateTime.now().minusDays(days);
            int deleted = emailDeliveryLogMapper.deleteOlderThan(cutOff);
            log.info("email delivery log cleanup: retentionDays={}, deleted={}", days, deleted);
            return OpsJobRegistry.JobResult.of(deleted, "retentionDays=" + days + ", deleted=" + deleted);
        });
    }
}
