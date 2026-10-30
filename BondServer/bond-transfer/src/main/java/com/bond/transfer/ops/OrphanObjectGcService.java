package com.bond.transfer.ops;

import com.bond.transfer.mapper.OpsMapper;
import com.bond.transfer.preview.OfficePreviewService;
import com.bond.transfer.preview.ThumbnailService;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.storage.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 存储卫生 GC（task-74 B）：
 * <ul>
 *   <li><b>孤儿对象扫描</b>：列出桶内 {@code workspaces/**}、{@code files/**} 前缀，与
 *       {@code workspace_files.minio_path}、{@code file_upload_sessions.storage_key} 比对，
 *       输出“疑似孤儿”的数量与总字节。<b>默认只报告不删</b>（{@code gc.orphan.delete=false}），
 *       只有显式开启且对象最后修改时间早于 {@code gc.orphan.min_age_hours}（默认 24h）才允许删除，
 *       避免误删正在写入的对象。</li>
 *   <li><b>预览缓存 TTL</b>：{@code thumbnails/**}、{@code previews/**}（office-pdf）按
 *       {@code preview.cache_ttl_days}（默认 30 天）清理，默认开启。
 *       注意 previews 前缀是 office-pdf 缓存实际使用的对象前缀（任务单里写的 office-pdf/** 并不存在）。</li>
 * </ul>
 *
 * <p>单实例部署，无需分布式锁；删除失败只 warn 并计入 errors，不中断整批。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrphanObjectGcService {

    static final String JOB_NAME = "orphan-object-gc";

    /** 业务对象前缀（与 WorkspaceService/TransferController 的 key 规则保持一致）。 */
    static final List<String> ORPHAN_PREFIXES = List.of("workspaces/", "files/");

    /** 预览缓存前缀：缩略图 + office-pdf（previews/）。 */
    static final List<String> PREVIEW_PREFIXES =
            List.of(ThumbnailService.CACHE_PREFIX, OfficePreviewService.CACHE_PREFIX);

    static final String CONFIG_ENABLED = "gc.orphan.enabled";
    static final String CONFIG_DELETE = "gc.orphan.delete";
    static final String CONFIG_MIN_AGE_HOURS = "gc.orphan.min_age_hours";
    static final String CONFIG_MAX_DELETE = "gc.orphan.max_delete_per_run";
    static final String CONFIG_PREVIEW_ENABLED = "gc.preview.enabled";
    static final String CONFIG_PREVIEW_TTL_DAYS = "preview.cache_ttl_days";

    static final int DEFAULT_MIN_AGE_HOURS = 24;
    static final int DEFAULT_MAX_DELETE_PER_RUN = 500;
    static final int HARD_MAX_DELETE_PER_RUN = 2000;
    static final int DEFAULT_PREVIEW_TTL_DAYS = 30;
    static final int HARD_MAX_PREVIEW_DELETE_PER_RUN = 1000;

    private final ObjectStorage objectStorage;
    private final OpsMapper opsMapper;
    private final ConfigService configService;
    private final OpsJobRegistry opsJobRegistry;

    /** 每天 04:30（JVM 时区）；可用 {@code bond.gc.orphan-cron} 覆盖。 */
    @Scheduled(cron = "${bond.gc.orphan-cron:0 30 4 * * *}")
    public void collectDaily() {
        opsJobRegistry.run(JOB_NAME, () -> collect(Instant.now()));
    }

    /** 执行一轮 GC；公开给定时任务与单测（单测传入固定时钟）。 */
    public OpsJobRegistry.JobResult collect(Instant now) {
        boolean reportEnabled = configService.getBoolean(CONFIG_ENABLED, true);
        List<String> errors = new ArrayList<>();
        long orphanCount = 0;
        long orphanBytes = 0;
        long deletedOrphans = 0;

        if (reportEnabled) {
            boolean deleteEnabled = configService.getBoolean(CONFIG_DELETE, false);
            int minAgeHours = Math.max(0, configService.getInt(CONFIG_MIN_AGE_HOURS, DEFAULT_MIN_AGE_HOURS));
            int maxDelete = Math.min(HARD_MAX_DELETE_PER_RUN,
                    Math.max(1, configService.getInt(CONFIG_MAX_DELETE, DEFAULT_MAX_DELETE_PER_RUN)));
            Instant minAgeCutoff = now.minus(minAgeHours, ChronoUnit.HOURS);
            Set<String> referenced = referencedKeys();

            for (String prefix : ORPHAN_PREFIXES) {
                List<ObjectStorage.StoredObject> objects = safeList(prefix, errors);
                for (ObjectStorage.StoredObject object : objects) {
                    if (object == null || object.key() == null || referenced.contains(object.key())) {
                        continue;
                    }
                    orphanCount++;
                    orphanBytes += Math.max(0L, object.size());
                    if (deletedOrphans >= maxDelete) {
                        continue;
                    }
                    if (deleteEnabled && isOldEnough(object.lastModified(), minAgeCutoff)) {
                        deletedOrphans += tryDelete(object.key(), errors);
                    }
                }
            }
            log.info("orphan object scan: scanned prefixes={}, orphans={}, orphanBytes={}, deleteEnabled={}, deleted={}",
                    ORPHAN_PREFIXES, orphanCount, orphanBytes, deleteEnabled, deletedOrphans);
        } else {
            log.debug("orphan object scan disabled by {}", CONFIG_ENABLED);
        }

        long purgedPreview = purgeExpiredPreviews(now, errors);

        String detail = "orphans=" + orphanCount + ",orphanBytes=" + orphanBytes
                + ",deletedOrphans=" + deletedOrphans + ",purgedPreview=" + purgedPreview;
        log.info("orphan object gc done: {}", detail);
        return new OpsJobRegistry.JobResult(orphanCount + purgedPreview, errors.size(), detail);
    }

    /** 预览缓存 TTL 清理；null mtime 视为过期（与 ThumbnailCacheGcService 口径一致）。 */
    long purgeExpiredPreviews(Instant now, List<String> errors) {
        if (!configService.getBoolean(CONFIG_PREVIEW_ENABLED, true)) {
            return 0L;
        }
        int ttlDays = Math.max(1, configService.getInt(CONFIG_PREVIEW_TTL_DAYS, DEFAULT_PREVIEW_TTL_DAYS));
        Instant cutoff = now.minus(ttlDays, ChronoUnit.DAYS);
        long purged = 0L;
        for (String prefix : PREVIEW_PREFIXES) {
            for (ObjectStorage.StoredObject object : safeList(prefix, errors)) {
                if (purged >= HARD_MAX_PREVIEW_DELETE_PER_RUN) {
                    break;
                }
                if (object == null || object.key() == null) {
                    continue;
                }
                if (!isOldEnough(object.lastModified(), cutoff)) {
                    continue;
                }
                purged += tryDelete(object.key(), errors);
            }
        }
        if (purged > 0) {
            log.info("preview cache gc: purged {} object(s) older than {} day(s)", purged, ttlDays);
        }
        return purged;
    }

    private Set<String> referencedKeys() {
        Set<String> referenced = new HashSet<>();
        addAll(referenced, opsMapper.selectReferencedObjectKeys());
        addAll(referenced, opsMapper.selectUploadSessionKeys());
        return referenced;
    }

    private static void addAll(Set<String> target, List<String> keys) {
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            if (key != null) {
                target.add(key);
            }
        }
    }

    private List<ObjectStorage.StoredObject> safeList(String prefix, List<String> errors) {
        try {
            List<ObjectStorage.StoredObject> objects = objectStorage.listObjects(prefix);
            return objects == null ? List.of() : objects;
        } catch (RuntimeException e) {
            errors.add("list:" + prefix + ":" + e.getMessage());
            log.warn("orphan gc: listObjects({}) failed: {}", prefix, e.getMessage());
            return List.of();
        }
    }

    private long tryDelete(String key, List<String> errors) {
        try {
            // 先打日志再删：出问题时能凭日志还原“删了什么”
            log.info("orphan gc deleting object: key={}", key);
            objectStorage.deleteObject(key);
            return 1L;
        } catch (RuntimeException e) {
            errors.add("delete:" + key + ":" + e.getMessage());
            log.warn("orphan gc: deleteObject({}) failed: {}", key, e.getMessage());
            return 0L;
        }
    }

    private static boolean isOldEnough(Instant lastModified, Instant cutoff) {
        // 对象后端没给 mtime 时按“很旧”处理：只有显式开启删除才会走到这里。
        return lastModified == null || lastModified.isBefore(cutoff);
    }
}
