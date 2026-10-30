package com.bond.transfer.preview;

import com.bond.common.entity.WorkspaceFile;
import com.bond.transfer.storage.ObjectStorage;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 缩略图生成 + RustFS 缓存（cache-first，失败一律返回 null → 上层 204）。
 *
 * <p>类型：图片 ImageIO / PDF PDFBox / 视频 ffmpeg 抽帧；文本与未知类型不生成。
 * 缓存 key：{@code thumbnails/{fileId}-{width}.jpg}（系统数据，不计入用户配额）。
 * 同一文件同一尺寸并发只生成一次；同时最多 2 个生成任务（信号量）。</p>
 */
@Slf4j
@Service
public class ThumbnailService {

    public static final List<Integer> ALLOWED_WIDTHS = List.of(64, 128, 320, 640);
    public static final int DEFAULT_WIDTH = 320;
    /** PDF 分页预览默认宽度（预览要能看清正文，比缩略图大一档）。 */
    public static final int DEFAULT_PREVIEW_WIDTH = 640;

    private static final long IMAGE_PDF_MAX_BYTES = 200L * 1024 * 1024;
    private static final long VIDEO_MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_CACHED_THUMB_BYTES = 10 * 1024 * 1024;
    private static final float JPEG_QUALITY = 0.8f;

    /** 缩略图在对象存储中的统一前缀（系统数据，不计入用户配额；由 GC 兜底清理）。 */
    public static final String CACHE_PREFIX = "thumbnails/";

    public enum Kind { IMAGE, PDF, VIDEO, UNSUPPORTED }

    private final ObjectStorage objectStorage;
    private final String ffmpegPath;
    private final Semaphore generationSlots = new Semaphore(2);
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final AtomicReference<Boolean> ffmpegAvailable = new AtomicReference<>();

    public ThumbnailService(ObjectStorage objectStorage,
                            @Value("${preview.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.objectStorage = objectStorage;
        this.ffmpegPath = ffmpegPath;
    }

    public record Thumb(byte[] jpeg, String etag) {
    }

    /**
     * PDF 单页渲染结果。{@code jpeg == null} 表示页码越界（totalPages 为真实页数，供 416 带 X-Total-Pages）。
     */
    public record Page(byte[] jpeg, int totalPages, String etag) {
    }

    /** 尺寸白名单：非法/空/非数字回退默认 320（不接受任意尺寸）。 */
    public static int resolveWidth(Integer requested) {
        if (requested == null || !ALLOWED_WIDTHS.contains(requested)) {
            return DEFAULT_WIDTH;
        }
        return requested;
    }

    /** 宽内容错：直接接收原始 query 字符串，非数字/非法值一律回退 320。 */
    public static int resolveWidth(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_WIDTH;
        }
        try {
            return resolveWidth(Integer.valueOf(requested.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_WIDTH;
        }
    }

    /** 预览页尺寸：白名单与缩略图一致，默认 640（非法值回退 640）。 */
    public static int resolvePreviewWidth(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_PREVIEW_WIDTH;
        }
        try {
            int width = Integer.parseInt(requested.trim());
            return ALLOWED_WIDTHS.contains(width) ? width : DEFAULT_PREVIEW_WIDTH;
        } catch (NumberFormatException e) {
            return DEFAULT_PREVIEW_WIDTH;
        }
    }

    public static Kind detectKind(String contentType, String fileName) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String ext = extensionOf(fileName);
        if (ct.startsWith("image/") || List.of("jpg", "jpeg", "png", "gif", "bmp", "webp").contains(ext)) {
            return Kind.IMAGE;
        }
        if (ct.equals("application/pdf") || ext.equals("pdf")) {
            return Kind.PDF;
        }
        if (ct.startsWith("video/") || List.of("mp4", "mov", "mkv", "webm", "m4v", "avi").contains(ext)) {
            return Kind.VIDEO;
        }
        return Kind.UNSUPPORTED;
    }

    /** 返回缩略图；不生成（类型不支持/超限/ffmpeg 缺失/失败）时返回 null。 */
    public Thumb getOrGenerate(WorkspaceFile file, int width) {
        if (file == null || file.getMinioPath() == null) {
            return null;
        }
        Kind kind = detectKind(file.getContentType(), file.getFileName());
        long size = file.getFileSize() == null ? 0L : file.getFileSize();
        if (kind == Kind.UNSUPPORTED) {
            return null;
        }
        if ((kind == Kind.IMAGE || kind == Kind.PDF) && size > IMAGE_PDF_MAX_BYTES) {
            log.warn("Skip thumbnail: image/pdf too large, fileId={}, size={}", file.getId(), size);
            return null;
        }
        if (kind == Kind.VIDEO && size > VIDEO_MAX_BYTES) {
            log.warn("Skip thumbnail: video too large, fileId={}, size={}", file.getId(), size);
            return null;
        }
        if (kind == Kind.VIDEO && !isFfmpegAvailable()) {
            // 首次探测已 warn 一次；这里只 debug，避免每个视频卡片刷一条 warn
            log.debug("Skip video thumbnail: ffmpeg not available, fileId={}", file.getId());
            return null;
        }

        String key = cacheKey(file, width);
        String etag = etag(file, width);
        byte[] cached = readCached(key);
        if (cached != null) {
            return new Thumb(cached, etag);
        }

        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            cached = readCached(key);
            if (cached != null) {
                return new Thumb(cached, etag);
            }
            boolean acquired;
            try {
                acquired = generationSlots.tryAcquire(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (!acquired) {
                log.warn("Skip thumbnail: generation slots busy, fileId={}", file.getId());
                return null;
            }
            try {
                byte[] jpeg = generate(file, kind, width);
                if (jpeg == null) {
                    return null;
                }
                try {
                    objectStorage.putObject(key, new ByteArrayInputStream(jpeg), jpeg.length, "image/jpeg");
                } catch (Exception e) {
                    log.warn("Failed to cache thumbnail {}: {}", key, e.toString());
                }
                return new Thumb(jpeg, etag);
            } catch (Exception e) {
                log.warn("Thumbnail generation failed: fileId={}, kind={}, error={}", file.getId(), kind, e.toString());
                return null;
            } finally {
                generationSlots.release();
            }
        } finally {
            lock.unlock();
            locks.remove(key, lock);
        }
    }

    /**
     * PDF 单页预览：仅 PDF；图片缓存 {@code thumbnails/{fileId}-{width}-p{page}.jpg}，
     * 页数缓存 {@code thumbnails/{fileId}-pages.txt}（均在现有缩略图缓存目录，不计入用户配额）。
     *
     * <p>按请求 open/close {@link PDDocument}，绝不跨线程共享；生成仍受全局 Semaphore(2) 约束。
     * 页码越界返回 {@code Page(null, totalPages, etag)}（控制器回 416 + X-Total-Pages）；
     * 非 PDF / 超限 / 生成失败返回 null（控制器回 204）。</p>
     */
    public Page previewPage(WorkspaceFile file, int pageNumber, int width) {
        if (file == null || file.getMinioPath() == null) {
            return null;
        }
        if (detectKind(file.getContentType(), file.getFileName()) != Kind.PDF) {
            return null;
        }
        long size = file.getFileSize() == null ? 0L : file.getFileSize();
        if (size > IMAGE_PDF_MAX_BYTES) {
            log.warn("Skip pdf preview: file too large, fileId={}, size={}", file.getId(), size);
            return null;
        }
        int page = Math.max(1, pageNumber);
        String key = previewCacheKey(file, width, page);
        String pageCountKey = pageCountCacheKey(file);
        String etag = previewEtag(file, width, page);

        Integer knownTotal = readCachedPageCount(pageCountKey);
        if (knownTotal != null && page > knownTotal) {
            return new Page(null, knownTotal, etag);
        }
        byte[] cached = readCached(key);
        if (cached != null && knownTotal != null) {
            return new Page(cached, knownTotal, etag);
        }

        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            Integer total = readCachedPageCount(pageCountKey);
            if (total != null && page > total) {
                return new Page(null, total, etag);
            }
            cached = readCached(key);
            if (cached != null && total != null) {
                return new Page(cached, total, etag);
            }
            boolean acquired;
            try {
                acquired = generationSlots.tryAcquire(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (!acquired) {
                log.warn("Skip pdf preview: generation slots busy, fileId={}", file.getId());
                return null;
            }
            try {
                byte[] original = readAllLimit(objectStorage.getObject(file.getMinioPath()), IMAGE_PDF_MAX_BYTES);
                try (PDDocument document = Loader.loadPDF(original)) {
                    int pages = document.getNumberOfPages();
                    if (pages <= 0) {
                        return null;
                    }
                    cachePageCount(pageCountKey, pages);
                    if (page > pages) {
                        return new Page(null, pages, etag);
                    }
                    BufferedImage image = new PDFRenderer(document).renderImage(page - 1, 1.5f);
                    byte[] jpeg = toJpeg(image, width);
                    try {
                        objectStorage.putObject(key, new ByteArrayInputStream(jpeg), jpeg.length, "image/jpeg");
                    } catch (Exception e) {
                        log.warn("Failed to cache pdf preview {}: {}", key, e.toString());
                    }
                    return new Page(jpeg, pages, etag);
                }
            } catch (Exception e) {
                log.warn("PDF preview generation failed: fileId={}, page={}, error={}",
                        file.getId(), page, e.toString());
                return null;
            } finally {
                generationSlots.release();
            }
        } finally {
            lock.unlock();
            locks.remove(key, lock);
        }
    }

    // ── 生成实现 ──

    private byte[] generate(WorkspaceFile file, Kind kind, int width) throws Exception {
        return switch (kind) {
            case IMAGE -> imageThumb(file, width);
            case PDF -> pdfThumb(file, width);
            case VIDEO -> videoThumb(file, width);
            case UNSUPPORTED -> null;
        };
    }

    private byte[] imageThumb(WorkspaceFile file, int width) throws Exception {
        byte[] original = readAllLimit(objectStorage.getObject(file.getMinioPath()), IMAGE_PDF_MAX_BYTES);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));
        if (image == null) {
            // 例如 ImageIO 不支持 webp：回退 204
            return null;
        }
        return toJpeg(image, width);
    }

    private byte[] pdfThumb(WorkspaceFile file, int width) throws Exception {
        byte[] original = readAllLimit(objectStorage.getObject(file.getMinioPath()), IMAGE_PDF_MAX_BYTES);
        try (PDDocument document = Loader.loadPDF(original)) {
            if (document.getNumberOfPages() <= 0) {
                return null;
            }
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage page = renderer.renderImage(0, 1.5f);
            return toJpeg(page, width);
        }
    }

    private byte[] videoThumb(WorkspaceFile file, int width) throws Exception {
        Path temp = Files.createTempFile("bond-thumb-", videoSuffix(file.getFileName()));
        try {
            try (InputStream in = objectStorage.getObject(file.getMinioPath());
                 OutputStream out = Files.newOutputStream(temp)) {
                in.transferTo(out);
            }
            Process process = new ProcessBuilder(ffmpegPath, "-y", "-ss", "1", "-i", temp.toString(),
                    "-frames:v", "1", "-f", "image2pipe", "-vcodec", "mjpeg", "-")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] frame = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0 || frame.length == 0) {
                return null;
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(frame));
            return image == null ? null : toJpeg(image, width);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
            }
        }
    }

    boolean isFfmpegAvailable() {
        Boolean known = ffmpegAvailable.get();
        if (known != null) {
            return known;
        }
        boolean available;
        try {
            Process process = new ProcessBuilder(ffmpegPath, "-version")
                    .redirectErrorStream(true).start();
            try (InputStream in = process.getInputStream()) {
                in.readAllBytes();
            }
            available = process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            available = false;
        }
        ffmpegAvailable.set(available);
        if (!available) {
            log.warn("ffmpeg is not available ({}); video thumbnails will return 204", ffmpegPath);
        }
        return available;
    }

    // ── 缓存生命周期 ──

    /**
     * 删除某个文件对应的全部缩略图缓存（多尺寸 + PDF 分页 + 页数）。
     *
     * <p>文件删除时调用；任何异常只记 warn，不影响主流程（清理失败由每日 GC 兜底）。</p>
     */
    public void evictFile(Long fileId) {
        if (fileId == null) {
            return;
        }
        String prefix = CACHE_PREFIX + fileId + "-";
        try {
            for (ObjectStorage.StoredObject object : objectStorage.listObjects(prefix)) {
                try {
                    objectStorage.deleteObject(object.key());
                } catch (RuntimeException e) {
                    log.warn("failed to delete thumbnail {}: {}", object.key(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("failed to evict thumbnails for file {}: {}", fileId, e.getMessage());
        }
    }

    /** 从缩略图对象 key（{@code thumbnails/{fileId}-...}）解析 fileId；无法解析返回 null。 */
    public static Long parseFileId(String key) {
        if (key == null || !key.startsWith(CACHE_PREFIX)) {
            return null;
        }
        int dash = key.indexOf('-', CACHE_PREFIX.length());
        if (dash <= CACHE_PREFIX.length()) {
            return null;
        }
        try {
            return Long.valueOf(key.substring(CACHE_PREFIX.length(), dash));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── 缓存/编码工具 ──

    String cacheKey(WorkspaceFile file, int width) {
        return "thumbnails/" + file.getId() + "-" + width + ".jpg";
    }

    String previewCacheKey(WorkspaceFile file, int width, int page) {
        return "thumbnails/" + file.getId() + "-" + width + "-p" + page + ".jpg";
    }

    String pageCountCacheKey(WorkspaceFile file) {
        return "thumbnails/" + file.getId() + "-pages.txt";
    }

    String previewEtag(WorkspaceFile file, int width, int page) {
        try {
            String raw = file.getId() + ":" + width + ":" + page + ":" + file.getFileSize() + ":"
                    + (file.getFileHash() == null ? "" : file.getFileHash());
            byte[] digest = MessageDigest.getInstance("MD5").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return "\"page-" + sb + "\"";
        } catch (Exception e) {
            return "\"page-" + file.getId() + "-" + width + "-" + page + "\"";
        }
    }

    String etag(WorkspaceFile file, int width) {
        try {
            String raw = file.getId() + ":" + width + ":" + file.getFileSize() + ":"
                    + (file.getFileHash() == null ? "" : file.getFileHash());
            byte[] digest = MessageDigest.getInstance("MD5").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return "\"thumb-" + sb + "\"";
        } catch (Exception e) {
            return "\"thumb-" + file.getId() + "-" + width + "\"";
        }
    }

    private byte[] readCached(String key) {
        try {
            if (!objectStorage.objectExists(key)) {
                return null;
            }
            return readAllLimit(objectStorage.getObject(key), MAX_CACHED_THUMB_BYTES);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer readCachedPageCount(String key) {
        try {
            if (!objectStorage.objectExists(key)) {
                return null;
            }
            byte[] data = readAllLimit(objectStorage.getObject(key), 32);
            return Integer.valueOf(new String(data, java.nio.charset.StandardCharsets.US_ASCII).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private void cachePageCount(String key, int totalPages) {
        try {
            byte[] data = Integer.toString(totalPages).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            objectStorage.putObject(key, new ByteArrayInputStream(data), data.length, "text/plain");
        } catch (Exception e) {
            log.warn("Failed to cache pdf page count {}: {}", key, e.toString());
        }
    }

    private static byte[] readAllLimit(InputStream in, long limit) throws Exception {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buf)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IllegalStateException("content exceeds limit: " + limit);
                }
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        }
    }

    static byte[] toJpeg(BufferedImage source, int width) throws Exception {
        int targetWidth = Math.max(1, Math.min(width, source.getWidth()));
        int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * (targetWidth / (double) source.getWidth())));
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(scaled, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String videoSuffix(String fileName) {
        String ext = extensionOf(fileName);
        return ext.isBlank() ? ".bin" : "." + ext;
    }
}
