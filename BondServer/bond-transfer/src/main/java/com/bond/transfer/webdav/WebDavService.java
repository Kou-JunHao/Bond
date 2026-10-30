package com.bond.transfer.webdav;

import com.bond.common.dto.WorkspaceFileDTO;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.preview.RangeParser;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * WebDAV MVP 的核心协议处理。
 *
 * <p>为什么不直接用 Spring 的 {@code @RequestMapping(method=...)}：Java 方法名无法写成
 * PROPFIND/MKCOL 这类自定义动词，所以由 {@link WebDavController} 统一接收
 * {@code /dav/**}，在这里按 {@code request.getMethod()} 手工分发。</p>
 *
 * <p>返回 {@link WebDavResponse} 由 controller 直接写回容器，不经过 Spring 的 JSON 响应包装。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebDavService {

    static final String CONTENT_TYPE_DIR = "httpd/unix-directory";
    static final long LOCK_TIMEOUT_SECONDS = 1800L;
    static final String ALLOW_METHODS =
            "OPTIONS, PROPFIND, GET, HEAD, PUT, MKCOL, DELETE, MOVE, COPY, LOCK, UNLOCK";

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    /** 从 If / Lock-Token 头里提取 opaquelocktoken（task-126）。 */
    private static final Pattern LOCK_TOKEN_PATTERN =
            Pattern.compile("(opaquelocktoken:[A-Za-z0-9-]+)");

    /** LOCK 请求体里的 {@code <owner>} 块。 */
    private static final Pattern OWNER_BLOCK = Pattern.compile(
            "<(?:\\w+:)?owner[^>]*>(.*?)</(?:\\w+:)?owner>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final WebDavAuthService authService;
    private final WebDavLocks locks;
    private final PersonalSpaceService personalSpaceService;
    private final WorkspaceService workspaceService;
    private final UsageService usageService;

    public WebDavResponse handle(HttpServletRequest request) {
        String method = request.getMethod() == null
                ? ""
                : request.getMethod().toUpperCase(Locale.ROOT);
        WebDavPath.Parsed parsed = parseRequestPath(request);

        // OPTIONS 不要求凭据：Windows Explorer 先探测能力再弹登录框。
        if ("OPTIONS".equals(method)) {
            authService.ensureAvailable(request);
            return optionsResponse();
        }

        authService.ensureAvailable(request);
        Long userId = authService.authenticate(request);
        Long workspaceId = resolveWorkspace(userId, parsed);

        try {
            // task-126：写操作必须持有匹配的锁 token，否则 423；不再出现“LOCK 返回 200
            // 但实际没人拦并发写”的静默覆盖。MOVE/COPY 的目标另在 moveOrCopy 内校验。
            if (isWriteMethod(method)) {
                assertUnlocked(workspaceId, parsed, request);
            }
            return switch (method) {
                case "PROPFIND" -> propFind(userId, workspaceId, parsed, request);
                case "GET", "HEAD" -> get(userId, workspaceId, parsed, request, method);
                case "PUT" -> put(userId, workspaceId, parsed, request);
                case "MKCOL" -> mkcol(userId, workspaceId, parsed);
                case "DELETE" -> delete(userId, workspaceId, parsed);
                case "MOVE" -> moveOrCopy(userId, workspaceId, parsed, request, true);
                case "COPY" -> moveOrCopy(userId, workspaceId, parsed, request, false);
                case "LOCK" -> lock(workspaceId, parsed, request);
                case "UNLOCK" -> unlock(workspaceId, parsed, request);
                case "PROPPATCH", "ACL" -> throw new WebDavException(501, "not implemented");
                default -> throw new WebDavException(405, "method not allowed");
            };
        } catch (BusinessException e) {
            throw new WebDavException(httpStatus(e.getCode()), e.getMessage());
        }
    }

    // ---------------------------------------------------------------- OPTIONS

    private WebDavResponse optionsResponse() {
        return WebDavResponse.status(200)
                .header("DAV", "1,2")
                .header("MS-Author-Via", "DAV")
                .header("Allow", ALLOW_METHODS)
                .header("Accept-Ranges", "bytes")
                .header("Content-Length", "0");
    }

    // ---------------------------------------------------------------- PROPFIND

    private WebDavResponse propFind(Long userId, Long workspaceId, WebDavPath.Parsed parsed,
                                    HttpServletRequest request) {
        String depth = depthOf(request);
        List<WebDavEntry> entries = new ArrayList<>();
        if (parsed.segments().isEmpty()) {
            entries.add(rootEntry(parsed));
            if ("1".equals(depth)) {
                appendChildren(userId, workspaceId, parsed, entries);
            }
        } else {
            WorkspaceFile entry = findEntry(workspaceId, parsed);
            if (entry == null) {
                throw new WebDavException(404, "not found");
            }
            entries.add(entryForFile(parsed, parsed.segments(), entry));
            if ("1".equals(depth) && Boolean.TRUE.equals(entry.getIsDirectory())) {
                appendChildren(userId, workspaceId, parsed, entries);
            }
        }
        attachLockTokens(workspaceId, entries);
        return WebDavResponse.xml(207, WebDavXml.multiStatus(entries));
    }

    /** task-126：把当前锁写进 PROPFIND 的 lockdiscovery，客户端才知道资源被谁锁。 */
    private void attachLockTokens(Long workspaceId, List<WebDavEntry> entries) {
        List<WebDavLocks.LockInfo> active = locks.active();
        if (active.isEmpty()) {
            return;
        }
        String prefix = (workspaceId == null ? "personal" : String.valueOf(workspaceId)) + ":";
        for (int i = 0; i < entries.size(); i++) {
            WebDavEntry entry = entries.get(i);
            String relative;
            try {
                relative = WebDavPath.parse(entry.href()).relativePath();
            } catch (RuntimeException e) {
                continue;
            }
            String key = prefix + relative;
            String token = null;
            for (WebDavLocks.LockInfo lock : active) {
                if (WebDavLocks.covers(lock.depth(), lock.path(), key)) {
                    token = lock.token();
                    break;
                }
            }
            if (token != null) {
                entries.set(i, new WebDavEntry(entry.href(), entry.displayName(), entry.directory(),
                        entry.size(), entry.lastModified(), entry.etag(), entry.contentType(), token));
            }
        }
    }

    private void appendChildren(Long userId, Long workspaceId, WebDavPath.Parsed parsed,
                                List<WebDavEntry> entries) {
        String parentPath = relativePath(parsed.segments());
        for (WorkspaceFileDTO child : workspaceService.listFiles(workspaceId, userId, parentPath)) {
            List<String> segments = new ArrayList<>(parsed.segments());
            segments.add(child.getFileName());
            entries.add(entryForChild(parsed, segments, child));
        }
    }

    // ---------------------------------------------------------------- GET / HEAD

    private WebDavResponse get(Long userId, Long workspaceId, WebDavPath.Parsed parsed,
                               HttpServletRequest request, String method) {
        if (parsed.segments().isEmpty()) {
            throw new WebDavException(405, "GET on collection is not supported");
        }
        WorkspaceFile file = findEntry(workspaceId, parsed);
        if (file == null) {
            throw new WebDavException(404, "not found");
        }
        if (Boolean.TRUE.equals(file.getIsDirectory())) {
            throw new WebDavException(405, "GET on collection is not supported");
        }
        long size = file.getFileSize() == null ? 0L : file.getFileSize();
        boolean head = "HEAD".equals(method);
        RangeParser.Spec spec = RangeParser.parse(request.getHeader("Range"), size);
        String contentType = file.getContentType() == null
                ? "application/octet-stream"
                : file.getContentType();

        if (spec.unsatisfiable()) {
            return WebDavResponse.status(416)
                    .header("Content-Range", "bytes */" + size);
        }
        if (!spec.partial()) {
            WebDavResponse response = WebDavResponse.status(200)
                    .header("Content-Type", contentType)
                    .header("Content-Length", String.valueOf(size))
                    .header("Accept-Ranges", "bytes")
                    .header("ETag", etagOf(file))
                    .header("Last-Modified", httpDate(file));
            if (head) {
                return response;
            }
            try {
                WorkspaceService.DownloadResult download =
                        workspaceService.downloadFile(workspaceId, userId, file.getId());
                return WebDavResponse.stream(200, download.inputStream(), download.fileSize())
                        .header("Content-Type", contentType)
                        .header("Content-Length", String.valueOf(download.fileSize()))
                        .header("Accept-Ranges", "bytes")
                        .header("ETag", etagOf(file))
                        .header("Last-Modified", httpDate(file));
            } catch (BusinessException e) {
                throw new WebDavException(httpStatus(e.getCode()), e.getMessage());
            } catch (Exception e) {
                throw new WebDavException(500, "failed to open file");
            }
        }

        long start = spec.offset();
        long end = start + spec.length() - 1;
        WebDavResponse response = WebDavResponse.status(206)
                .header("Content-Type", contentType)
                .header("Content-Range", "bytes " + start + "-" + end + "/" + size)
                .header("Content-Length", String.valueOf(spec.length()))
                .header("Accept-Ranges", "bytes")
                .header("ETag", etagOf(file))
                .header("Last-Modified", httpDate(file));
        if (head) {
            return response;
        }
        try {
            InputStream raw = workspaceService.openFileContentStream(file, start, spec.length());
            return WebDavResponse.stream(206,
                            usageService.meterAndThrottleDownload(userId, raw), spec.length())
                    .header("Content-Type", contentType)
                    .header("Content-Range", "bytes " + start + "-" + end + "/" + size)
                    .header("Content-Length", String.valueOf(spec.length()))
                    .header("Accept-Ranges", "bytes")
                    .header("ETag", etagOf(file))
                    .header("Last-Modified", httpDate(file));
        } catch (Exception e) {
            throw new WebDavException(500, "failed to open file");
        }
    }

    // ---------------------------------------------------------------- PUT

    private WebDavResponse put(Long userId, Long workspaceId, WebDavPath.Parsed parsed,
                               HttpServletRequest request) {
        if (parsed.segments().isEmpty()) {
            throw new WebDavException(405, "PUT on collection is not supported");
        }
        if (request.getRequestURI() != null && request.getRequestURI().endsWith("/")) {
            throw new WebDavException(409, "cannot PUT to a collection");
        }
        long length = request.getContentLengthLong();
        if (length < 0) {
            throw new WebDavException(411, "Length Required");
        }
        ensureParentCollections(workspaceId, userId, parsed.parentPath());

        WorkspaceFile existing = workspaceService.findActiveEntry(
                workspaceId, parsed.parentPath(), parsed.name());
        boolean overwrite = existing != null;
        if (overwrite) {
            if (Boolean.TRUE.equals(existing.getIsDirectory())) {
                throw new WebDavException(405, "target is a collection");
            }
            workspaceService.deleteFile(workspaceId, userId, existing.getId());
        }

        String contentType = request.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }
        try (InputStream in = request.getInputStream()) {
            workspaceService.uploadStream(workspaceId, userId, parsed.parentPath(), parsed.name(),
                    in, length, contentType);
        } catch (BusinessException e) {
            throw mapPutError(e);
        } catch (Exception e) {
            // task-47：禁止 500 + 空体 + 零日志。完整记录方法/路径/长度/类型与堆栈。
            log.error("DAV PUT {} failed: contentLength={} contentType={} overwrite={}",
                    parsed.relativePath(), length, contentType, overwrite, e);
            throw new WebDavException(500, "upload failed");
        }
        return WebDavResponse.status(overwrite ? 204 : 201);
    }

    private WebDavException mapPutError(BusinessException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        // 空间配额不足：WebDAV 语义是 507 Insufficient Storage（不是 413）
        if (e.getCode() == 413 && message.contains("空间不足")) {
            return new WebDavException(507, "Insufficient Storage").header("X-Bond-Code", "413");
        }
        // 档位 max_file_size 超限：413 Payload Too Large
        if (e.getCode() == 400 && message.contains("exceeds plan max limit")) {
            return new WebDavException(413, "Payload Too Large");
        }
        return new WebDavException(httpStatus(e.getCode()), message);
    }

    // ---------------------------------------------------------------- MKCOL

    private WebDavResponse mkcol(Long userId, Long workspaceId, WebDavPath.Parsed parsed) {
        if (parsed.segments().isEmpty()) {
            throw new WebDavException(405, "cannot create a collection on a configured mount");
        }
        ensureParentCollections(workspaceId, userId, parsed.parentPath());
        try {
            workspaceService.createDirectory(workspaceId, userId, parsed.parentPath(), parsed.name());
        } catch (BusinessException e) {
            if (e.getCode() == 405) {
                throw new WebDavException(405, "collection already exists");
            }
            throw new WebDavException(httpStatus(e.getCode()), e.getMessage());
        }
        return WebDavResponse.status(201);
    }

    // ---------------------------------------------------------------- DELETE

    private WebDavResponse delete(Long userId, Long workspaceId, WebDavPath.Parsed parsed) {
        if (parsed.segments().isEmpty()) {
            throw new WebDavException(403, "cannot delete a configured mount");
        }
        WorkspaceFile entry = findEntry(workspaceId, parsed);
        if (entry == null) {
            throw new WebDavException(404, "not found");
        }
        workspaceService.deleteFile(workspaceId, userId, entry.getId());
        return WebDavResponse.status(204);
    }

    // ---------------------------------------------------------------- MOVE / COPY

    private WebDavResponse moveOrCopy(Long userId, Long workspaceId, WebDavPath.Parsed parsed,
                                      HttpServletRequest request, boolean move) {
        if (parsed.segments().isEmpty()) {
            throw new WebDavException(403, "cannot move or copy a configured mount");
        }
        WebDavPath.Parsed destination = WebDavPath.parseDestination(request.getHeader("Destination"));
        if (destination == null) {
            throw new WebDavException(400, "invalid Destination header");
        }
        if (!Objects.equals(destination.workspaceId(), parsed.workspaceId())) {
            throw new WebDavException(403, "cross-workspace move/copy is not supported");
        }
        if (destination.segments().isEmpty()) {
            throw new WebDavException(403, "invalid destination");
        }
        ensureParentCollections(workspaceId, userId, destination.parentPath());

        WorkspaceFile source = findEntry(workspaceId, parsed);
        if (source == null) {
            throw new WebDavException(404, "not found");
        }
        WorkspaceFile target = workspaceService.findActiveEntry(
                workspaceId, destination.parentPath(), destination.name());
        boolean overwrite = target != null;
        assertUnlockedKey(lockKey(workspaceId, destination), request);
        if (overwrite) {
            if ("F".equalsIgnoreCase(request.getHeader("Overwrite"))) {
                throw new WebDavException(412, "destination exists");
            }
            if (target.getId().equals(source.getId())) {
                return WebDavResponse.status(204);
            }
            workspaceService.deleteFile(workspaceId, userId, target.getId());
        }
        if (move) {
            workspaceService.moveEntry(workspaceId, userId, source.getId(),
                    destination.parentPath(), destination.name());
        } else {
            workspaceService.copyEntry(workspaceId, userId, source.getId(),
                    destination.parentPath(), destination.name());
        }
        return WebDavResponse.status(overwrite ? 204 : 201);
    }

    // ---------------------------------------------------------------- LOCK / UNLOCK

    private WebDavResponse lock(Long workspaceId, WebDavPath.Parsed parsed,
                                HttpServletRequest request) {
        if (parsed.segments().isEmpty()) {
            throw new WebDavException(405, "cannot lock a configured mount");
        }
        WorkspaceFile entry = findEntry(workspaceId, parsed);
        if (entry == null) {
            throw new WebDavException(404, "not found");
        }
        long timeoutSeconds = parseTimeoutSeconds(request.getHeader("Timeout"));
        String submitted = submittedLockToken(request);
        if (submitted != null) {
            WebDavLocks.LockInfo refreshed = locks.refresh(submitted, timeoutSeconds);
            if (refreshed != null) {
                return lockResponse(parsed, entry, refreshed.token(), timeoutSeconds);
            }
        }
        WebDavLocks.LockInfo created = locks.lock(lockKey(workspaceId, parsed),
                lockOwner(request), request.getHeader("Depth"), timeoutSeconds);
        return lockResponse(parsed, entry, created.token(), timeoutSeconds);
    }

    private WebDavResponse lockResponse(WebDavPath.Parsed parsed, WorkspaceFile entry, String token,
                                        long timeoutSeconds) {
        String href = hrefFor(parsed, parsed.segments(), Boolean.TRUE.equals(entry.getIsDirectory()));
        return WebDavResponse.xml(200, WebDavXml.lockDiscovery(href, token, timeoutSeconds))
                .header("Lock-Token", "<" + token + ">")
                .header("Timeout", "Second-" + timeoutSeconds);
    }

    private WebDavResponse unlock(Long workspaceId, WebDavPath.Parsed parsed,
                                  HttpServletRequest request) {
        if (parsed.segments().isEmpty()) {
            throw new WebDavException(405, "cannot unlock a configured mount");
        }
        String token = submittedLockToken(request);
        if (token == null) {
            throw new WebDavException(400, "missing Lock-Token");
        }
        WebDavLocks.LockInfo lock = locks.find(token);
        if (lock == null) {
            throw new WebDavException(409, "lock token not found");
        }
        if (!lock.path().equals(lockKey(workspaceId, parsed))) {
            throw new WebDavException(409, "lock token mismatch");
        }
        locks.unlock(token);
        return WebDavResponse.status(204);
    }

    // ---------------------------------------------------------------- helpers

    private WebDavPath.Parsed parseRequestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri != null && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        try {
            return WebDavPath.parse(uri);
        } catch (IllegalArgumentException e) {
            throw new WebDavException(400, "invalid path");
        }
    }

    private Long resolveWorkspace(Long userId, WebDavPath.Parsed parsed) {
        if (parsed.workspaceId() != null) {
            workspaceService.ensureMember(parsed.workspaceId(), userId);
            return parsed.workspaceId();
        }
        return personalSpaceService.resolveWorkspaceId(userId);
    }

    private WorkspaceFile findEntry(Long workspaceId, WebDavPath.Parsed parsed) {
        return workspaceService.findActiveEntry(workspaceId, parsed.parentPath(), parsed.name());
    }

    private void ensureParentCollections(Long workspaceId, Long userId, String parentPath) {
        if (parentPath == null || "/".equals(parentPath) || parentPath.isBlank()) {
            return;
        }
        String[] segments = parentPath.substring(1).split("/");
        String current = "/";
        for (String segment : segments) {
            WorkspaceFile dir = workspaceService.findActiveEntry(workspaceId, current, segment);
            if (dir == null || !Boolean.TRUE.equals(dir.getIsDirectory())) {
                throw new WebDavException(409, "parent collection does not exist");
            }
            current = "/".equals(current) ? "/" + segment : current + "/" + segment;
        }
    }

    private String depthOf(HttpServletRequest request) {
        String depth = request.getHeader("Depth");
        if (depth == null || depth.isBlank()) {
            return "1";
        }
        String normalized = depth.trim().toLowerCase(Locale.ROOT);
        if ("infinity".equals(normalized)) {
            throw new WebDavException(403, "Depth: infinity is not supported");
        }
        if (!"0".equals(normalized) && !"1".equals(normalized)) {
            throw new WebDavException(400, "invalid Depth header");
        }
        return normalized;
    }

    private String relativePath(List<String> segments) {
        return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
    }

    private String lockKey(Long workspaceId, WebDavPath.Parsed parsed) {
        return (workspaceId == null ? "personal" : String.valueOf(workspaceId))
                + ":" + parsed.relativePath();
    }

    private static boolean isWriteMethod(String method) {
        return switch (method) {
            case "PUT", "MKCOL", "DELETE", "MOVE", "COPY" -> true;
            default -> false;
        };
    }

    private void assertUnlocked(Long workspaceId, WebDavPath.Parsed parsed,
                                HttpServletRequest request) {
        assertUnlockedKey(lockKey(workspaceId, parsed), request);
    }

    private void assertUnlockedKey(String key, HttpServletRequest request) {
        List<WebDavLocks.LockInfo> covering = locks.covering(key);
        if (covering.isEmpty()) {
            return;
        }
        String submitted = submittedLockToken(request);
        if (submitted != null && covering.stream().anyMatch(lock -> lock.token().equals(submitted))) {
            return;
        }
        throw new WebDavException(423, "resource is locked");
    }

    private static String submittedLockToken(HttpServletRequest request) {
        String direct = firstLockToken(request.getHeader("Lock-Token"));
        if (direct != null) {
            return direct;
        }
        return firstLockToken(request.getHeader("If"));
    }

    private static String firstLockToken(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        Matcher matcher = LOCK_TOKEN_PATTERN.matcher(header);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static long parseTimeoutSeconds(String header) {
        if (header == null || header.isBlank()) {
            return LOCK_TIMEOUT_SECONDS;
        }
        for (String part : header.split(",")) {
            String value = part.trim();
            if (value.toLowerCase(Locale.ROOT).startsWith("second-")) {
                try {
                    return Math.max(0L, Long.parseLong(value.substring("second-".length()).trim()));
                } catch (NumberFormatException ignored) {
                    return LOCK_TIMEOUT_SECONDS;
                }
            }
        }
        return LOCK_TIMEOUT_SECONDS;
    }

    private static String lockOwner(HttpServletRequest request) {
        try (InputStream in = request.getInputStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = OWNER_BLOCK.matcher(body);
            if (matcher.find()) {
                String value = matcher.group(1).replaceAll("<[^>]*>", " ").trim();
                return value.length() > 512 ? value.substring(0, 512) : value;
            }
        } catch (Exception e) {
            log.debug("failed to read WebDAV lock owner", e);
        }
        return "";
    }

    private WebDavEntry rootEntry(WebDavPath.Parsed parsed) {
        return new WebDavEntry(
                WebDavPath.mountHref(parsed.workspaceId()),
                parsed.workspaceId() == null ? "个人空间" : "工作区",
                true, 0L, Instant.now(), null, CONTENT_TYPE_DIR, null);
    }

    private WebDavEntry entryForFile(WebDavPath.Parsed parsed, List<String> segments,
                                     WorkspaceFile file) {
        boolean directory = Boolean.TRUE.equals(file.getIsDirectory());
        return new WebDavEntry(
                hrefFor(parsed, segments, directory),
                file.getFileName(),
                directory,
                directory ? 0L : (file.getFileSize() == null ? 0L : file.getFileSize()),
                toInstant(file.getCreatedAt()),
                etagOf(file),
                directory ? CONTENT_TYPE_DIR
                        : (file.getContentType() == null ? "application/octet-stream" : file.getContentType()),
                null);
    }

    private WebDavEntry entryForChild(WebDavPath.Parsed parsed, List<String> segments,
                                      WorkspaceFileDTO child) {
        boolean directory = Boolean.TRUE.equals(child.getIsDirectory());
        Instant lastModified = child.getCreatedAt() == null
                ? Instant.now()
                : Instant.ofEpochMilli(child.getCreatedAt());
        return new WebDavEntry(
                hrefFor(parsed, segments, directory),
                child.getFileName(),
                directory,
                directory ? 0L : (child.getFileSize() == null ? 0L : child.getFileSize()),
                lastModified,
                child.getFileHash() == null ? null : '"' + child.getFileHash() + '"',
                directory ? CONTENT_TYPE_DIR
                        : (child.getContentType() == null ? "application/octet-stream" : child.getContentType()),
                null);
    }

    private String hrefFor(WebDavPath.Parsed parsed, List<String> segments, boolean directory) {
        StringBuilder href = new StringBuilder(WebDavPath.mountHref(parsed.workspaceId()));
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                href.append('/');
            }
            href.append(WebDavPath.encodeSegment(segments.get(i)));
        }
        if (directory) {
            href.append('/');
        }
        return href.toString();
    }

    private static String etagOf(WorkspaceFile file) {
        String hash = file.getFileHash();
        if (hash != null && !hash.isBlank()) {
            return '"' + hash + '"';
        }
        String created = file.getCreatedAt() == null
                ? ""
                : String.valueOf(file.getCreatedAt().atZone(ZoneId.systemDefault())
                        .toInstant().toEpochMilli());
        long size = file.getFileSize() == null ? 0L : file.getFileSize();
        return '"' + String.valueOf(file.getId()) + '-' + size + '-' + created + '"';
    }

    private static String httpDate(WorkspaceFile file) {
        Instant instant = toInstant(file.getCreatedAt());
        return instant == null ? null : HTTP_DATE.format(instant);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static int httpStatus(int code) {
        return code >= 400 && code <= 599 ? code : 500;
    }
}
