package com.bond.transfer.share;

import com.bond.common.dto.Result;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.preview.PreviewService;
import com.bond.transfer.share.ShareDtos.CreateShareRequest;
import com.bond.transfer.share.ShareDtos.PatchShareRequest;
import com.bond.transfer.share.ShareDtos.ShareLinkView;
import com.bond.transfer.share.ShareDtos.VerifyRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 分享链接接口：
 * <ul>
 *   <li>{@code /api/shares}：登录态管理（创建/列表/撤销），由网关注入 X-User-Id；</li>
 *   <li>{@code /s/{token}...}：匿名访问（落地页/提取码校验/下载），无需登录，
 *       已在 {@code PublicEndpoints} 白名单；网关与下游都不做 JWT/HMAC 校验。</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class ShareController {

    private static final Logger log = LoggerFactory.getLogger(ShareController.class);

    private final ShareLinkService shareLinkService;

    // ── 登录态：管理 ──

    @GetMapping("/api/shares")
    public Result<List<ShareLinkView>> list(@RequestHeader("X-User-Id") Long userId,
                                            @RequestParam(required = false) Long workspaceId) {
        return Result.ok(shareLinkService.list(userId, workspaceId));
    }

    @PostMapping("/api/shares")
    public Result<ShareLinkView> create(@RequestHeader("X-User-Id") Long userId,
                                        @RequestBody(required = false) CreateShareRequest request) {
        return Result.ok(shareLinkService.create(userId, request));
    }

    /** 单个分享链接详情。 */
    @GetMapping("/api/shares/{id}")
    public Result<ShareLinkView> detail(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        return Result.ok(shareLinkService.get(userId, id));
    }

    @DeleteMapping("/api/shares/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        shareLinkService.revoke(userId, id);
    }

    /**
     * 修改分享链接（task-163，仅创建者）。三态语义见 {@link PatchShareRequest}：
     * 字段缺失=不改、显式 null=清空、有值=设置；已过期可通过改期重新生效。
     */
    @PatchMapping("/api/shares/{id}")
    public Result<ShareLinkView> patch(@RequestHeader("X-User-Id") Long userId,
                                       @PathVariable Long id,
                                       @RequestBody(required = false) PatchShareRequest request,
                                       HttpServletRequest httpRequest) {
        return Result.ok(shareLinkService.patch(userId, id, request, clientIp(httpRequest)));
    }

    // ── 匿名：落地页 / 校验 / 下载 ──

    @GetMapping(value = "/s/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> landing(@PathVariable String token) {
        MediaType html = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);
        try {
            return ResponseEntity.ok()
                    .contentType(html)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Robots-Tag", "noindex, nofollow")
                    .body(shareLinkService.landingHtml(token));
        } catch (BusinessException e) {
            // task-163：撤销/过期/次数用尽/不存在都必须给中文 HTML 说明页（不是 JSON/空白），
            // 且失效页不泄露文件名、大小、创建者。
            HttpStatus status = HttpStatus.resolve(shareLinkService.landingStatus(e));
            return ResponseEntity.status(status == null ? HttpStatus.GONE : status)
                    .contentType(html)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Robots-Tag", "noindex, nofollow")
                    .body(shareLinkService.invalidLandingHtml(e));
        }
    }

    @PostMapping("/s/{token}/verify")
    public Result<Void> verify(@PathVariable String token,
                               @RequestBody(required = false) VerifyRequest request,
                               HttpServletRequest httpRequest) {
        shareLinkService.verify(token, request == null ? null : request.code(), clientIp(httpRequest));
        return Result.ok();
    }

    /**
     * 匿名下载。单文件 → 支持 Range 的流式下载（206/416 语义不变）；
     * 文件夹分享（task-183）→ 服务端实时打包的 ZIP 流（无 Content-Length、分块传输、
     * 不支持 Range；一次 ZIP 下载按 1 次计）。
     */
    @GetMapping("/s/{token}/download")
    public ResponseEntity<?> download(@PathVariable String token,
                                      @RequestParam(value = "code", required = false) String code,
                                      @RequestHeader(value = "Range", required = false) String rangeHeader,
                                      HttpServletRequest httpRequest) {
        ShareLinkService.ShareDownloadPlan plan;
        try {
            plan = shareLinkService.planDownload(token, code, rangeHeader, clientIp(httpRequest));
        } catch (PreviewService.UnsatisfiableRangeException e) {
            // 与预览接口一致：416 + 真实总大小，客户端可据此校正 Range
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + e.getTotalSize())
                    .build();
        }
        if (plan.zip() != null) {
            return zipResponse(plan.zip());
        }
        ShareLinkService.ShareDownload download = plan.file();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, mediaType(download.contentType()));
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(download.fileName()));
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(download.length()));
        if (download.partial()) {
            long end = download.offset() + download.length() - 1;
            headers.set(HttpHeaders.CONTENT_RANGE,
                    "bytes " + download.offset() + "-" + end + "/" + download.totalSize());
        }
        HttpStatus status = download.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
        return new ResponseEntity<>(new InputStreamResource(download.stream()), headers, status);
    }

    /**
     * 文件夹分享的 ZIP 响应：大小未知 → 不设 Content-Length，走 chunked；边打包边写响应流。
     * 所有 DB 校验（链接/提取码/次数/目录存在性）都在返回前完成，流内失败只记 WARN。
     * <p>2026-10-30 生产 500 复盘：原实现把 {@code StreamingResponseBody} 放进 {@code ResponseEntity<?>}，
     * Spring 按「方法声明返回类型」的泛型选择 handler，解析不到 → 落到 HttpMessageConverters →
     * {@code HttpMessageNotWritableException: No converter for ...$$Lambda}。改为 {@link Resource} 后，
     * ResourceHttpMessageConverter 按实际 body 类型写出，天然支持未知长度的分块流。</p>
     */
    private ResponseEntity<Resource> zipResponse(ShareLinkService.ShareZipDownload zip) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, ShareLinkService.ZIP_CONTENT_TYPE);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(zipFileName(zip.folderName())));
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        headers.set("X-Content-Type-Options", "nosniff");

        try {
            PipedOutputStream pipeOut = new PipedOutputStream();
            PipedInputStream pipeIn = new PipedInputStream(pipeOut, 64 * 1024);
            Thread writer = new Thread(() -> {
                try (OutputStream out = pipeOut) {
                    shareLinkService.writeZip(zip, out);
                } catch (Exception e) {
                    // 客户端断开 / 对象被删：响应可能已提交，只能记录，不能改状态码
                    log.warn("share zip stream failed: folder={}", zip.folderName(), e);
                }
            }, "share-zip");
            writer.setDaemon(true);
            writer.start();
            return new ResponseEntity<>(new ZipStreamResource(pipeIn, zipFileName(zip.folderName())),
                    headers, HttpStatus.OK);
        } catch (IOException e) {
            throw new BusinessException(500, "无法生成 ZIP 下载流");
        }
    }

    /** ZIP 分块流资源：长度未知（contentLength() = -1），由容器按 chunked 输出。 */
    private static final class ZipStreamResource extends AbstractResource {

        private final InputStream stream;
        private final String filename;

        ZipStreamResource(InputStream stream, String filename) {
            this.stream = stream;
            this.filename = filename;
        }

        @Override
        public String getDescription() {
            return "share-zip:" + filename;
        }

        @Override
        public InputStream getInputStream() {
            return stream;
        }

        @Override
        public long contentLength() {
            return -1L;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }

    private static String zipFileName(String folderName) {
        String name = folderName == null || folderName.isBlank() ? "share" : folderName.trim();
        return name.toLowerCase(Locale.ROOT).endsWith(".zip") ? name : name + ".zip";
    }

    private static String mediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        try {
            return MediaType.parseMediaType(contentType).toString();
        } catch (IllegalArgumentException e) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }

    private static String contentDisposition(String fileName) {
        String name = fileName == null || fileName.isBlank() ? "download" : fileName;
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
