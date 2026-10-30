package com.bond.transfer.ops;

import com.bond.transfer.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 过期数据清理调度器（task-157）。
 *
 * <p>cron 存在数据库 {@code system_configs}（{@code ops.cleanup.cron}，可热改），因此不用固定
 * {@code @Scheduled(cron=...)} 注解值：这里每分钟 tick 一次，用 {@link CronExpression} 判断
 * 「上次检查时间 → 现在」之间是否应触发一次清理。重启后从当前时间重新起算。</p>
 *
 * <p>时区：Spring {@code @Scheduled} 采用 JVM 默认时区；生产容器为 UTC，默认 cron
 * {@code 0 20 3 * * *} = 每天 03:20 UTC（北京时间 11:20）。本地部署需改配置。</p>
 *
 * <p>单实例部署；跨实例重复触发由 {@link com.bond.transfer.trash.TrashService} 内的 PG advisory lock 兜底。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiryCleanupScheduler {

    private final ExpiryCleanupService expiryCleanupService;
    private final OpsJobRegistry opsJobRegistry;
    private final ConfigService configService;

    /** 上次 tick 的判定时间（内存；重启后重置）。 */
    private OffsetDateTime lastCheckedAt = OffsetDateTime.now();

    /** 应用启动时把「下次执行时间」登记到管理端，避免首次运行前列表里没有这个 job。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        publishNextRun(OffsetDateTime.now());
    }

    @Scheduled(cron = "0 * * * * *")
    public void tick() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime previous = lastCheckedAt;
        lastCheckedAt = now;
        publishNextRun(now);
        CronExpression cron = parseCron();
        if (cron == null) {
            return;
        }
        OffsetDateTime next = cron.next(previous);
        if (next != null && !next.isAfter(now)) {
            expiryCleanupService.scheduledRun();
        }
    }

    private void publishNextRun(OffsetDateTime now) {
        CronExpression cron = parseCron();
        if (cron == null) {
            return;
        }
        OffsetDateTime next = cron.next(now);
        if (next != null) {
            opsJobRegistry.registerScheduled(ExpiryCleanupService.JOB_NAME, next.toString());
        }
    }

    private CronExpression parseCron() {
        String expression = configService.get("ops.cleanup.cron", "0 20 3 * * *");
        try {
            return CronExpression.parse(expression);
        } catch (RuntimeException e) {
            log.warn("invalid ops.cleanup.cron '{}': {}", expression, e.getMessage());
            return null;
        }
    }
}
