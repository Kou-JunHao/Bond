package com.bond.transfer.preview;

import com.bond.common.entity.WorkspaceFile;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.storage.ObjectStorage;
import com.bond.transfer.storage.ObjectStorage.ObjectStat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import static com.bond.transfer.preview.OfficePreviewService.OfficePreviewException.*;

/**
 * Office 文档 → PDF 预览服务（task-59）。
 *
 * <p>流程：源文件（RustFS）→ {@link OfficePdfConverter}（Gotenberg）→ PDF 写入
 * {@code previews/{cacheVersion}/{fileHash}.pdf}（或回退 {@code previews/{cacheVersion}/{fileId}-{size}-{createdAt}.pdf}）
 * → 后续请求直接回源该缓存。缓存**不占用户配额、不计下行流量**（预览由服务端产生，
 * 用户最终看到的是 PDF，不按原文件体积计量）。</p>
 *
 * <p>并发控制：全局 {@code Semaphore(office.preview.concurrency 默认 2)} + 同 key 单飞锁，
 * 避免同一文件被并发重复转换；坏文档进入 10 分钟负缓存（415），转换服务不可用返回 503
 * 且**不缓存**，恢复后可重试。</p>
 */
@Slf4j
@Service
public class OfficePreviewService {

    /** 支持预览的扩展名（Gotenberg LibreOffice 路由支持的主流 Office 格式）。 */
    private static final Set<String> EXTENSIONS = Set.of(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "odt", "ods", "odp", "rtf", "csv");

    public static final String CONTENT_TYPE_PDF = "application/pdf";
    private static final Duration NEGATIVE_CACHE_TTL = Duration.ofMinutes(10);
    /** office-pdf 缓存对象前缀（运维 GC 需要扫描它，故公开）。 */
    public static final String CACHE_PREFIX = "previews/";
    /** 转换器/字体包版本配置键（进缓存键，改镜像或字体别名后必须升版本）。 */
    private static final String CACHE_VERSION_CONFIG = "office.preview.cache_version";
    private static final String DEFAULT_CACHE_VERSION = "2";
    private static final long DEFAULT_MAX_SOURCE_BYTES = 100L * 1024 * 1024;

    /** 表格类文档：xlsx/xls/ods/csv。Gotenberg 默认按「打印区域/分页」导出，宽表会被截列。 */
    private static final Set<String> SPREADSHEET_EXTENSIONS = Set.of("xlsx", "xls", "ods", "csv");

    private final ObjectStorage objectStorage;
    private final OfficePdfConverter converter;
    private final ConfigService configService;
    private final Semaphore concurrency;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> negativeCache = new ConcurrentHashMap<>();

    public OfficePreviewService(ObjectStorage objectStorage,
                                OfficePdfConverter converter,
                                ConfigService configService) {
        this.objectStorage = objectStorage;
        this.converter = converter;
        this.configService = configService;
        this.concurrency = new Semaphore(Math.max(1, configService.getInt("office.preview.concurrency", 2)));
    }

    /** 缓存 PDF 的位置与大小。 */
    public record CachedPdf(String key, long size) {
    }

    /** Office 预览错误基类（携带 HTTP 状态码，控制器映射为真实状态码 + JSON）。 */
    public static class OfficePreviewException extends RuntimeException {
        private final int status;

        protected OfficePreviewException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /** 功能关闭（不该被调用；防御性 404）。 */
    public static class DisabledException extends OfficePreviewException {
        public DisabledException() {
            super(404, "Office 预览未启用");
        }
    }

    /** 非 Office 文档（415）。 */
    public static class UnsupportedTypeException extends OfficePreviewException {
        public UnsupportedTypeException() {
            super(415, "该文件类型不支持 Office 预览");
        }
    }

    /** 源文件超过转换上限（413）。 */
    public static class TooLargeException extends OfficePreviewException {
        public TooLargeException() {
            super(413, "文件超出预览转换上限");
        }
    }

    /** 文档无法转换（415，可负缓存）。 */
    public static class BadDocumentException extends OfficePreviewException {
        public BadDocumentException() {
            super(415, "该文档无法转换预览，请下载后用本地程序打开");
        }
    }

    /** 转换服务不可用（503，不缓存）。 */
    public static class UnavailableException extends OfficePreviewException {
        public UnavailableException() {
            super(503, "文档转换服务暂时不可用，请下载后查看");
        }
    }

    public boolean isEnabled() {
        return configService.getBoolean("office.preview.enabled", true);
    }

    /** 按文件名扩展名判断是否支持 Office 预览。 */
    public static boolean supportsFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        return EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public boolean supports(WorkspaceFile file) {
        return file != null
                && !Boolean.TRUE.equals(file.getIsDirectory())
                && supportsFileName(file.getFileName());
    }

    /**
     * 确保 PDF 缓存存在并返回其 key/size（幂等，多请求单飞）。
     *
     * @throws OfficePreviewException 带真实状态码的领域异常（404/413/415/503）
     */
    public CachedPdf ensure(WorkspaceFile file) {
        if (!isEnabled()) {
            throw new DisabledException();
        }
        if (!supports(file)) {
            throw new UnsupportedTypeException();
        }
        String key = cacheKey(file);
        if (isNegative(key)) {
            throw new BadDocumentException();
        }
        ObjectStat cached = statOrNull(key);
        if (cached != null) {
            return new CachedPdf(key, cached.size());
        }
        Object lock = locks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                if (isNegative(key)) {
                    throw new BadDocumentException();
                }
                ObjectStat again = statOrNull(key);
                if (again != null) {
                    return new CachedPdf(key, again.size());
                }
                long maxSourceBytes = configService.getLong("office.preview.max-source-bytes", DEFAULT_MAX_SOURCE_BYTES);
                if (file.getFileSize() != null && file.getFileSize() > maxSourceBytes) {
                    throw new TooLargeException();
                }
                concurrency.acquire();
                try {
                    long size = convertAndCache(file, key);
                    return new CachedPdf(key, size);
                } finally {
                    concurrency.release();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnavailableException();
        } finally {
            locks.remove(key, lock);
        }
    }

    /**
     * 打开缓存 PDF 的指定字节区间（用于 {@code /office-pdf} 的 Range/206）。
     */
    public InputStream openRange(String key, long totalSize, long offset, long length) {
        if (offset <= 0 && length >= totalSize) {
            return objectStorage.getObject(key);
        }
        return objectStorage.getObject(key, offset, length);
    }

    /**
     * 缓存键：{@code previews/{cacheVersion}/{fileHash}.pdf}，历史数据无 hash 时回退稳定键。
     *
     * <p>{@code cacheVersion}（配置 {@code office.preview.cache_version}）把「转换器镜像 +
     * 字体/别名配置」的版本编进键里：升级 Gotenberg 镜像、改字体别名后必须改这个值，
     * 否则修好字体后用户仍会命中旧的空白 PDF（task-161 血泪教训）。</p>
     */
    String cacheKey(WorkspaceFile file) {
        String prefix = CACHE_PREFIX + cacheVersion() + "/";
        String hash = file.getFileHash();
        if (hash != null && !hash.isBlank()) {
            return prefix + hash.trim().toLowerCase(Locale.ROOT) + ".pdf";
        }
        long created = file.getCreatedAt() == null ? 0L : file.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        return prefix + file.getId() + "-" + (file.getFileSize() == null ? 0L : file.getFileSize()) + "-" + created + ".pdf";
    }

    /**
     * 转换器/字体包版本（进缓存键）。默认 {@code 2}（task-161 修正字体别名后从裸 hash 升级而来）。
     *
     * <p>只允许 {@code [A-Za-z0-9._-]}，并去掉连续点，避免配置值被拼成对象路径穿越（如 {@code ../x}）。</p>
     */
    String cacheVersion() {
        String raw = configService.get(CACHE_VERSION_CONFIG, DEFAULT_CACHE_VERSION);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CACHE_VERSION;
        }
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "_");
        while (cleaned.startsWith(".") || cleaned.startsWith("-")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isEmpty()) {
            return DEFAULT_CACHE_VERSION;
        }
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }

    private long convertAndCache(WorkspaceFile file, String key) {
        String endpoint = configService.get("office.preview.endpoint", "http://bond-gotenberg:3000");
        long timeoutSeconds = Math.max(1, configService.getInt("office.preview.timeout-seconds", 90));
        long sourceSize = file.getFileSize() == null ? 0L : file.getFileSize();
        Map<String, String> formFields = formFieldsOf(file);
        Path temp = null;
        try (InputStream source = objectStorage.getObject(file.getMinioPath());
             InputStream pdf = formFields.isEmpty()
                     ? converter.convert(endpoint, Duration.ofSeconds(timeoutSeconds),
                             file.getFileName(), source, sourceSize)
                     : converter.convert(endpoint, Duration.ofSeconds(timeoutSeconds),
                             file.getFileName(), source, sourceSize, formFields)) {
            temp = Files.createTempFile("bond-office-preview-", ".pdf");
            Files.copy(pdf, temp, StandardCopyOption.REPLACE_EXISTING);
            long size = Files.size(temp);
            if (!isPdf(temp)) {
                markNegative(key);
                log.warn("office preview: converter returned non-PDF, fileId={} ext={}",
                        file.getId(), extensionOf(file));
                throw new BadDocumentException();
            }
            warnIfNoExtractableText(file, temp);
            try (InputStream in = Files.newInputStream(temp)) {
                objectStorage.putObject(key, in, size, CONTENT_TYPE_PDF);
            }
            log.info("office preview converted: fileId={} size={} cached={}", file.getId(), size, key);
            return size;
        } catch (OfficePdfConverter.BadDocumentException e) {
            markNegative(key);
            log.warn("office preview: document rejected by converter, fileId={} ext={} reason={}",
                    file.getId(), extensionOf(file), abbreviate(e.getMessage(), 200));
            throw new BadDocumentException();
        } catch (OfficePdfConverter.ConversionUnavailableException e) {
            log.error("office preview: converter unavailable, fileId={} ext={} reason={}",
                    file.getId(), extensionOf(file), abbreviate(e.getMessage(), 200));
            throw new UnavailableException();
        } catch (OfficePreviewException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.error("office preview: conversion failed, fileId={} ext={} error={}",
                    file.getId(), extensionOf(file), e.getClass().getSimpleName());
            throw new UnavailableException();
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.debug("failed to delete office preview temp file: {}", temp, e);
                }
            }
        }
    }

    /** 小写扩展名（仅日志用；未取到返回空串）。 */
    /**
     * 表格类文档默认开启 {@code singlePageSheets}：Gotenberg/LibreOffice 按表格自身的打印区域
     * 与分页符导出 PDF，宽表会被截列、多工作表各自分页，客户端看到「简化版」。开启后每个工作表
     * 整表缩到一页（内容完整但字更小），可用 {@code office.preview.single-page-sheets=false} 关闭。
     */
    private Map<String, String> formFieldsOf(WorkspaceFile file) {
        if (SPREADSHEET_EXTENSIONS.contains(extensionOf(file))
                && configService.getBoolean("office.preview.single-page-sheets", true)) {
            return Map.of("singlePageSheets", "true");
        }
        return Map.of();
    }

    /**
     * 字体缺失的「软」检测：CJK 字体缺失时 LibreOffice 仍会产出结构合法的 PDF，只是文字全空。
     * 这里对可提取文本做一次快速体检，空白则 WARN 并带上 fileId/扩展名/页数——用于日志取证，
     * **不改变返回码**：空白 PDF 与配置错误（如误开页数范围）难以区分，直接 415 会误杀正常文档。
     */
    private void warnIfNoExtractableText(WorkspaceFile file, Path pdf) {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            String text = new PDFTextStripper().getText(document);
            if (text == null || text.isBlank()) {
                log.warn("office preview: PDF has no extractable text (possible missing CJK glyphs), fileId={} ext={} pages={}",
                        file.getId(), extensionOf(file), document.getNumberOfPages());
            }
        } catch (Exception e) {
            log.debug("office preview: text sanity check skipped, fileId={} reason={}",
                    file.getId(), e.getClass().getSimpleName());
        }
    }

    private static String extensionOf(WorkspaceFile file) {
        String name = file == null ? null : file.getFileName();
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 日志里的下游错误摘要截断（避免整段 HTML 错误页刷日志）。 */
    private static String abbreviate(String value, int max) {
        String text = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private ObjectStat statOrNull(String key) {        try {
            return objectStorage.statObject(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isNegative(String key) {
        Long expireAt = negativeCache.get(key);
        if (expireAt == null) {
            return false;
        }
        if (expireAt < System.currentTimeMillis()) {
            negativeCache.remove(key, expireAt);
            return false;
        }
        return true;
    }

    private void markNegative(String key) {
        negativeCache.put(key, System.currentTimeMillis() + NEGATIVE_CACHE_TTL.toMillis());
    }

    /** 校验转换结果是不是 PDF（防止 Gotenberg 异常返回 HTML/JSON 被当缓存写入）。 */
    private static boolean isPdf(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] magic = in.readNBytes(5);
            return new String(magic, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-");
        }
    }
}
