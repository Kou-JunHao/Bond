package com.bond.transfer.preview;

import com.bond.common.entity.WorkspaceFile;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 预览统一编排：短期令牌校验 → 文件归属校验（越权 404）→ 内容流 / 缩略图。
 *
 * <p>内容走应用服务器流式透传（RustFS 仍只绑 127.0.0.1，不向公网暴露），支持 Range/206；
 * 缩略图生成失败/不支持一律返回 null，由控制器回 204。</p>
 */
@Service
@RequiredArgsConstructor
public class PreviewService {

    private final PreviewTokenService tokens;
    private final WorkspaceService workspaceService;
    private final ThumbnailService thumbnails;
    private final UsageService usageService;
    private final ThumbnailRateLimiter thumbnailRateLimiter;
    private final OfficePreviewService officePreview;

    /**
     * 预览入口集合。
     *
     * <p>{@code officePdfUrl} 仅当服务端开启 Office 预览且文件扩展名受支持时非空；
     * 客户端据此决定「在线预览 / 下载后查看」。</p>
     */
    public record PreviewUrl(String contentUrl, String thumbnailUrl, String previewUrlTemplate, String officePdfUrl,
                             long expiresIn, String contentType, String fileName) {
    }

    /** PDF 分页结果；{@code jpeg == null} 表示页码越界（totalPages 为真实页数）。 */
    public record Page(byte[] jpeg, int totalPages, String etag) {
    }

    public record Content(InputStream stream, long totalSize, long offset, long length,
                          boolean partial, String contentType, String fileName) {
    }

    public record Thumbnail(byte[] jpeg, String etag) {
    }

    /** 无效/过期/绑定的 fileId 不符（统一按 404 处理，不泄露存在性）。 */
    public static class InvalidPreviewTokenException extends RuntimeException {
    }

    public static class UnsatisfiableRangeException extends RuntimeException {
        private final long totalSize;

        public UnsatisfiableRangeException(long totalSize) {
            this.totalSize = totalSize;
        }

        public long getTotalSize() {
            return totalSize;
        }
    }

    /**
     * 缩略图按用户软限流触发（{@link ThumbnailRateLimiter}）。
     *
     * <p>控制器据此返回 {@code 429 + Retry-After}；客户端应降级为类型图标，缩略图失败不得阻塞列表。</p>
     */
    public static class ThumbnailRateLimitedException extends RuntimeException {
        private final long retryAfterSeconds;

        public ThumbnailRateLimitedException(long retryAfterSeconds) {
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    // ── 短期 URL 签发（需要登录态：X-User-Id） ──

    public PreviewUrl personalPreviewUrls(Long userId, Long fileId) {
        WorkspaceFile file = workspaceService.getPersonalFileForPreview(userId, fileId);
        return buildUrls(file, userId, "/api/files/" + file.getId());
    }

    public PreviewUrl workspacePreviewUrls(Long userId, Long workspaceId, Long fileId) {
        WorkspaceFile file = workspaceService.getFileForPreview(workspaceId, userId, fileId);
        return buildUrls(file, userId, "/api/workspaces/" + workspaceId + "/files/" + file.getId());
    }

    private PreviewUrl buildUrls(WorkspaceFile file, Long userId, String basePath) {
        String contentToken = tokens.issue(file.getId(), userId, PreviewTokenService.SCOPE_CONTENT);
        String thumbToken = tokens.issue(file.getId(), userId, PreviewTokenService.SCOPE_THUMB);
        String previewToken = tokens.issue(file.getId(), userId, PreviewTokenService.SCOPE_PREVIEW);
        // Office 预览按需在首次请求时触发转换（可能较慢），URL 总是签发；是否支持由客户端按扩展名判断
        String officePdfUrl = null;
        if (officePreview.isEnabled() && officePreview.supports(file)) {
            String officeToken = tokens.issue(file.getId(), userId, PreviewTokenService.SCOPE_OFFICE);
            officePdfUrl = basePath + "/office-pdf?token=" + officeToken;
        }
        return new PreviewUrl(
                basePath + "/content?token=" + contentToken,
                basePath + "/thumbnail?w=" + ThumbnailService.DEFAULT_WIDTH + "&token=" + thumbToken,
                // 客户端把 {page} 替换成 1..X-Total-Pages；越界由服务端回 416 + X-Total-Pages
                basePath + "/preview?page={page}&w=" + ThumbnailService.DEFAULT_PREVIEW_WIDTH + "&token=" + previewToken,
                officePdfUrl,
                tokens.getTtlSeconds(),
                contentTypeOf(file),
                file.getFileName());
    }

    // ── 内容流（令牌在 query，无需 Authorization 头，浏览器/播放器可直接用） ──

    public Content openPersonalContent(String token, Long fileId, String rangeHeader) {
        PreviewTokenService.Ticket ticket = tokens.resolve(token, fileId, PreviewTokenService.SCOPE_CONTENT)
                .orElseThrow(InvalidPreviewTokenException::new);
        WorkspaceFile file = workspaceService.getPersonalFileForPreview(ticket.userId(), fileId);
        return openContent(file, ticket.userId(), rangeHeader);
    }

    public Content openWorkspaceContent(String token, Long workspaceId, Long fileId, String rangeHeader) {
        PreviewTokenService.Ticket ticket = tokens.resolve(token, fileId, PreviewTokenService.SCOPE_CONTENT)
                .orElseThrow(InvalidPreviewTokenException::new);
        WorkspaceFile file = workspaceService.getFileForPreview(workspaceId, ticket.userId(), fileId);
        return openContent(file, ticket.userId(), rangeHeader);
    }

    private Content openContent(WorkspaceFile file, Long userId, String rangeHeader) {
        RangeParser.Spec spec = RangeParser.parse(rangeHeader, file.getFileSize());
        if (spec.unsatisfiable()) {
            throw new UnsatisfiableRangeException(file.getFileSize());
        }
        try {
            InputStream raw = workspaceService.openFileContentStream(file, spec.offset(), spec.length());
            // 只计下行：预览/播放同样视为下载；超额降速不断线。
            // 计量按实际读出的字节数在流 close 时原子累加（客户端中断也按已送出量计）
            InputStream stream = usageService.meterAndThrottleDownload(userId, raw);
            return new Content(stream, file.getFileSize(), spec.offset(), spec.length(), spec.partial(),
                    contentTypeOf(file), file.getFileName());
        } catch (UnsatisfiableRangeException | InvalidPreviewTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to open content stream: " + file.getId(), e);
        }
    }

    // ── Office → PDF 预览（scope=office；不计量、支持 Range） ──

    public Content openPersonalOfficePdf(String token, Long fileId, String rangeHeader) {
        PreviewTokenService.Ticket ticket = tokens.resolve(token, fileId, PreviewTokenService.SCOPE_OFFICE)
                .orElseThrow(InvalidPreviewTokenException::new);
        WorkspaceFile file = workspaceService.getPersonalFileForPreview(ticket.userId(), fileId);
        return openOfficePdf(file, rangeHeader);
    }

    public Content openWorkspaceOfficePdf(String token, Long workspaceId, Long fileId, String rangeHeader) {
        PreviewTokenService.Ticket ticket = tokens.resolve(token, fileId, PreviewTokenService.SCOPE_OFFICE)
                .orElseThrow(InvalidPreviewTokenException::new);
        WorkspaceFile file = workspaceService.getFileForPreview(workspaceId, ticket.userId(), fileId);
        return openOfficePdf(file, rangeHeader);
    }

    /**
     * Office→PDF：转换结果缓存于 RustFS，按 Range 流式返回。
     *
     * <p><b>不计量下行</b>：转换产物是服务端生成的预览，不按原文件体积计流量，也不占用户空间配额；
     * 用户若要原件仍走 {@code /content} 或 {@code /download} 正常计量。</p>
     */
    private Content openOfficePdf(WorkspaceFile file, String rangeHeader) {
        OfficePreviewService.CachedPdf pdf = officePreview.ensure(file);
        RangeParser.Spec spec = RangeParser.parse(rangeHeader, pdf.size());
        if (spec.unsatisfiable()) {
            throw new UnsatisfiableRangeException(pdf.size());
        }
        InputStream stream = officePreview.openRange(pdf.key(), pdf.size(), spec.offset(), spec.length());
        return new Content(stream, pdf.size(), spec.offset(), spec.length(), spec.partial(),
                OfficePreviewService.CONTENT_TYPE_PDF, officePdfFileName(file));
    }

    /** 预览文件名：保留原名，扩展名换成 .pdf，避免浏览器/播放器按原格式打开失败。 */
    public static String officePdfFileName(WorkspaceFile file) {
        String name = file.getFileName() == null ? "preview" : file.getFileName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + ".pdf";
    }

    // ── 缩略图 ──

    public Thumbnail personalThumbnail(String token, Long fileId, String width) {        PreviewTokenService.Ticket ticket = tokens.resolve(token, fileId, PreviewTokenService.SCOPE_THUMB)
                .orElseThrow(InvalidPreviewTokenException::new);
        WorkspaceFile file = workspaceService.getPersonalFileForPreview(ticket.userId(), fileId);
        return thumbnail(file, ticket.userId(), width);
    }

    public Thumbnail workspaceThumbnail(String token, Long workspaceId, Long fileId, String width) {
        PreviewTokenService.Ticket ticket = tokens.resolve(token, fileId, PreviewTokenService.SCOPE_THUMB)
                .orElseThrow(InvalidPreviewTokenException::new);
        WorkspaceFile file = workspaceService.getFileForPreview(workspaceId, ticket.userId(), fileId);
        return thumbnail(file, ticket.userId(), width);
    }

    private Thumbnail thumbnail(WorkspaceFile file, Long userId, String width) {
        // 缩略图不计流量但可被刷：按用户软限流，超限 429（客户端降级图标），不静默 204
        long retryAfter = thumbnailRateLimiter.acquire(userId);
        if (retryAfter > 0) {
            throw new ThumbnailRateLimitedException(retryAfter);
        }
        int effectiveWidth = ThumbnailService.resolveWidth(width);
        ThumbnailService.Thumb thumb = thumbnails.getOrGenerate(file, effectiveWidth);
        return thumb == null ? null : new Thumbnail(thumb.jpeg(), thumb.etag());
    }

    // ── PDF 分页预览（scope=preview） ──

    public Page personalPreviewPage(String token, Long fileId, String page, String width) {
        PreviewTokenService.Ticket ticket = tokens.resolve(token, fileId, PreviewTokenService.SCOPE_PREVIEW)
                .orElseThrow(InvalidPreviewTokenException::new);
        WorkspaceFile file = workspaceService.getPersonalFileForPreview(ticket.userId(), fileId);
        return previewPage(file, page, width);
    }

    public Page workspacePreviewPage(String token, Long workspaceId, Long fileId, String page, String width) {
        PreviewTokenService.Ticket ticket = tokens.resolve(token, fileId, PreviewTokenService.SCOPE_PREVIEW)
                .orElseThrow(InvalidPreviewTokenException::new);
        WorkspaceFile file = workspaceService.getFileForPreview(workspaceId, ticket.userId(), fileId);
        return previewPage(file, page, width);
    }

    private Page previewPage(WorkspaceFile file, String page, String width) {
        int effectivePage = resolvePage(page);
        int effectiveWidth = ThumbnailService.resolvePreviewWidth(width);
        ThumbnailService.Page rendered = thumbnails.previewPage(file, effectivePage, effectiveWidth);
        return rendered == null ? null : new Page(rendered.jpeg(), rendered.totalPages(), rendered.etag());
    }

    /**
     * page 从 1 开始；非法值（非数字/空/0/负数）回退第 1 页——有确定且安全的默认行为，不该让客户端画错误态。
     */
    public static int resolvePage(String raw) {
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        try {
            int page = Integer.parseInt(raw.trim());
            return page < 1 ? 1 : page;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    public static String contentTypeOf(WorkspaceFile file) {
        if (file.getContentType() != null && !file.getContentType().isBlank()) {
            return file.getContentType();
        }
        return MediaTypeFactory.getMediaType(file.getFileName())
                .map(MediaType::toString)
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }
}
