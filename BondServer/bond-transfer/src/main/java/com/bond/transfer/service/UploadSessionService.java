package com.bond.transfer.service;

import com.bond.common.dto.PageResult;
import com.bond.common.dto.UploadSessionDTO;
import com.bond.common.dto.WorkspaceFileDTO;
import com.bond.common.entity.FileUploadSession;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.SnowflakeId;
import com.bond.transfer.mapper.UploadSessionMapper;
import com.bond.transfer.storage.ObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 网盘大文件分片/断点续传上传（task-40，V7）。
 *
 * <p>直接复用 {@link ObjectStorage} 的 S3 Multipart Upload，不在本地拼接分片：
 * 会话行只记录业务元数据与协议状态（storageUploadId/storageKey/etags），
 * 真正的 part 保存在对象存储侧；complete 成功后才创建 {@code workspace_files} 行。</p>
 *
 * <p>关键语义：</p>
 * <ul>
 *   <li>init 对同名（用户+工作区+目录+文件名+大小）未过期会话复用，返回旧 uploadId 与已收分片；</li>
 *   <li>PUT 分片幂等：同 index 重传覆盖 etag、receivedBytes 不重复累加；</li>
 *   <li>complete 先 {@code checkUploadAllowed} + 原子 {@code reserveUploadQuota} 再 completeMultipart，
 *       配额不足返回 413 且**不删会话**；</li>
 *   <li>会话过期 410、非本人 404（不泄露存在性）；</li>
 *   <li>每小时清理过期会话（abort S3 multipart + 删行，batch 上限可配）。</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UploadSessionService {

    static final int MIN_CHUNK_SIZE = 5 * 1024 * 1024;
    static final int MAX_CHUNK_SIZE = 64 * 1024 * 1024;
    static final int DEFAULT_CHUNK_SIZE = 8 * 1024 * 1024;
    static final int DEFAULT_MAX_CHUNKS = 10_000;
    static final int DEFAULT_TTL_HOURS = 24;
    static final int DEFAULT_CLEANUP_BATCH = 200;
    /** task-54：上传会话列表分页默认值/上限。 */
    static final int DEFAULT_LIST_SIZE = 50;
    static final int MAX_LIST_SIZE = 200;
    private static final int MAX_FILE_NAME_LENGTH = 512;
    private static final int MAX_PARENT_PATH_LENGTH = 1024;
    private static final int MAX_CONTENT_TYPE_LENGTH = 255;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern HEX64 = Pattern.compile("[0-9a-fA-F]{64}");

    private final UploadSessionMapper sessionMapper;
    private final ObjectStorage objectStorage;
    private final WorkspaceService workspaceService;
    private final PersonalSpaceService personalSpaceService;
    private final ConfigService configService;

    /** 单 JVM 内按 uploadId 串行化「读-合并-写」，避免并发分片丢更新（多实例部署时需换 DB 原子合并）。 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    // ── 对外模型 ──

    public record InitResult(String uploadId, int chunkSize, int totalChunks, List<Integer> uploadedChunks) {
    }

    public record StatusResult(String uploadId, List<Integer> uploadedChunks, long receivedBytes,
                               int totalChunks, OffsetDateTime expiresAt) {
    }

    // ── 会话生命周期 ──

    public InitResult init(Long userId, Long workspaceId, String parentPath, String fileName,
                           Long fileSize, String contentType, String fileHash, List<String> chunkHashes) {
        Long wsId = workspaceId != null ? workspaceId : personalSpaceService.resolveWorkspaceId(userId);
        String path = normalizePath(parentPath);
        String name = normalizeFileName(fileName);
        long size = fileSize == null ? 0L : fileSize;
        if (size <= 0) {
            throw new BusinessException(400, "文件大小必须大于 0");
        }
        String type = normalizeContentType(contentType);

        int chunkSize = resolveChunkSize();
        long totalChunks = (size + chunkSize - 1L) / chunkSize;
        long maxChunks = maxChunks();
        if (totalChunks > maxChunks) {
            throw new BusinessException(400, "分片数量超过上限(" + maxChunks + ")");
        }

        // task-41：客户端声明 hash 与逐片 hash 均为可选；complete 时据此落库，避免服务端重读对象
        String hash = normalizeFileHash(fileHash);
        List<String> partHashes = normalizeChunkHashes(chunkHashes, totalChunks);

        // 档位 max_file_size + 空间预检（与单次上传同一套逻辑，错误码 413/400 一致）
        workspaceService.checkUploadAllowed(wsId, userId, size);

        FileUploadSession reusable = sessionMapper.selectReusable(userId, wsId, path, name, size);
        if (reusable != null) {
            return new InitResult(String.valueOf(reusable.getId()), reusable.getChunkSize(),
                    reusable.getTotalChunks(), uploadedChunks(reusable));
        }

        String storageKey = "workspaces/" + wsId + "/" + UUID.randomUUID() + "/" + name;
        String storageUploadId = objectStorage.initMultipart(storageKey, type);

        FileUploadSession session = new FileUploadSession();
        session.setId(SnowflakeId.nextId());
        session.setUserId(userId);
        session.setWorkspaceId(wsId);
        session.setParentPath(path);
        session.setFileName(name);
        session.setContentType(type);
        session.setFileSize(size);
        session.setChunkSize(chunkSize);
        session.setTotalChunks((int) totalChunks);
        session.setStorageUploadId(storageUploadId);
        session.setStorageKey(storageKey);
        session.setEtags(toStateJson(new String[(int) totalChunks], hash, partHashes));
        session.setReceivedBytes(0L);
        session.setExpiresAt(OffsetDateTime.now().plusHours(ttlHours()));
        sessionMapper.insertSession(session);
        return new InitResult(String.valueOf(session.getId()), chunkSize, (int) totalChunks, List.of());
    }

    public void putPart(Long userId, String uploadId, int index, InputStream data, long size) {
        FileUploadSession session = loadOwned(userId, uploadId);
        if (index < 0 || index >= session.getTotalChunks()) {
            throw new BusinessException(400, "分片索引越界(0.." + (session.getTotalChunks() - 1) + ")");
        }
        long expected = expectedPartSize(session, index);
        if (size != expected) {
            throw new BusinessException(400, "分片大小不符：期望 " + expected + " 字节，实际 " + size);
        }

        // task-41：客户端若在 init 时提供了逐片 SHA-256，上传时边传边算并校验，不落库
        SessionState state = parseState(session.getEtags(), session.getTotalChunks());
        String expectedHash = state.chunkHashes() != null && index < state.chunkHashes().size()
                ? state.chunkHashes().get(index) : null;
        MessageDigest digest = expectedHash != null ? newSha256() : null;
        InputStream source = digest != null ? new DigestInputStream(data, digest) : data;
        String etag = objectStorage.uploadPart(session.getStorageKey(), session.getStorageUploadId(),
                index + 1, source, size);
        if (expectedHash != null) {
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!expectedHash.equalsIgnoreCase(actual)) {
                throw new BusinessException(400, "分片 SHA-256 校验失败(index=" + index + ")");
            }
        }

        synchronized (lock(uploadId)) {
            FileUploadSession fresh = sessionMapper.selectSession(session.getId());
            if (fresh == null) {
                throw new BusinessException(404, "上传会话不存在");
            }
            if (fresh.getExpiresAt() != null && fresh.getExpiresAt().isBefore(OffsetDateTime.now())) {
                throw new BusinessException(410, "上传会话已过期");
            }
            SessionState freshState = parseState(fresh.getEtags(), fresh.getTotalChunks());
            String[] etags = freshState.etags();
            boolean existed = etags[index] != null;
            etags[index] = etag;
            long received = fresh.getReceivedBytes() == null ? 0L : fresh.getReceivedBytes();
            if (!existed) {
                received += size;
            }
            sessionMapper.updateProgress(fresh.getId(),
                    toStateJson(etags, freshState.fileHash(), freshState.chunkHashes()), received);
        }
    }

    public StatusResult status(Long userId, String uploadId) {
        FileUploadSession session = loadOwned(userId, uploadId);
        return new StatusResult(String.valueOf(session.getId()), uploadedChunks(session),
                session.getReceivedBytes() == null ? 0L : session.getReceivedBytes(),
                session.getTotalChunks(), session.getExpiresAt());
    }

    /**
     * task-54：当前用户「未完成」上传会话分页列表（只读）。
     *
     * <p>数据源就是 V7 的 {@code file_upload_sessions} 表：complete 成功或 abort 后会话行即删除，
     * 因此表内未过期行天然等于「未完成」。分页走 LIMIT/OFFSET + 独立 count，不做 N+1。</p>
     *
     * <p>注意：本方法不触发任何写操作（客户端「传输中心」刷新列表时不应产生副作用）。</p>
     */
    public PageResult<UploadSessionDTO> list(Long userId, Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? DEFAULT_LIST_SIZE : Math.min(size, MAX_LIST_SIZE);
        long total = sessionMapper.countActive(userId);
        if (total == 0L) {
            return PageResult.of(List.of(), 0L, p, s);
        }
        long offset = (long) (p - 1) * s;
        List<FileUploadSession> sessions = sessionMapper.selectActivePage(userId, offset, s);
        List<UploadSessionDTO> items = new ArrayList<>(sessions.size());
        for (FileUploadSession session : sessions) {
            items.add(toListDto(session));
        }
        return PageResult.of(items, total, p, s);
    }

    private UploadSessionDTO toListDto(FileUploadSession session) {
        UploadSessionDTO dto = new UploadSessionDTO();
        dto.setUploadId(String.valueOf(session.getId()));
        dto.setFileName(session.getFileName());
        dto.setWorkspaceId(session.getWorkspaceId());
        dto.setParentPath(session.getParentPath());
        dto.setTotalBytes(session.getFileSize() == null ? 0L : session.getFileSize());
        dto.setReceivedBytes(session.getReceivedBytes() == null ? 0L : session.getReceivedBytes());
        dto.setTotalChunks(session.getTotalChunks() == null ? 0 : session.getTotalChunks());
        SessionState state = parseState(session.getEtags(), session.getTotalChunks());
        int received = 0;
        for (String etag : state.etags()) {
            if (etag != null && !etag.isBlank()) {
                received++;
            }
        }
        dto.setChunksReceived(received);
        dto.setCreatedAt(session.getCreatedAt());
        dto.setExpiresAt(session.getExpiresAt());
        return dto;
    }

    public WorkspaceFileDTO complete(Long userId, String uploadId) {
        FileUploadSession session = loadOwned(userId, uploadId);
        if (session.getStorageUploadId() == null || session.getStorageKey() == null) {
            throw new BusinessException(410, "上传会话不可用");
        }
        SessionState state = parseState(session.getEtags(), session.getTotalChunks());
        String[] etags = state.etags();
        List<ObjectStorage.PartETag> parts = new ArrayList<>(session.getTotalChunks());
        long sum = 0L;
        for (int i = 0; i < etags.length; i++) {
            if (etags[i] == null || etags[i].isBlank()) {
                throw new BusinessException(400, "分片尚未上传完整");
            }
            long partSize = expectedPartSize(session, i);
            sum += partSize;
            parts.add(new ObjectStorage.PartETag(i + 1, etags[i], partSize));
        }
        if (sum != session.getFileSize()) {
            throw new BusinessException(400, "分片总字节数与声明大小不一致");
        }

        // 先校验 + 原子占配额，再 completeMultipart：配额不足时不破坏 S3 multipart，用户可重试
        workspaceService.checkUploadAllowed(session.getWorkspaceId(), userId, session.getFileSize());
        workspaceService.reserveUploadQuota(session.getWorkspaceId(), session.getFileSize());
        try {
            objectStorage.completeMultipart(session.getStorageKey(), session.getStorageUploadId(), parts);
        } catch (RuntimeException e) {
            workspaceService.releaseUploadQuota(session.getWorkspaceId(), session.getFileSize());
            throw e;
        }
        try {
            WorkspaceFileDTO dto = workspaceService.createUploadedFile(session.getWorkspaceId(), userId,
                    session.getParentPath(), session.getFileName(), session.getFileSize(),
                    session.getStorageKey(), session.getContentType(), state.fileHash());
            sessionMapper.deleteById(session.getId());
            locks.remove(uploadId);
            return dto;
        } catch (RuntimeException e) {
            workspaceService.releaseUploadQuota(session.getWorkspaceId(), session.getFileSize());
            try {
                objectStorage.deleteObject(session.getStorageKey());
            } catch (RuntimeException cleanupError) {
                log.warn("failed to delete orphan object {}: {}", session.getStorageKey(),
                        cleanupError.getMessage());
            }
            sessionMapper.deleteById(session.getId());
            locks.remove(uploadId);
            throw e;
        }
    }

    public void abort(Long userId, String uploadId) {
        FileUploadSession session = load(userId, uploadId);
        try {
            if (session.getStorageUploadId() != null && session.getStorageKey() != null) {
                objectStorage.abortMultipart(session.getStorageKey(), session.getStorageUploadId());
            }
        } catch (RuntimeException e) {
            log.warn("failed to abort multipart upload {}: {}", session.getId(), e.getMessage());
        }
        sessionMapper.deleteById(session.getId());
        locks.remove(uploadId);
    }

    /**
     * 清理过期会话（task-157）：未完成且 {@code expires_at < now} 的会话 → abort S3 multipart
     * + 删行；**已完成会话的历史不动**（complete 成功后会话行已被删，不存在历史）。
     *
     * <p>由 {@code ExpiryCleanupService} 每日统一调度（本方法不再自带 {@code @Scheduled}）。
     * 单轮上限 {@code upload.cleanup_batch}（0=关闭），最多 {@code maxBatches} 轮；
     * 单条失败 WARN 跳过并计入 failed。</p>
     */
    public CleanupResult cleanupExpired(int maxBatches) {
        int batch = configService.getInt("upload.cleanup_batch", DEFAULT_CLEANUP_BATCH);
        if (batch <= 0) {
            return new CleanupResult(0, 0, 0);
        }
        int rounds = 0;
        int cleaned = 0;
        int failed = 0;
        for (; rounds < Math.max(1, maxBatches); rounds++) {
            List<FileUploadSession> expired = sessionMapper.selectExpired(batch);
            if (expired == null || expired.isEmpty()) {
                break;
            }
            for (FileUploadSession session : expired) {
                try {
                    if (session.getStorageUploadId() != null && session.getStorageKey() != null) {
                        objectStorage.abortMultipart(session.getStorageKey(), session.getStorageUploadId());
                    }
                    sessionMapper.deleteById(session.getId());
                    locks.remove(String.valueOf(session.getId()));
                    cleaned++;
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("failed to clean expired upload session {}: {}", session.getId(), e.getMessage());
                }
            }
            // 不足一批 = 本轮已取完全部待清理行，再查也是空，直接结束避免空转
            if (expired.size() < batch) {
                break;
            }
        }
        if (cleaned > 0 || failed > 0) {
            log.info("cleaned {} expired upload sessions (failed={}, rounds={})", cleaned, failed, rounds);
        }
        return new CleanupResult(cleaned, failed, rounds);
    }

    /** 清理结果：删除数 / 失败数 / 实际执行轮数（供统一调度汇总）。 */
    public record CleanupResult(int deleted, int failed, int rounds) {
    }

    // ── 内部工具 ──

    private FileUploadSession loadOwned(Long userId, String uploadId) {
        FileUploadSession session = load(userId, uploadId);
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new BusinessException(410, "上传会话已过期");
        }
        return session;
    }

    /** 只校验存在性与归属：别人的 uploadId 一律 404，不泄露存在性。 */
    private FileUploadSession load(Long userId, String uploadId) {
        Long id = parseSessionId(uploadId);
        FileUploadSession session = sessionMapper.selectSession(id);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new BusinessException(404, "上传会话不存在");
        }
        return session;
    }

    private Object lock(String uploadId) {
        return locks.computeIfAbsent(uploadId, key -> new Object());
    }

    private static long parseSessionId(String uploadId) {
        try {
            return Long.parseLong(uploadId);
        } catch (NumberFormatException e) {
            throw new BusinessException(404, "上传会话不存在");
        }
    }

    private long expectedPartSize(FileUploadSession session, int index) {
        int chunkSize = session.getChunkSize();
        if (index == session.getTotalChunks() - 1) {
            return session.getFileSize() - (long) chunkSize * (session.getTotalChunks() - 1L);
        }
        return chunkSize;
    }

    private List<Integer> uploadedChunks(FileUploadSession session) {
        String[] etags = parseState(session.getEtags(), session.getTotalChunks()).etags();
        List<Integer> uploaded = new ArrayList<>();
        for (int i = 0; i < etags.length; i++) {
            if (etags[i] != null && !etags[i].isBlank()) {
                uploaded.add(i);
            }
        }
        return uploaded;
    }

    /**
     * 会话 etags 列内的协议状态。
     *
     * <p>task-40 只存 {@code ["etag0", ...]} 数组；task-41 起兼容存对象
     * {@code {"p":[...],"h":"<整文件SHA-256>","c":["<逐片SHA-256>",...]}}，
     * 这样加 hash 字段不需要改 V7（Flyway 迁移已完成，不能回改历史脚本）。</p>
     */
    private record SessionState(String fileHash, List<String> chunkHashes, String[] etags) {
    }

    private static SessionState parseState(String json, int totalChunks) {
        String[] etags = new String[Math.max(totalChunks, 0)];
        if (json == null || json.isBlank()) {
            return new SessionState(null, null, etags);
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node.isArray()) {
                copyEtags(node, etags);
                return new SessionState(null, null, etags);
            }
            if (node.isObject()) {
                JsonNode parts = node.get("p");
                if (parts != null && parts.isArray()) {
                    copyEtags(parts, etags);
                }
                String fileHash = null;
                JsonNode hashNode = node.get("h");
                if (hashNode != null && !hashNode.isNull()) {
                    fileHash = hashNode.asText();
                }
                List<String> chunkHashes = null;
                JsonNode chunkNode = node.get("c");
                if (chunkNode != null && chunkNode.isArray()) {
                    chunkHashes = new ArrayList<>();
                    for (JsonNode value : chunkNode) {
                        chunkHashes.add(value == null || value.isNull() ? null : value.asText());
                    }
                }
                return new SessionState(fileHash, chunkHashes, etags);
            }
        } catch (Exception e) {
            log.warn("failed to parse upload session etags: {}", e.getMessage());
        }
        return new SessionState(null, null, etags);
    }

    private static void copyEtags(JsonNode parts, String[] etags) {
        for (int i = 0; i < etags.length && i < parts.size(); i++) {
            JsonNode value = parts.get(i);
            etags[i] = value == null || value.isNull() ? null : value.asText();
        }
    }

    private static String toStateJson(String[] etags, String fileHash, List<String> chunkHashes) {
        ObjectNode node = MAPPER.createObjectNode();
        ArrayNode parts = node.putArray("p");
        for (String etag : etags) {
            if (etag == null) {
                parts.addNull();
            } else {
                parts.add(etag);
            }
        }
        if (fileHash != null) {
            node.put("h", fileHash);
        }
        if (chunkHashes != null) {
            ArrayNode hashes = node.putArray("c");
            for (String hash : chunkHashes) {
                if (hash == null) {
                    hashes.addNull();
                } else {
                    hashes.add(hash);
                }
            }
        }
        return node.toString();
    }

    private static String normalizeFileHash(String fileHash) {
        if (fileHash == null || fileHash.isBlank()) {
            return null;
        }
        String hash = fileHash.trim().toLowerCase(Locale.ROOT);
        if (!HEX64.matcher(hash).matches()) {
            throw new BusinessException(400, "fileHash 必须是 64 位 hex");
        }
        return hash;
    }

    private static List<String> normalizeChunkHashes(List<String> chunkHashes, long totalChunks) {
        if (chunkHashes == null) {
            return null;
        }
        if (chunkHashes.size() != totalChunks) {
            throw new BusinessException(400, "chunkHashes 数量必须等于分片数(" + totalChunks + ")");
        }
        List<String> normalized = new ArrayList<>(chunkHashes.size());
        for (String hash : chunkHashes) {
            if (hash == null || !HEX64.matcher(hash.trim()).matches()) {
                throw new BusinessException(400, "分片哈希必须是 64 位 hex");
            }
            normalized.add(hash.trim().toLowerCase(Locale.ROOT));
        }
        return normalized;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private int resolveChunkSize() {
        int configured = configService.getInt("upload.chunk_size", DEFAULT_CHUNK_SIZE);
        return Math.max(MIN_CHUNK_SIZE, Math.min(MAX_CHUNK_SIZE, configured));
    }

    private long maxChunks() {
        return configService.getInt("upload.max_chunks", DEFAULT_MAX_CHUNKS);
    }

    private int ttlHours() {
        int configured = configService.getInt("upload.session_ttl_hours", DEFAULT_TTL_HOURS);
        return Math.max(1, Math.min(24 * 7, configured));
    }

    private String normalizePath(String parentPath) {
        String path = parentPath == null || parentPath.isBlank() ? "/" : parentPath;
        if (path.length() > MAX_PARENT_PATH_LENGTH) {
            throw new BusinessException(400, "目录路径过长");
        }
        return path;
    }

    private String normalizeFileName(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        if (name.isEmpty()) {
            throw new BusinessException(400, "文件名不能为空");
        }
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            throw new BusinessException(400, "文件名过长");
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new BusinessException(400, "文件名不能包含路径分隔符");
        }
        return name;
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String type = contentType.trim();
        return type.length() > MAX_CONTENT_TYPE_LENGTH ? null : type;
    }
}
