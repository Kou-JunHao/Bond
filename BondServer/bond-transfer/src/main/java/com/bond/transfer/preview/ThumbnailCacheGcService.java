package com.bond.transfer.preview;

import com.bond.common.entity.WorkspaceFile;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.storage.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 缩略图缓存兜底 GC（每日一次）。
 *
 * <p>清理规则（对象存储 {@code thumbnails/} 前缀，系统数据，不计入用户配额）：</p>
 * <ul>
 *   <li>key 中的 fileId 在 {@code workspace_files} 已不存在 → 孤儿对象，删除；</li>
 *   <li>或最后修改时间超过 {@code thumbnail.cache_days}（默认 30 天）→ 过期对象，删除。</li>
 * </ul>
 *
 * <p>单轮最多删除 {@value #MAX_DELETE_PER_RUN} 个，避免一次扫全部对象；失败只记 warn，
 * 下一轮继续。删除即清理（{@link ThumbnailService#evictFile(Long)}）已覆盖绝大多数场景，
 * 本任务只兜底漏网（进程崩溃、直接改库、历史上传覆盖等）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailCacheGcService {

    /** 单轮删除上限。 */
    static final int MAX_DELETE_PER_RUN = 500;
    static final int DEFAULT_CACHE_DAYS = 30;
    static final String CONFIG_CACHE_DAYS = "thumbnail.cache_days";

    /** 每天 03:30（JVM 时区）执行。 */
    private static final String CRON = "0 30 3 * * *";

    private final ObjectStorage objectStorage;
    private final WorkspaceFileMapper fileMapper;
    private final ConfigService configService;

    @Scheduled(cron = CRON)
    public void collectDaily() {
        collect(Instant.now());
    }

    /**
     * 执行一轮 GC。
     *
     * @return 实际删除的对象数
     */
    int collect(Instant now) {
        int deleted = 0;
        try {
            List<ObjectStorage.StoredObject> objects = objectStorage.listObjects(ThumbnailService.CACHE_PREFIX);
            if (objects == null || objects.isEmpty()) {
                return 0;
            }
            int days = Math.max(1, configService.getInt(CONFIG_CACHE_DAYS, DEFAULT_CACHE_DAYS));
            Instant cutoff = now.minus(days, ChronoUnit.DAYS);

            Set<Long> fileIds = objects.stream()
                    .map(object -> ThumbnailService.parseFileId(object.key()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(HashSet::new));
            Set<Long> existing = new HashSet<>();
            if (!fileIds.isEmpty()) {
                List<WorkspaceFile> rows = fileMapper.selectBatchIds(fileIds);
                if (rows != null) {
                    for (WorkspaceFile row : rows) {
                        if (row != null && row.getId() != null) {
                            existing.add(row.getId());
                        }
                    }
                }
            }

            for (ObjectStorage.StoredObject object : objects) {
                if (deleted >= MAX_DELETE_PER_RUN) {
                    log.info("thumbnail cache GC: reached per-run limit {}, {} object(s) left for next run",
                            MAX_DELETE_PER_RUN, objects.size() - deleted);
                    break;
                }
                Long fileId = ThumbnailService.parseFileId(object.key());
                boolean orphan = fileId != null && !existing.contains(fileId);
                boolean expired = object.lastModified() == null || object.lastModified().isBefore(cutoff);
                if (!orphan && !expired) {
                    continue;
                }
                try {
                    objectStorage.deleteObject(object.key());
                    deleted++;
                } catch (RuntimeException e) {
                    log.warn("thumbnail cache GC: failed to delete {}: {}", object.key(), e.getMessage());
                }
            }
            if (deleted > 0) {
                log.info("thumbnail cache GC: deleted {} object(s), cache_days={}, cutoff={}",
                        deleted, days, cutoff);
            }
        } catch (Exception e) {
            log.warn("thumbnail cache GC failed: {}", e.getMessage());
        }
        return deleted;
    }
}
