package com.bond.transfer.trash;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bond.common.entity.Document;
import com.bond.common.entity.ShareLink;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.DocumentMapper;
import com.bond.transfer.mapper.OpsMapper;
import com.bond.transfer.mapper.ShareLinkMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.ops.OpsJobRegistry;
import com.bond.transfer.preview.ThumbnailService;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceService;
import com.bond.transfer.storage.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 回收站：软删后的列表 / 恢复 / 彻底删除 / 清空 / 每日过期清理。
 *
 * <p>语义（V6 + task-162 调整）：</p>
 * <ul>
 *   <li>删除 = 软删（deleted_at 非空），物理对象保留；**used_size 在删除时已原子释放**
 *       （GREATEST 防下溢），因此回收站期间**不再占用**配额，物理清理也不重复扣减。</li>
 *   <li>保留期按**工作区 owner** 的生效档位写入 {@code trash_expires_at}（写入方在
 *       WorkspaceService/DocumentService，归档时取 owner 非操作者）；统一策略见
 *       {@link TrashRetentionPolicy}：档位 {@code trash_retention_days} &gt; 全局配置
 *       {@code trash.retention_days}（默认 7）&gt; 硬编码 7；0=不限期。</li>
 *   <li>恢复 = 清空回收站三列，按原路径回位；原父目录已不在时回根目录；同名冲突自动
 *       「已恢复」改名（目录名同样处理）。恢复时按子树字节**重新原子预占** used_size，
 *       空间不足 → 该条 413（批次里记为 failed，不中断其它条）。</li>
 *   <li>彻底删除 = 物理删对象（幂等，缺失算成功）+ 删缩略图 + 删分享行 + 删行
 *       （**不再释放 used_size**，删除时已释放；见 task-162）；对象按 minio_path 引用计数
 *       判断，避免秒传共享 key 被误删。</li>
 *   <li>过期清理 = 由 {@code ExpiryCleanupService}（task-157）每日统一调度，多分类合并在一个
 *       {@code ops job} 里；单轮 ≤{@code trash.purge_batch}（默认 200）条、最多
 *       {@code trash.purge_max_rounds}（默认 20）轮；统计 scanned/purged/blobDeleted/blobMissing/failed，
 *       单条失败 WARN 跳过。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrashService {

    private final WorkspaceFileMapper fileMapper;
    private final WorkspaceMapper workspaceMapper;
    private final ShareLinkMapper shareLinkMapper;
    private final DocumentMapper documentMapper;
    private final ObjectStorage objectStorage;
    private final WorkspaceService workspaceService;
    private final PersonalSpaceService personalSpaceService;
    private final ConfigService configService;
    private final ThumbnailService thumbnailService;
    private final OpsMapper opsMapper;
    private final OpsJobRegistry opsJobRegistry;
    private final PlatformTransactionManager transactionManager;

    private static final int MAX_BATCH = 500;
    private static final int DEFAULT_MAX_ROUNDS = 20;
    private static final int MAX_ROUNDS = 100;

    /** 过期清理任务的 PG advisory lock 键（单实例部署，加锁是防手工触发的重入）。 */
    private static final long PURGE_LOCK_KEY = 0x426F6E64_50726731L;

    /** 彻底删除一棵子树的结果：行数 + 释放的对象字节 + 物理对象命中统计。 */
    private record PurgeOutcome(int rows, long bytes, long blobDeleted, long blobMissing) {
        static final PurgeOutcome EMPTY = new PurgeOutcome(0, 0L, 0L, 0L);
    }

    // ── 列表 ──────────────────────────────────────────────────────────────

    /**
     * 回收站列表（顶层条目）。workspaceId 不传 → 个人空间。
     */
    public List<TrashItem> list(Long userId, Long workspaceId) {
        Long wsId = resolveWorkspaceId(userId, workspaceId);
        List<TrashItem> items = new ArrayList<>();
        List<WorkspaceFile> deleted = fileMapper.selectList(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, wsId)
                .isNotNull(WorkspaceFile::getDeletedAt)
                .orderByDesc(WorkspaceFile::getDeletedAt));
        if (!deleted.isEmpty()) {
            Set<String> deletedDirPaths = new LinkedHashSet<>();
            for (WorkspaceFile f : deleted) {
                if (Boolean.TRUE.equals(f.getIsDirectory())) {
                    deletedDirPaths.add(FileTree.originalPath(f));
                }
            }
            for (WorkspaceFile row : deleted) {
                String parent = FileTree.normalizePath(row.getParentPath());
                if (isUnderDeletedDir(parent, deletedDirPaths)) {
                    continue; // 子树内子项不单独展示
                }
                items.add(toItem(row, deleted));
            }
        }
        // 文档与文件共用一个回收站：文档行 kind=document、标题当文件名、正文 UTF-8 字节当体积
        for (Document doc : documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getWorkspaceId, wsId)
                .isNotNull(Document::getDeletedAt)
                .orderByDesc(Document::getDeletedAt))) {
            items.add(new TrashItem(doc.getId(), doc.getTitle(),
                    doc.getSizeBytes() == null ? 0L : doc.getSizeBytes(), false, "document",
                    FileTree.joinPath(doc.getParentPath(), doc.getTitle()),
                    toMillis(doc.getDeletedAt()), toMillis(doc.getTrashExpiresAt()), 0L));
        }
        return items;
    }

    private boolean isUnderDeletedDir(String parent, Set<String> deletedDirPaths) {
        for (String dir : deletedDirPaths) {
            if (parent.equals(dir) || parent.startsWith(dir + "/")) {
                return true;
            }
        }
        return false;
    }

    private TrashItem toItem(WorkspaceFile row, List<WorkspaceFile> deleted) {
        boolean dir = Boolean.TRUE.equals(row.getIsDirectory());
        long itemCount = 0;
        if (dir) {
            String base = FileTree.originalPath(row);
            itemCount = deleted.stream()
                    .filter(f -> !Objects.equals(f.getId(), row.getId()) && FileTree.isUnder(f, base))
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDirectory()))
                    .count();
        }
        return new TrashItem(
                row.getId(),
                row.getFileName(),
                row.getFileSize() == null ? 0L : row.getFileSize(),
                dir,
                dir ? "directory" : "file",
                FileTree.originalPath(row),
                toMillis(row.getDeletedAt()),
                toMillis(row.getTrashExpiresAt()),
                itemCount);
    }

    // ── 恢复 ──────────────────────────────────────────────────────────────

    public TrashBatchResult restore(Long userId, List<Long> ids) {
        List<Long> distinct = distinctIds(ids);
        List<Long> succeeded = new ArrayList<>();
        List<TrashBatchResult.Failed> failed = new ArrayList<>();
        for (Long id : distinct) {
            try {
                restoreOne(userId, id);
                succeeded.add(id);
            } catch (BusinessException e) {
                failed.add(new TrashBatchResult.Failed(id, e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("restore failed: id={}, err={}", id, e.toString());
                failed.add(new TrashBatchResult.Failed(id, "恢复失败"));
            }
        }
        return new TrashBatchResult(succeeded, failed);
    }

    private void restoreOne(Long userId, Long id) {
        WorkspaceFile row = fileMapper.selectById(id);
        if (row == null || row.getDeletedAt() == null) {
            restoreDocument(userId, id);
            return;
        }
        Long wsId = row.getWorkspaceId();
        workspaceService.getWorkspace(wsId, userId); // 成员校验（403/404）

        boolean dir = Boolean.TRUE.equals(row.getIsDirectory());
        List<WorkspaceFile> subtree = deletedSubtree(row, true);
        long totalSize = subtree.stream()
                .filter(f -> !Boolean.TRUE.equals(f.getIsDirectory()))
                .mapToLong(f -> f.getFileSize() == null ? 0L : f.getFileSize())
                .sum();

        String targetParent = activeParentOrRoot(wsId, row.getParentPath());
        String newName = row.getFileName();
        boolean renamed = activeNameExists(wsId, targetParent, newName, dir);
        if (renamed) {
            newName = uniqueRestoredName(wsId, targetParent, row.getFileName(), dir);
        }

        // task-162：删除时已释放配额，恢复必须重新原子预占；空间不足 → 413（批次记为 failed）
        workspaceService.reserveSpace(wsId, totalSize);
        if (dir) {
            restoreDirectoryRows(row, subtree, targetParent, newName);
        } else {
            restoreFileRow(row, targetParent, newName, renamed);
        }
        log.info("restored from trash: id={}, workspace={}, renamed={}, bytesReserved={}",
                id, wsId, renamed, totalSize);
    }

    /** 恢复回收站里的文档：清空软删三列、父目录不存在回根目录、按正文字节重新占用配额。 */
    private void restoreDocument(Long userId, Long id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null || doc.getDeletedAt() == null) {
            throw new BusinessException(404, "回收站项不存在");
        }
        Long wsId = doc.getWorkspaceId();
        workspaceService.getWorkspace(wsId, userId); // 成员校验（403/404）

        String targetParent = activeParentOrRoot(wsId, doc.getParentPath());
        String targetTitle = uniqueRestoredDocTitle(wsId, targetParent, doc.getTitle());
        long size = doc.getSizeBytes() == null ? 0L : doc.getSizeBytes();
        // task-162：删除时已释放配额，恢复按正文字节重新原子预占（不足 413）
        workspaceService.reserveSpace(wsId, size);
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, id)
                .set(Document::getParentPath, targetParent)
                .set(Document::getTitle, targetTitle)
                .set(Document::getDeletedAt, null)
                .set(Document::getTrashExpiresAt, null));
        log.info("restored document from trash: id={}, workspace={}, bytesReserved={}", id, wsId, size);
    }

    /** 文档恢复时目录内标题唯一：冲突追加「(2)」「(3)」…（与创建口径一致）。 */
    private String uniqueRestoredDocTitle(Long wsId, String parent, String title) {
        String base = title == null || title.isBlank() ? "未命名文档" : title;
        String candidate = base;
        int n = 2;
        while (activeDocumentTitleExists(wsId, parent, candidate)) {
            candidate = base + " (" + n + ")";
            n++;
        }
        return candidate;
    }

    private boolean activeDocumentTitleExists(Long wsId, String parent, String title) {
        Long count = documentMapper.selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getWorkspaceId, wsId)
                .eq(Document::getParentPath, parent)
                .eq(Document::getTitle, title)
                .isNull(Document::getDeletedAt));
        return count != null && count > 0;
    }

    private void restoreFileRow(WorkspaceFile row, String targetParent, String newName, boolean renamed) {
        String newKey = row.getMinioPath();
        if (renamed) {
            newKey = moveObjectKey(row, newName);
        }
        fileMapper.update(null, new LambdaUpdateWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getId, row.getId())
                .set(WorkspaceFile::getParentPath, targetParent)
                .set(WorkspaceFile::getFileName, newName)
                .set(WorkspaceFile::getMinioPath, newKey)
                .set(WorkspaceFile::getDeletedAt, null)
                .set(WorkspaceFile::getDeletedBy, null)
                .set(WorkspaceFile::getTrashExpiresAt, null));
    }

    private void restoreDirectoryRows(WorkspaceFile root, List<WorkspaceFile> subtree,
                                      String targetParent, String newName) {
        String oldBase = FileTree.originalPath(root);
        String newBase = FileTree.joinPath(targetParent, newName);
        OffsetDateTime now = OffsetDateTime.now();
        for (WorkspaceFile child : subtree) {
            boolean isRoot = Objects.equals(child.getId(), root.getId());
            String childParent;
            String childName;
            if (isRoot) {
                childParent = targetParent;
                childName = newName;
            } else {
                String parent = FileTree.normalizePath(child.getParentPath());
                if (parent.equals(oldBase)) {
                    childParent = newBase;
                } else if (parent.startsWith(oldBase + "/")) {
                    childParent = newBase + parent.substring(oldBase.length());
                } else {
                    childParent = parent; // 理论不可达：路径不在子树内
                }
                childName = child.getFileName();
            }
            LambdaUpdateWrapper<WorkspaceFile> update = new LambdaUpdateWrapper<WorkspaceFile>()
                    .eq(WorkspaceFile::getId, child.getId())
                    .set(WorkspaceFile::getParentPath, childParent)
                    .set(WorkspaceFile::getFileName, childName)
                    .set(WorkspaceFile::getDeletedAt, null)
                    .set(WorkspaceFile::getDeletedBy, null)
                    .set(WorkspaceFile::getTrashExpiresAt, null);
            if (isRoot) {
                // 目录本身没有物理对象；如曾改名过也无需移动 key
                update.set(WorkspaceFile::getMinioPath, child.getMinioPath());
            }
            fileMapper.update(null, update);
        }
        log.debug("restored directory subtree: base={}, rows={}, at={}", oldBase, subtree.size(), now);
    }

    /** 同名冲突时改名：{@code a.pdf} → {@code a (已恢复).pdf}；仍冲突则追加序号。 */
    private String uniqueRestoredName(Long wsId, String parent, String name, boolean dir) {
        String safeName = name == null || name.isBlank() ? "未命名" : name;
        String candidate = dir ? safeName + " (已恢复)" : withSuffix(safeName, " (已恢复)");
        int n = 2;
        while (activeNameExists(wsId, parent, candidate, dir)) {
            candidate = dir ? safeName + " (已恢复" + n + ")" : withSuffix(safeName, " (已恢复" + n + ")");
            n++;
        }
        return candidate;
    }

    private String withSuffix(String name, String suffix) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name + suffix;
        }
        return name.substring(0, dot) + suffix + name.substring(dot);
    }

    private boolean activeNameExists(Long wsId, String parent, String name, boolean dir) {
        Long count = fileMapper.selectCount(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, wsId)
                .eq(WorkspaceFile::getParentPath, parent)
                .eq(WorkspaceFile::getFileName, name)
                .eq(WorkspaceFile::getIsDirectory, dir)
                .isNull(WorkspaceFile::getDeletedAt));
        return count != null && count > 0;
    }

    /** 原父目录仍活跃则回原位，否则回根目录 "/"。 */
    private String activeParentOrRoot(Long wsId, String parentPath) {
        String parent = FileTree.normalizePath(parentPath);
        if ("/".equals(parent)) {
            return "/";
        }
        if (findActiveDirByPath(wsId, parent) != null) {
            return parent;
        }
        return "/";
    }

    private WorkspaceFile findActiveDirByPath(Long wsId, String path) {
        int idx = path.lastIndexOf('/');
        String parent = idx <= 0 ? "/" : path.substring(0, idx);
        String name = path.substring(idx + 1);
        return fileMapper.selectOne(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, wsId)
                .eq(WorkspaceFile::getParentPath, parent)
                .eq(WorkspaceFile::getFileName, name)
                .eq(WorkspaceFile::getIsDirectory, true)
                .isNull(WorkspaceFile::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** 恢复改名时复制到新 key 再删旧 key（保持 uuid 前缀不变，便于运维排查）。 */
    private String moveObjectKey(WorkspaceFile row, String newName) {
        String oldKey = row.getMinioPath();
        int idx = oldKey == null ? -1 : oldKey.lastIndexOf('/');
        String newKey = idx >= 0 ? oldKey.substring(0, idx + 1) + newName : newName;
        if (oldKey == null || newKey.equals(oldKey)) {
            return oldKey;
        }
        try (InputStream in = objectStorage.getObject(oldKey)) {
            long size = row.getFileSize() == null ? 0L : row.getFileSize();
            objectStorage.putObject(newKey, in, size, row.getContentType());
        } catch (RuntimeException e) {
            throw new BusinessException(500, "恢复改名失败");
        } catch (Exception e) {
            throw new BusinessException(500, "恢复改名失败");
        }
        try {
            objectStorage.deleteObject(oldKey);
        } catch (RuntimeException e) {
            log.warn("old object cleanup failed after rename: key={}, err={}", oldKey, e.getMessage());
        }
        return newKey;
    }

    // ── 彻底删除 / 清空 ──────────────────────────────────────────────────

    public TrashBatchResult purge(Long userId, List<Long> ids) {
        List<Long> distinct = distinctIds(ids);
        List<Long> succeeded = new ArrayList<>();
        List<TrashBatchResult.Failed> failed = new ArrayList<>();
        for (Long id : distinct) {
            try {
                WorkspaceFile row = fileMapper.selectById(id);
                if (row != null && row.getDeletedAt() != null) {
                    workspaceService.getWorkspace(row.getWorkspaceId(), userId);
                    purgeRoot(row);
                } else {
                    purgeDocument(userId, id);
                }
                succeeded.add(id);
            } catch (BusinessException e) {
                failed.add(new TrashBatchResult.Failed(id, e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("purge failed: id={}, err={}", id, e.toString());
                failed.add(new TrashBatchResult.Failed(id, "彻底删除失败"));
            }
        }
        return new TrashBatchResult(succeeded, failed);
    }

    /** 清空某空间回收站，返回删除的行数（配额按实际删除字节原子释放）。 */
    public int empty(Long userId, Long workspaceId) {
        Long wsId = resolveWorkspaceId(userId, workspaceId);
        List<WorkspaceFile> deleted = fileMapper.selectList(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, wsId)
                .isNotNull(WorkspaceFile::getDeletedAt));
        int purged = 0;
        long bytes = 0L;
        if (!deleted.isEmpty()) {
            for (WorkspaceFile root : topLevelRows(deleted)) {
                PurgeOutcome outcome = purgeRoot(root);
                purged += outcome.rows();
                bytes += outcome.bytes();
            }
        }
        List<Document> docs = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getWorkspaceId, wsId)
                .isNotNull(Document::getDeletedAt));
        for (Document doc : docs) {
            bytes += purgeDocumentRow(doc);
            purged++;
        }
        log.info("trash emptied: workspace={}, rows={}, releasedBytes={}", wsId, purged, bytes);
        return purged;
    }

    /**
     * 由 {@code ExpiryCleanupService}（task-157）每日统一调度；本方法单独调用时结果记入
     * {@link OpsJobRegistry}（管理端可观测），保留给手动触发/测试使用。
     */
    public void purgeExpired() {
        opsJobRegistry.run("trash-purge", this::cleanupExpired);
    }

    /**
     * 执行过期清理（task-157 起由 {@code ExpiryCleanupService} 每日统一调度；历史入口保留）。
     *
     * <p>多轮批处理，单轮 ≤{@code trash.purge_batch}（默认 200）条，
     * 最多 {@code trash.purge_max_rounds}（默认 20）轮；单条失败 WARN 跳过。</p>
     *
     * <p>保留期由 V6 的 {@code trash_expires_at} 决定（按 owner 档位写入，0=不限期 → 写入 NULL，
     * 查询用 {@code IS NOT NULL} 过滤，故不限期项永不清理；升级档位不回溯），本任务只负责
     * 到点后的物理回收；每棵子树/每个文档各自独立事务（行删除 + used_size 释放原子）。</p>
     */
    public OpsJobRegistry.JobResult cleanupExpired() {
        if (!opsMapper.tryAdvisoryLock(PURGE_LOCK_KEY)) {
            log.info("trash purge skipped: advisory lock is held by another run");
            return OpsJobRegistry.JobResult.of(0, "skipped:lock-held");
        }
        long purgedRows = 0L;
        long releasedBytes = 0L;
        long blobDeleted = 0L;
        long blobMissing = 0L;
        long failed = 0L;
        int roots = 0;
        int docs = 0;
        int rounds = 0;
        int batch = Math.min(MAX_BATCH, Math.max(1, configService.getInt("trash.purge_batch", 200)));
        int maxRounds = Math.min(MAX_ROUNDS,
                Math.max(1, configService.getInt("trash.purge_max_rounds", DEFAULT_MAX_ROUNDS)));
        try {
            for (rounds = 0; rounds < maxRounds; rounds++) {
                OffsetDateTime now = OffsetDateTime.now();
                List<WorkspaceFile> expired = fileMapper.selectList(new LambdaQueryWrapper<WorkspaceFile>()
                        .isNotNull(WorkspaceFile::getTrashExpiresAt)
                        .lt(WorkspaceFile::getTrashExpiresAt, now)
                        .orderByAsc(WorkspaceFile::getTrashExpiresAt)
                        .last("LIMIT " + batch));
                List<Document> expiredDocs = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                        .isNotNull(Document::getTrashExpiresAt)
                        .lt(Document::getTrashExpiresAt, now)
                        .orderByAsc(Document::getTrashExpiresAt)
                        .last("LIMIT " + batch));
                if (expired.isEmpty() && expiredDocs.isEmpty()) {
                    break;
                }
                for (WorkspaceFile root : topLevelRows(expired)) {
                    roots++;
                    try {
                        PurgeOutcome outcome = purgeRoot(root);
                        purgedRows += outcome.rows();
                        releasedBytes += outcome.bytes();
                        blobDeleted += outcome.blobDeleted();
                        blobMissing += outcome.blobMissing();
                    } catch (RuntimeException e) {
                        failed++;
                        log.warn("expired trash purge failed: id={}, err={}", root.getId(), e.toString());
                    }
                }
                for (Document doc : expiredDocs) {
                    try {
                        releasedBytes += purgeDocumentRow(doc);
                        purgedRows++;
                        docs++;
                    } catch (RuntimeException e) {
                        failed++;
                        log.warn("expired document purge failed: id={}, err={}", doc.getId(), e.toString());
                    }
                }
            }
            log.info("trash purge done: rounds={}, roots={}, docs={}, purgedRows={}, releasedBytes={}, "
                            + "blobDeleted={}, blobMissing={}, failed={}",
                    rounds, roots, docs, purgedRows, releasedBytes, blobDeleted, blobMissing, failed);
        } finally {
            try {
                opsMapper.advisoryUnlock(PURGE_LOCK_KEY);
            } catch (RuntimeException e) {
                // 连接被回收时锁会随会话关闭释放；这里失败不影响主流程
                log.debug("trash purge advisory unlock failed: {}", e.getMessage());
            }
        }
        return new OpsJobRegistry.JobResult(purgedRows, failed,
                "rounds=" + rounds + ",roots=" + roots + ",docs=" + docs
                        + ",releasedBytes=" + releasedBytes
                        + ",blobDeleted=" + blobDeleted + ",blobMissing=" + blobMissing);
    }

    /**
     * 彻底删除一棵子树（root + 其回收站后代）：物理对象 → 分享行/行数据 + 原子释放配额。
     *
     * <p>顺序：先删 blob（幂等，缺失算成功；失败只 warn，由每日 GC 兜底）→ 行删除与
     * {@code used_size} 释放放在同一事务（{@code UPDATE ... GREATEST(...)}，不读改写）。
     * 已不存在的行会被安全跳过。</p>
     */
    private PurgeOutcome purgeRoot(WorkspaceFile root) {
        List<WorkspaceFile> subtree = deletedSubtree(root, false);
        Set<String> keys = new LinkedHashSet<>();
        List<Long> fileIds = new ArrayList<>();
        long bytes = 0L;
        Long wsId = root.getWorkspaceId();
        for (WorkspaceFile f : subtree) {
            if (Boolean.TRUE.equals(f.getIsDirectory())) {
                continue;
            }
            fileIds.add(f.getId());
            bytes += f.getFileSize() == null ? 0L : f.getFileSize();
            if (f.getMinioPath() != null) {
                keys.add(f.getMinioPath());
            }
        }
        // 物理对象：按 minio_path 全表引用计数，避免秒传共享 key 被误删
        long blobDeleted = 0L;
        long blobMissing = 0L;
        for (String key : keys) {
            long totalRefs = fileMapper.selectCount(new LambdaQueryWrapper<WorkspaceFile>()
                    .eq(WorkspaceFile::getMinioPath, key));
            long subtreeRefs = subtree.stream()
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDirectory()))
                    .filter(f -> key.equals(f.getMinioPath()))
                    .count();
            if (totalRefs - subtreeRefs > 0) {
                continue;
            }
            boolean exists;
            try {
                exists = objectStorage.objectExists(key);
            } catch (RuntimeException e) {
                log.warn("object exists check failed (ignored, gc will retry): key={}, err={}",
                        key, e.getMessage());
                continue;
            }
            if (!exists) {
                blobMissing++;
                continue;
            }
            try {
                objectStorage.deleteObject(key);
                blobDeleted++;
            } catch (RuntimeException e) {
                log.warn("object delete failed (ignored, gc will retry): key={}, err={}", key, e.getMessage());
            }
        }
        List<Long> rowIds = subtree.stream().map(WorkspaceFile::getId).toList();
        inTransaction(() -> {
            // 分享行：彻底删除后不应残留 410
            if (!fileIds.isEmpty()) {
                shareLinkMapper.delete(new LambdaQueryWrapper<ShareLink>()
                        .in(ShareLink::getFileId, fileIds));
            }
            if (!rowIds.isEmpty()) {
                fileMapper.deleteBatchIds(rowIds);
            }
            // task-162：used_size 已在软删时释放，这里**不再释放**（避免重复扣减）
        });
        // 缩略图缓存回收（失败只 warn，由每日 GC 兜底）
        for (Long fileId : fileIds) {
            try {
                thumbnailService.evictFile(fileId);
            } catch (RuntimeException e) {
                log.warn("thumbnail evict failed: fileId={}, err={}", fileId, e.getMessage());
            }
        }
        log.info("trash purged: rootId={}, rows={}, bytes={} (quota already released on delete, task-162), objects={}, blobDeleted={}, blobMissing={}",
                root.getId(), subtree.size(), bytes, keys.size(), blobDeleted, blobMissing);
        return new PurgeOutcome(subtree.size(), bytes, blobDeleted, blobMissing);
    }

    /** 彻底删除回收站里的文档（历史版本经 FK CASCADE 一并删除；文档无物理对象）。 */
    private void purgeDocument(Long userId, Long id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null || doc.getDeletedAt() == null) {
            throw new BusinessException(404, "回收站项不存在");
        }
        workspaceService.getWorkspace(doc.getWorkspaceId(), userId);
        long bytes = purgeDocumentRow(doc);
        log.info("trash purged document: id={}, workspace={}, bytes={} (quota already released on delete, task-162)",
                id, doc.getWorkspaceId(), bytes);
    }

    /**
     * 删文档行（历史版本经 FK CASCADE 一并删除）；返回该文档字节数（仅用于日志/统计）。
     * task-162：used_size 已在软删时释放，这里**不再释放**，避免重复扣减。
     */
    private long purgeDocumentRow(Document doc) {
        long size = doc.getSizeBytes() == null ? 0L : doc.getSizeBytes();
        inTransaction(() -> documentMapper.deleteById(doc.getId()));
        return size;
    }

    private void inTransaction(Runnable body) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> body.run());
    }

    // ── 公共查询/工具 ────────────────────────────────────────────────────

    /** root + 回收站中位于其路径之下的后代；sameBatchOnly=true 时只取同批删除（恢复用）。 */
    private List<WorkspaceFile> deletedSubtree(WorkspaceFile root, boolean sameBatchOnly) {
        List<WorkspaceFile> rows = new ArrayList<>();
        rows.add(root);
        if (!Boolean.TRUE.equals(root.getIsDirectory())) {
            return rows;
        }
        String base = FileTree.originalPath(root);
        List<WorkspaceFile> candidates = fileMapper.selectList(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, root.getWorkspaceId())
                .isNotNull(WorkspaceFile::getDeletedAt)
                .and(w -> w.eq(WorkspaceFile::getParentPath, base)
                        .or().likeRight(WorkspaceFile::getParentPath, base + "/")));
        for (WorkspaceFile f : candidates) {
            if (Objects.equals(f.getId(), root.getId())) {
                continue;
            }
            if (!FileTree.isUnder(f, base)) {
                continue; // likeRight 对 % _ 的误匹配兜底
            }
            if (sameBatchOnly && !sameBatch(root, f)) {
                continue;
            }
            rows.add(f);
        }
        return rows;
    }

    private boolean sameBatch(WorkspaceFile root, WorkspaceFile child) {
        return root.getDeletedAt() != null && root.getDeletedAt().equals(child.getDeletedAt());
    }

    /** 从一堆已删除行中取顶层（父目录不在集合内的行）。 */
    private List<WorkspaceFile> topLevelRows(List<WorkspaceFile> deleted) {
        Set<String> dirPaths = new LinkedHashSet<>();
        for (WorkspaceFile f : deleted) {
            if (Boolean.TRUE.equals(f.getIsDirectory())) {
                dirPaths.add(FileTree.originalPath(f));
            }
        }
        List<WorkspaceFile> roots = new ArrayList<>();
        for (WorkspaceFile f : deleted) {
            String parent = FileTree.normalizePath(f.getParentPath());
            if (!isUnderDeletedDir(parent, dirPaths)) {
                roots.add(f);
            }
        }
        return roots;
    }

    private Long resolveWorkspaceId(Long userId, Long workspaceId) {
        Long wsId = workspaceId != null ? workspaceId : personalSpaceService.resolveWorkspaceId(userId);
        workspaceService.getWorkspace(wsId, userId);
        return wsId;
    }

    private static List<Long> distinctIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(400, "ids 不能为空");
        }
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        if (distinct.size() > MAX_BATCH) {
            throw new BusinessException(400, "单次最多操作 " + MAX_BATCH + " 项");
        }
        for (Long id : distinct) {
            if (id == null) {
                throw new BusinessException(400, "ids 含空值");
            }
        }
        return distinct;
    }

    private static Long toMillis(OffsetDateTime time) {
        return time == null ? null : time.toInstant().toEpochMilli();
    }
}
