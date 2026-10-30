package com.bond.transfer.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bond.common.dto.DocumentDTO;
import com.bond.common.dto.DocumentVersionDTO;
import com.bond.common.entity.Document;
import com.bond.common.entity.DocumentVersion;
import com.bond.common.entity.User;
import com.bond.common.entity.Workspace;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.ConflictException;
import com.bond.common.utils.SnowflakeId;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.Entitlements;
import com.bond.transfer.mapper.DocumentMapper;
import com.bond.transfer.mapper.DocumentVersionMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceService;
import com.bond.transfer.trash.TrashRetentionPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 在线文档（task-50，V10）。
 *
 * <ul>
 *   <li>权限：工作区文档走 {@link WorkspaceService#ensureMember}；个人空间文档仅 owner，他人一律 404。</li>
 *   <li>并发：乐观锁 {@code version} + 行锁；无变化保存为 200 no-op；{@code force} 跳过版本校验（历史保留）。</li>
 *   <li>配额：创建预留、保存按差值 reserve/release、软删释放；正文上限 min(档位 max_file_size, 配置 10MB)。</li>
 *   <li>版本：每次成功保存/恢复写行，保留最近 50 个且总字节 ≤ 20MB；每小时清理。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper versionMapper;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceService workspaceService;
    private final PersonalSpaceService personalSpaceService;
    private final ConfigService configService;
    private final EntitlementResolver entitlementResolver;
    private final UserMapper userMapper;
    private final DocumentPdfExporter pdfExporter;

    public static final String CONTENT_TYPE_MARKDOWN = "markdown";
    public static final String CONTENT_TYPE_HTML = "html";
    private static final String CONFIG_MAX_CONTENT_BYTES = "document.max_content_bytes";
    private static final String CONFIG_VERSION_KEEP = "document.version_keep";
    private static final String CONFIG_VERSION_TOTAL_BYTES = "document.version_total_bytes";
    private static final String CONFIG_VERSION_CLEANUP_BATCH = "document.version_cleanup_batch";
    private static final long DEFAULT_MAX_CONTENT_BYTES = 10L * 1024 * 1024;
    private static final int DEFAULT_VERSION_KEEP = 50;
    private static final long DEFAULT_VERSION_TOTAL_BYTES = 20L * 1024 * 1024;
    private static final int DEFAULT_VERSION_CLEANUP_BATCH = 200;
    private static final int TITLE_MAX = 512;
    private static final int PATH_MAX = 1024;
    private static final int DEFAULT_PAGE_LIMIT = 20;
    private static final int MAX_PAGE_LIMIT = 100;
    private static final int MAX_OFFSET = 10_000;

    // ── 创建 / 列表 / 详情 ────────────────────────────────────────────────

    @Transactional
    public DocumentDTO create(Long userId, Long workspaceId, String parentPath, String title, String contentMd) {
        return create(userId, workspaceId, parentPath, title, contentMd, null, null);
    }

    /**
     * 创建文档（task-137 起支持富文本）：{@code contentType=html} 或传 {@code contentHtml}
     * 时，正文以白名单净化后的 HTML 落 {@code content_html}，{@code content_md} 置空。
     */
    @Transactional
    public DocumentDTO create(Long userId, Long workspaceId, String parentPath, String title,
                              String contentMd, String contentHtml, String contentType) {
        String validTitle = requireTitle(title);
        String path = normalizeParentPath(parentPath);
        Long wsId = resolveAndCheck(userId, workspaceId);
        String uniqueTitle = uniqueTitleForCreate(wsId, path, validTitle);
        boolean htmlMode = resolveHtmlMode(contentType, contentHtml, contentMd, false);
        String storedMd;
        String storedHtml;
        if (htmlMode) {
            storedHtml = ContentSanitizer.sanitize(contentHtml == null ? "" : contentHtml);
            storedMd = "";
        } else {
            storedMd = contentMd == null ? "" : contentMd;
            storedHtml = null;
        }
        byte[] bytes = storedBytes(storedMd, storedHtml);
        validateContentSize(userId, bytes.length);

        OffsetDateTime now = OffsetDateTime.now();
        Document doc = new Document();
        doc.setId(SnowflakeId.nextId());
        doc.setWorkspaceId(wsId);
        doc.setOwnerId(userId);
        doc.setTitle(uniqueTitle);
        doc.setContentMd(storedMd);
        doc.setContentHtml(storedHtml);
        doc.setContentType(htmlMode ? CONTENT_TYPE_HTML : CONTENT_TYPE_MARKDOWN);
        doc.setVersion(1L);
        doc.setSizeBytes((long) bytes.length);
        doc.setParentPath(path);
        doc.setLastEditorId(userId);
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);

        if (bytes.length > 0) {
            workspaceService.reserveUploadQuota(wsId, bytes.length);
        }
        try {
            documentMapper.insert(doc);
        } catch (RuntimeException e) {
            if (bytes.length > 0) {
                releaseQuietly(wsId, bytes.length);
            }
            throw e;
        }
        insertVersion(doc, userId, 1L, storedMd, storedHtml, bytes.length);
        log.info("document created: id={}, workspace={}, bytes={}, type={}",
                doc.getId(), wsId, bytes.length, doc.getContentType());
        return toDto(doc, displayName(userMapper.selectById(userId)));
    }

    public List<DocumentDTO> list(Long userId, Long workspaceId, String parentPath) {
        return list(userId, workspaceId, parentPath, null, null);
    }

    /**
     * 文档列表增强（task-137）：{@code q} 标题模糊（LIKE，参数绑定），{@code limit} 1..100 默认 20。
     * 不返回正文。
     */
    public List<DocumentDTO> list(Long userId, Long workspaceId, String parentPath, String q, Integer limit) {
        Long wsId = resolveAndCheck(userId, workspaceId);
        String path = normalizeParentPath(parentPath);
        int safeLimit = clamp(limit == null ? DEFAULT_PAGE_LIMIT : limit, 1, MAX_PAGE_LIMIT);
        LambdaQueryWrapper<Document> query = new LambdaQueryWrapper<Document>()
                .eq(Document::getWorkspaceId, wsId)
                .eq(Document::getParentPath, path)
                .isNull(Document::getDeletedAt);
        if (q != null && !q.isBlank()) {
            String keyword = q.strip();
            if (keyword.length() > 100) {
                throw new BusinessException(400, "q 最长 100 字符");
            }
            query.like(Document::getTitle, keyword);
        }
        query.orderByDesc(Document::getUpdatedAt).last("LIMIT " + safeLimit);
        List<Document> docs = documentMapper.selectList(query);
        Map<Long, String> names = editorNames(docs.stream().map(Document::getLastEditorId).toList());
        List<DocumentDTO> result = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            result.add(toDto(doc, names.get(doc.getLastEditorId()), false));
        }
        return result;
    }

    public DocumentDTO get(Long userId, Long documentId) {
        Document doc = requireAccessible(userId, documentId);
        return toDto(doc, displayName(userMapper.selectById(doc.getLastEditorId())));
    }

    // ── 保存（乐观锁 / no-op / force） ─────────────────────────────────────

    @Transactional
    public DocumentDTO save(Long userId, Long documentId, String title, String contentMd,
                            Long expectedVersion, boolean force) {
        return save(userId, documentId, title, contentMd, null, null, expectedVersion, force);
    }

    /**
     * 保存（task-137 扩展）：支持 {@code contentHtml} / {@code contentType}；html 内容先白名单净化，
     * 再落 {@code content_html}（{@code content_md} 置空）。其余乐观锁/no-op/force 语义不变。
     */
    @Transactional
    public DocumentDTO save(Long userId, Long documentId, String title, String contentMd, String contentHtml,
                            String contentType, Long expectedVersion, boolean force) {
        if (expectedVersion == null && !force) {
            throw new BusinessException(400, "version 不能为空");
        }
        requireAccessible(userId, documentId);
        Document doc = documentMapper.selectForUpdate(documentId);
        if (doc == null || doc.getDeletedAt() != null) {
            throw new BusinessException(404, "文档不存在");
        }

        boolean htmlMode = resolveHtmlMode(contentType, contentHtml, contentMd,
                CONTENT_TYPE_HTML.equals(doc.getContentType()));
        String newTitle = title != null ? requireTitle(title) : doc.getTitle();
        String newMd;
        String newHtml;
        if (htmlMode) {
            String raw = contentHtml != null ? contentHtml
                    : (doc.getContentHtml() != null ? doc.getContentHtml() : "");
            newHtml = ContentSanitizer.sanitize(raw);
            newMd = "";
        } else {
            newMd = contentMd != null ? contentMd : (doc.getContentMd() == null ? "" : doc.getContentMd());
            newHtml = null;
        }
        byte[] bytes = storedBytes(newMd, newHtml);
        validateContentSize(userId, bytes.length);

        // 目录内标题唯一：显式改名撞名 → 409（不允许静默改客户端给的名字）
        if (!Objects.equals(newTitle, doc.getTitle())) {
            ensureTitleAvailable(doc.getWorkspaceId(), doc.getParentPath(), newTitle, documentId);
        }

        boolean sameContent = sha256Hex(doc.getContentMd()).equals(sha256Hex(newMd))
                && sha256Hex(doc.getContentHtml()).equals(sha256Hex(newHtml))
                && Objects.equals(doc.getContentType(), htmlMode ? CONTENT_TYPE_HTML : CONTENT_TYPE_MARKDOWN);
        if (sameContent && Objects.equals(newTitle, doc.getTitle())) {
            // 自动保存的周期性 PUT：不升版本、不写版本行、不动 updated_at / 配额
            return toDto(doc, displayName(userMapper.selectById(userId)));
        }

        long oldSize = doc.getSizeBytes() == null ? 0L : doc.getSizeBytes();
        long delta = bytes.length - oldSize;
        if (delta > 0) {
            workspaceService.reserveUploadQuota(doc.getWorkspaceId(), delta);
        }
        long newVersion = doc.getVersion() + 1;
        OffsetDateTime now = OffsetDateTime.now();
        LambdaUpdateWrapper<Document> update = new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, documentId)
                .isNull(Document::getDeletedAt)
                .set(Document::getTitle, newTitle)
                .set(Document::getContentMd, newMd)
                .set(Document::getContentHtml, newHtml)
                .set(Document::getContentType, htmlMode ? CONTENT_TYPE_HTML : CONTENT_TYPE_MARKDOWN)
                .set(Document::getSizeBytes, (long) bytes.length)
                .set(Document::getVersion, newVersion)
                .set(Document::getUpdatedAt, now)
                .set(Document::getLastEditorId, userId);
        if (!force) {
            update.eq(Document::getVersion, expectedVersion);
        }
        int rows = documentMapper.update(null, update);
        if (rows == 0) {
            throw conflict(documentMapper.selectById(documentId));
        }
        insertVersion(doc, userId, newVersion, newMd, newHtml, bytes.length);
        if (delta < 0) {
            releaseQuietly(doc.getWorkspaceId(), -delta);
        }
        cleanupVersions(documentId);

        doc.setTitle(newTitle);
        doc.setContentMd(newMd);
        doc.setContentHtml(newHtml);
        doc.setContentType(htmlMode ? CONTENT_TYPE_HTML : CONTENT_TYPE_MARKDOWN);
        doc.setSizeBytes((long) bytes.length);
        doc.setVersion(newVersion);
        doc.setUpdatedAt(now);
        doc.setLastEditorId(userId);
        log.info("document saved: id={}, version={}, bytes={}, type={}, force={}",
                documentId, newVersion, bytes.length, doc.getContentType(), force);
        return toDto(doc, displayName(userMapper.selectById(userId)));
    }

    /**
     * 自动保存（PATCH）：只更新正文（不允许改标题/目录）。
     *
     * <p>版本合并语义（task-137）：首次自动保存升一个版本；同一编辑者在 **60 秒**内
     * 的连续自动保存只更新该版本的内容（版本号不变），避免每次停顿都产生版本行。</p>
     */
    @Transactional
    public DocumentDTO autosave(Long userId, Long documentId, String contentMd, String contentHtml) {
        requireAccessible(userId, documentId);
        Document doc = documentMapper.selectForUpdate(documentId);
        if (doc == null || doc.getDeletedAt() != null) {
            throw new BusinessException(404, "文档不存在");
        }
        boolean htmlMode = CONTENT_TYPE_HTML.equals(doc.getContentType());
        String raw = htmlMode ? contentHtml : contentMd;
        if (raw == null) {
            throw new BusinessException(400, htmlMode ? "contentHtml 不能为空" : "contentMd 不能为空");
        }
        String newMd;
        String newHtml;
        if (htmlMode) {
            newHtml = ContentSanitizer.sanitize(raw);
            newMd = "";
        } else {
            newMd = raw;
            newHtml = null;
        }
        byte[] bytes = storedBytes(newMd, newHtml);
        validateContentSize(userId, bytes.length);

        if (sha256Hex(doc.getContentMd()).equals(sha256Hex(newMd))
                && sha256Hex(doc.getContentHtml()).equals(sha256Hex(newHtml))) {
            return toDto(doc, displayName(userMapper.selectById(userId)));
        }

        OffsetDateTime now = OffsetDateTime.now();
        long oldSize = doc.getSizeBytes() == null ? 0L : doc.getSizeBytes();
        long delta = bytes.length - oldSize;
        if (delta > 0) {
            workspaceService.reserveUploadQuota(doc.getWorkspaceId(), delta);
        }

        DocumentVersion latest = versionMapper.selectLatest(documentId);
        boolean merge = latest != null
                && Objects.equals(latest.getVersion(), doc.getVersion())
                && Objects.equals(latest.getEditorId(), userId)
                && latest.getCreatedAt() != null
                && !latest.getCreatedAt().isBefore(now.minusSeconds(60));
        long version = doc.getVersion();
        if (!merge) {
            version = doc.getVersion() + 1;
        }
        LambdaUpdateWrapper<Document> update = new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, documentId)
                .isNull(Document::getDeletedAt)
                .eq(Document::getVersion, doc.getVersion())
                .set(Document::getContentMd, newMd)
                .set(Document::getContentHtml, newHtml)
                .set(Document::getSizeBytes, (long) bytes.length)
                .set(Document::getVersion, version)
                .set(Document::getUpdatedAt, now)
                .set(Document::getLastEditorId, userId);
        int rows = documentMapper.update(null, update);
        if (rows == 0) {
            if (delta > 0) {
                releaseQuietly(doc.getWorkspaceId(), delta);
            }
            throw conflict(documentMapper.selectById(documentId));
        }
        if (merge) {
            versionMapper.updateContent(latest.getId(), newMd, newHtml, bytes.length);
        } else {
            insertVersion(doc, userId, version, newMd, newHtml, bytes.length);
        }
        if (delta < 0) {
            releaseQuietly(doc.getWorkspaceId(), -delta);
        }
        cleanupVersions(documentId);

        doc.setContentMd(newMd);
        doc.setContentHtml(newHtml);
        doc.setSizeBytes((long) bytes.length);
        doc.setVersion(version);
        doc.setUpdatedAt(now);
        doc.setLastEditorId(userId);
        log.info("document autosaved: id={}, version={}, bytes={}, merged={}",
                documentId, version, bytes.length, merge);
        return toDto(doc, displayName(userMapper.selectById(userId)));
    }

    // ── 删除（软删进回收站） ──────────────────────────────────────────────

    @Transactional
    public void delete(Long userId, Long documentId) {
        Document doc = requireAccessible(userId, documentId);
        OffsetDateTime now = OffsetDateTime.now();
        // 保留期按「工作区 owner 生效档位」算（task-88），不是操作者的档位
        Long ownerId = null;
        if (doc.getWorkspaceId() != null) {
            Workspace ws = workspaceMapper.selectById(doc.getWorkspaceId());
            ownerId = ws == null ? null : ws.getOwnerId();
        }
        int retentionDays = trashRetentionDays(ownerId);
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, documentId)
                .isNull(Document::getDeletedAt)
                .set(Document::getDeletedAt, now)
                .set(Document::getTrashExpiresAt, retentionDays > 0 ? now.plusDays(retentionDays) : null));
        // 配额（task-162）：软删即释放（与文件一致）；物理清理不再重复扣减，恢复时重新预占。
        long releasedBytes = doc.getSizeBytes() == null ? 0L : doc.getSizeBytes();
        workspaceService.releaseSpace(doc.getWorkspaceId(), releasedBytes);
        log.info("document moved to trash (quota released, task-162): id={}, workspace={}, releasedBytes={}",
                documentId, doc.getWorkspaceId(), releasedBytes);
    }

    // ── 版本历史 ──────────────────────────────────────────────────────────

    public List<DocumentVersionDTO> versions(Long userId, Long documentId, Integer limit, Integer offset) {
        requireAccessible(userId, documentId);
        int safeLimit = clamp(limit == null ? DEFAULT_PAGE_LIMIT : limit, 1, MAX_PAGE_LIMIT);
        int safeOffset = clamp(offset == null ? 0 : offset, 0, MAX_OFFSET);
        List<DocumentVersion> rows = versionMapper.selectPage(documentId, safeLimit, safeOffset);
        Map<Long, String> names = editorNames(rows.stream().map(DocumentVersion::getEditorId).toList());
        List<DocumentVersionDTO> result = new ArrayList<>(rows.size());
        for (DocumentVersion row : rows) {
            result.add(toVersionDto(row, names.get(row.getEditorId())));
        }
        return result;
    }

    public DocumentVersionDTO version(Long userId, Long documentId, long version) {
        requireAccessible(userId, documentId);
        DocumentVersion row = versionMapper.selectByVersion(documentId, version);
        if (row == null) {
            throw new BusinessException(404, "版本不存在");
        }
        DocumentVersionDTO dto = toVersionDto(row, displayName(userMapper.selectById(row.getEditorId())));
        dto.setContentMd(row.getContentMd());
        dto.setContentHtml(row.getContentHtml());
        dto.setContent(row.getContentHtml() != null ? row.getContentHtml() : row.getContentMd());
        return dto;
    }

    @Transactional
    public DocumentDTO restoreVersion(Long userId, Long documentId, long version) {
        requireAccessible(userId, documentId);
        Document doc = documentMapper.selectForUpdate(documentId);
        if (doc == null || doc.getDeletedAt() != null) {
            throw new BusinessException(404, "文档不存在");
        }
        DocumentVersion old = versionMapper.selectByVersion(documentId, version);
        if (old == null) {
            throw new BusinessException(404, "版本不存在");
        }
        String content = old.getContentMd() == null ? "" : old.getContentMd();
        String contentHtml = old.getContentHtml();
        byte[] bytes = storedBytes(content, contentHtml);
        validateContentSize(userId, bytes.length);

        long oldSize = doc.getSizeBytes() == null ? 0L : doc.getSizeBytes();
        long delta = bytes.length - oldSize;
        if (delta > 0) {
            workspaceService.reserveUploadQuota(doc.getWorkspaceId(), delta);
        }
        long newVersion = doc.getVersion() + 1;
        OffsetDateTime now = OffsetDateTime.now();
        int rows = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, documentId)
                .isNull(Document::getDeletedAt)
                .eq(Document::getVersion, doc.getVersion())
                .set(Document::getContentMd, content)
                .set(Document::getContentHtml, contentHtml)
                .set(Document::getSizeBytes, (long) bytes.length)
                .set(Document::getVersion, newVersion)
                .set(Document::getUpdatedAt, now)
                .set(Document::getLastEditorId, userId));
        if (rows == 0) {
            throw conflict(documentMapper.selectById(documentId));
        }
        insertVersion(doc, userId, newVersion, content, contentHtml, bytes.length);
        if (delta < 0) {
            releaseQuietly(doc.getWorkspaceId(), -delta);
        }
        cleanupVersions(documentId);

        doc.setContentMd(content);
        doc.setContentHtml(contentHtml);
        doc.setSizeBytes((long) bytes.length);
        doc.setVersion(newVersion);
        doc.setUpdatedAt(now);
        doc.setLastEditorId(userId);
        log.info("document version restored: id={}, fromVersion={}, newVersion={}",
                documentId, version, newVersion);
        return toDto(doc, displayName(userMapper.selectById(userId)));
    }

    // ── 导出 ──────────────────────────────────────────────────────────────

    public ExportFile export(Long userId, Long documentId, String format) {
        String fmt = format == null || format.isBlank() ? "md" : format.toLowerCase();
        Document doc = requireAccessible(userId, documentId);
        boolean htmlDoc = CONTENT_TYPE_HTML.equals(doc.getContentType());
        String content = doc.getContentMd() == null ? "" : doc.getContentMd();
        String baseName = safeFileName(doc.getTitle());
        String stem = baseName.toLowerCase().endsWith(".md")
                ? baseName.substring(0, baseName.length() - 3) : baseName;
        if (stem.isBlank()) {
            stem = "document";
        }
        switch (fmt) {
            case "md" -> {
                if (htmlDoc) {
                    throw new BusinessException(400, "HTML 文档不支持导出 Markdown，请导出 HTML 或 PDF");
                }
                String name = baseName.toLowerCase().endsWith(".md") ? baseName : baseName + ".md";
                return new ExportFile(content.getBytes(StandardCharsets.UTF_8),
                        "text/markdown; charset=UTF-8", name);
            }
            case "html" -> {
                String html = htmlDoc
                        ? wrapHtmlPage(doc.getTitle(), doc.getContentHtml())
                        : MarkdownHtmlRenderer.render(doc.getTitle(), content);
                return new ExportFile(html.getBytes(StandardCharsets.UTF_8),
                        "text/html; charset=UTF-8", stem + ".html");
            }
            case "pdf" -> {
                String html = htmlDoc
                        ? wrapHtmlPage(doc.getTitle(), doc.getContentHtml())
                        : MarkdownHtmlRenderer.render(doc.getTitle(), content);
                return new ExportFile(pdfExporter.toPdf(html), "application/pdf", stem + ".pdf");
            }
            case "docx", "doc" -> throw new BusinessException(501, "暂不支持导出 Word，请导出 PDF 或 HTML");
            default -> throw new BusinessException(400, "format 只支持 md/html/pdf");
        }
    }

    /** 把（已净化的）HTML 片段包成完整页面，供浏览器打开/PDF 转换。 */
    private static String wrapHtmlPage(String title, String bodyHtml) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + escapeHtml(title)
                + "</title></head><body>" + (bodyHtml == null ? "" : bodyHtml) + "</body></html>";
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** 导出文件（内容、Content-Type、建议文件名）。 */
    public record ExportFile(byte[] content, String contentType, String fileName) {
    }

    // ── 版本保留清理 ──────────────────────────────────────────────────────

    /** 保留最近 {@code document.version_keep} 个且总字节 ≤ 上限，超出从最老删。 */
    public void cleanupVersions(Long documentId) {
        int keep = (int) Math.max(1, configService.getInt(CONFIG_VERSION_KEEP, DEFAULT_VERSION_KEEP));
        long totalCap = Math.max(0L, configService.getLong(CONFIG_VERSION_TOTAL_BYTES, DEFAULT_VERSION_TOTAL_BYTES));
        List<DocumentVersion> rows = versionMapper.selectSizes(documentId);
        if (rows.size() <= keep) {
            long total = rows.stream().mapToLong(r -> r.getSizeBytes() == null ? 0L : r.getSizeBytes()).sum();
            if (totalCap <= 0 || total <= totalCap) {
                return;
            }
        }
        long cumulative = 0;
        List<Long> toDelete = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            DocumentVersion row = rows.get(i);
            cumulative += row.getSizeBytes() == null ? 0L : row.getSizeBytes();
            if (i >= keep || (totalCap > 0 && cumulative > totalCap)) {
                for (int j = i; j < rows.size(); j++) {
                    toDelete.add(rows.get(j).getId());
                }
                break;
            }
        }
        if (!toDelete.isEmpty()) {
            versionMapper.deleteBatchIds(toDelete);
            log.debug("document versions pruned: document={}, deleted={}, keep={}, totalCap={}",
                    documentId, toDelete.size(), keep, totalCap);
        }
    }

    /** 每小时清理超限文档的历史版本（单轮上限 document.version_cleanup_batch）。 */
    @Scheduled(cron = "0 40 * * * *")
    public void cleanupExpiredVersions() {
        int keep = (int) Math.max(1, configService.getInt(CONFIG_VERSION_KEEP, DEFAULT_VERSION_KEEP));
        long totalCap = Math.max(1L, configService.getLong(CONFIG_VERSION_TOTAL_BYTES, DEFAULT_VERSION_TOTAL_BYTES));
        int batch = (int) Math.max(1, Math.min(500, configService.getInt(
                CONFIG_VERSION_CLEANUP_BATCH, DEFAULT_VERSION_CLEANUP_BATCH)));
        List<Long> docs = versionMapper.selectBloatDocumentIds(keep, totalCap, batch);
        if (docs.isEmpty()) {
            return;
        }
        int cleaned = 0;
        for (Long docId : docs) {
            try {
                cleanupVersions(docId);
                cleaned++;
            } catch (RuntimeException e) {
                log.warn("document version cleanup failed: document={}, err={}", docId, e.getMessage());
            }
        }
        log.info("document version cleanup done: docs={}, cleaned={}", docs.size(), cleaned);
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────

    private void insertVersion(Document doc, Long editorId, long version, String content, long sizeBytes) {
        insertVersion(doc, editorId, version, content, null, sizeBytes);
    }

    private void insertVersion(Document doc, Long editorId, long version, String contentMd,
                               String contentHtml, long sizeBytes) {
        DocumentVersion row = new DocumentVersion();
        row.setId(SnowflakeId.nextId());
        row.setDocumentId(doc.getId());
        row.setVersion(version);
        row.setContentMd(contentMd == null ? "" : contentMd);
        row.setContentHtml(contentHtml);
        row.setSizeBytes(sizeBytes);
        row.setEditorId(editorId);
        row.setCreatedAt(OffsetDateTime.now());
        versionMapper.insert(row);
    }

    /** 正文模式判定：显式 contentType 优先；否则按是否提供 html/正文推断；都没有则沿用当前类型。 */
    private static boolean resolveHtmlMode(String contentType, String contentHtml, String contentMd,
                                           boolean currentHtml) {
        if (contentType != null && !contentType.isBlank()) {
            String type = contentType.trim().toLowerCase(java.util.Locale.ROOT);
            if (CONTENT_TYPE_MARKDOWN.equals(type)) {
                return false;
            }
            if (CONTENT_TYPE_HTML.equals(type)) {
                return true;
            }
            throw new BusinessException(400, "contentType 只支持 markdown/html");
        }
        if (contentHtml != null) {
            return true;
        }
        if (contentMd != null) {
            return false;
        }
        return currentHtml;
    }

    private static byte[] storedBytes(String contentMd, String contentHtml) {
        String value = contentHtml != null ? contentHtml : (contentMd == null ? "" : contentMd);
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private ConflictException conflict(Document current) {
        String name = current == null ? null : displayName(userMapper.selectById(current.getLastEditorId()));
        String message = name == null ? "文档已被他人修改" : "文档已被 " + name + " 修改";
        return new ConflictException(message, conflictPayload(current));
    }

    private Long resolveAndCheck(Long userId, Long workspaceId) {
        Long wsId = workspaceId != null ? workspaceId : personalSpaceService.resolveWorkspaceId(userId);
        Workspace ws = workspaceMapper.selectById(wsId);
        if (ws == null) {
            throw new BusinessException(404, "工作区不存在");
        }
        if ("personal".equals(ws.getType()) && !Objects.equals(ws.getOwnerId(), userId)) {
            throw new BusinessException(404, "文档不存在");
        }
        workspaceService.ensureMember(wsId, userId);
        return wsId;
    }

    /** 目录内标题唯一：冲突时自动追加「(2)」「(3)」…（仅在创建时使用）。 */
    private String uniqueTitleForCreate(Long workspaceId, String path, String title) {
        if (!titleExists(workspaceId, path, title, null)) {
            return title;
        }
        int n = 2;
        while (titleExists(workspaceId, path, title + " (" + n + ")", null)) {
            n++;
        }
        return title + " (" + n + ")";
    }

    private void ensureTitleAvailable(Long workspaceId, String path, String title, Long excludeId) {
        if (titleExists(workspaceId, path, title, excludeId)) {
            throw new BusinessException(409, "同名文档已存在");
        }
    }

    private boolean titleExists(Long workspaceId, String path, String title, Long excludeId) {
        Long count = documentMapper.selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getWorkspaceId, workspaceId)
                .eq(Document::getParentPath, path)
                .eq(Document::getTitle, title)
                .isNull(Document::getDeletedAt)
                .ne(excludeId != null, Document::getId, excludeId));
        return count != null && count > 0;
    }

    private Document requireAccessible(Long userId, Long documentId) {        Document doc = documentMapper.selectById(documentId);
        if (doc == null || doc.getDeletedAt() != null) {
            throw new BusinessException(404, "文档不存在");
        }
        Workspace ws = workspaceMapper.selectById(doc.getWorkspaceId());
        if (ws == null) {
            throw new BusinessException(404, "文档不存在");
        }
        if ("personal".equals(ws.getType())) {
            if (!Objects.equals(ws.getOwnerId(), userId)) {
                throw new BusinessException(404, "文档不存在");
            }
        } else {
            workspaceService.ensureMember(doc.getWorkspaceId(), userId);
        }
        return doc;
    }

    /** 正文硬上限 = min(档位 max_file_size, document.max_content_bytes)；超限 413。 */
    private void validateContentSize(Long userId, long bytes) {
        Entitlements entitlements = entitlementResolver.resolve(userId);
        long planMax = entitlements.limits().maxFileSize();
        long configured = configService.getLong(CONFIG_MAX_CONTENT_BYTES, DEFAULT_MAX_CONTENT_BYTES);
        long cap = configured > 0 ? (planMax > 0 ? Math.min(planMax, configured) : configured) : planMax;
        if (cap <= 0) {
            cap = DEFAULT_MAX_CONTENT_BYTES;
        }
        if (bytes > cap) {
            throw new BusinessException(413, "单篇文档正文上限 " + formatBytes(cap));
        }
    }

    /** 回收站保留天数（task-110 统一策略：档位 &gt; 全局配置 trash.retention_days &gt; 7；0=不限期）。 */
    private int trashRetentionDays(Long ownerId) {
        return TrashRetentionPolicy.daysFor(entitlementResolver, configService, ownerId, log);
    }

    private void releaseQuietly(Long workspaceId, long bytes) {
        if (bytes <= 0) {
            return;
        }
        try {
            workspaceService.releaseUploadQuota(workspaceId, bytes);
        } catch (RuntimeException e) {
            log.warn("document quota release failed: workspace={}, bytes={}, err={}",
                    workspaceId, bytes, e.getMessage());
        }
    }

    private Map<String, Object> conflictPayload(Document current) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (current == null) {
            payload.put("version", null);
            payload.put("content", null);
            payload.put("contentHtml", null);
            payload.put("updatedAtMillis", null);
            payload.put("serverVersion", null);
            payload.put("lastEditorName", null);
            payload.put("updatedAt", null);
            return payload;
        }
        boolean htmlDoc = CONTENT_TYPE_HTML.equals(current.getContentType());
        Long updatedAtMillis = millis(current.getUpdatedAt());
        payload.put("version", current.getVersion());
        payload.put("content", htmlDoc ? current.getContentHtml() : current.getContentMd());
        payload.put("contentHtml", current.getContentHtml());
        payload.put("updatedAtMillis", updatedAtMillis);
        // 兼容 task-50 起的旧字段
        payload.put("serverVersion", current.getVersion());
        payload.put("lastEditorName", displayName(userMapper.selectById(current.getLastEditorId())));
        payload.put("updatedAt", updatedAtMillis);
        return payload;
    }

    private DocumentDTO toDto(Document doc, String lastEditorName) {
        return toDto(doc, lastEditorName, true);
    }

    /** includeContent=false 用于列表：不把整篇正文带进列表响应。 */
    private DocumentDTO toDto(Document doc, String lastEditorName, boolean includeContent) {
        DocumentDTO dto = new DocumentDTO();
        dto.setId(doc.getId());
        dto.setWorkspaceId(doc.getWorkspaceId());
        dto.setTitle(doc.getTitle());
        dto.setParentPath(doc.getParentPath());
        dto.setSizeBytes(doc.getSizeBytes());
        dto.setVersion(doc.getVersion());
        dto.setContentType(doc.getContentType());
        dto.setLastEditorId(doc.getLastEditorId());
        dto.setLastEditorName(lastEditorName);
        dto.setLastEditorNickname(lastEditorName);
        dto.setCreatedAt(millis(doc.getCreatedAt()));
        dto.setUpdatedAt(millis(doc.getUpdatedAt()));
        dto.setUpdatedAtMillis(millis(doc.getUpdatedAt()));
        if (includeContent) {
            dto.setContentMd(doc.getContentMd());
            dto.setContentHtml(doc.getContentHtml());
            dto.setContent(CONTENT_TYPE_HTML.equals(doc.getContentType())
                    ? doc.getContentHtml() : doc.getContentMd());
        }
        return dto;
    }

    private DocumentVersionDTO toVersionDto(DocumentVersion row, String editorName) {
        DocumentVersionDTO dto = new DocumentVersionDTO();
        dto.setId(row.getId());
        dto.setDocumentId(row.getDocumentId());
        dto.setVersion(row.getVersion());
        dto.setSizeBytes(row.getSizeBytes());
        dto.setEditorId(row.getEditorId());
        dto.setEditorName(editorName);
        dto.setCreatedAt(millis(row.getCreatedAt()));
        return dto;
    }

    private Map<Long, String> editorNames(Collection<Long> editorIds) {
        List<Long> ids = editorIds == null ? List.of()
                : editorIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (User user : userMapper.selectBatchIds(ids)) {
            if (user != null && user.getId() != null) {
                names.put(user.getId(), displayName(user));
            }
        }
        return names;
    }

    private static String displayName(User user) {
        if (user == null) {
            return null;
        }
        if (user.getNickname() != null && !user.getNickname().isBlank()) {
            return user.getNickname();
        }
        return user.getUsername();
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(400, "标题不能为空");
        }
        String trimmed = title.strip();
        if (trimmed.length() > TITLE_MAX) {
            throw new BusinessException(400, "标题最长 " + TITLE_MAX + " 字符");
        }
        return trimmed;
    }

    /** 规范化父路径：空 → "/"；必须以 "/" 开头、去掉尾部 "/"、拒绝 ".." 与 "//"。 */
    private static String normalizeParentPath(String parentPath) {
        if (parentPath == null || parentPath.isBlank()) {
            return "/";
        }
        String path = parentPath.strip();
        if (!path.startsWith("/")) {
            throw new BusinessException(400, "parentPath 必须以 / 开头");
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.length() > PATH_MAX) {
            throw new BusinessException(400, "parentPath 最长 " + PATH_MAX + " 字符");
        }
        if (path.contains("..") || path.contains("//")) {
            throw new BusinessException(400, "parentPath 不合法");
        }
        return path;
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String safeFileName(String title) {
        String name = title == null || title.isBlank() ? "document" : title.strip();
        name = name.replaceAll("[/\\\\:*?\"<>|]", "_");
        return name.isBlank() ? "document" : name;
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1048576L) {
            return (bytes / 1048576L) + "MB";
        }
        if (bytes >= 1024L) {
            return (bytes / 1024L) + "KB";
        }
        return bytes + "字节";
    }

    private static Long millis(OffsetDateTime time) {
        return time == null ? null : time.toInstant().toEpochMilli();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
