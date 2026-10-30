package com.bond.transfer.controller;

import com.bond.common.dto.Result;
import com.bond.transfer.chat.ChatFileAccessService;
import com.bond.transfer.preview.OfficePreviewService;
import com.bond.transfer.preview.PreviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 聊天文件跨账号访问（task-84 方案 C 的客户端接口）。
 *
 * <p>签发一步（需登录，{@code POST /api/chat/files/{messageId}/tokens}）；之后
 * {@code meta/content/thumbnail/office-pdf} 都只凭短令牌访问，令牌根绑 messageId，
 * 无令牌/令牌失效/资源不存在统一 404（不泄露消息或文件是否存在）；签发时会话存在但非成员返回 403。</p>
 */
@RestController
@RequestMapping("/api/chat/files")
@RequiredArgsConstructor
public class ChatFileController {

    private static final String NO_STORE = "no-store";

    private final ChatFileAccessService chatFiles;

    // ------------------------------------------------------------------ 签发

    @PostMapping("/{messageId}/tokens")
    public Result<ChatFileAccessService.Tokens> tokens(@RequestHeader("X-User-Id") Long userId,
                                                       @PathVariable Long messageId) {
        return Result.ok(chatFiles.issue(messageId, userId));
    }

    // ------------------------------------------------------------------ 读取

    @GetMapping("/meta")
    public Result<ChatFileAccessService.FileMeta> meta(@RequestParam(required = false) String token) {
        if (token == null || token.isBlank()) {
            throw new com.bond.common.exception.BusinessException(404, "文件不存在或已失效");
        }
        try {
            return Result.ok(chatFiles.meta(token));
        } catch (PreviewService.InvalidPreviewTokenException e) {
            throw new com.bond.common.exception.BusinessException(404, "文件不存在或已失效");
        }
    }

    @GetMapping("/content")
    public ResponseEntity<InputStreamResource> content(
            @RequestParam(required = false) String token,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        try {
            PreviewService.Content content = chatFiles.openContent(token, range);
            ResponseEntity.BodyBuilder builder = ResponseEntity
                    .status(content.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, content.contentType())
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encodeFileName(content.fileName()));
            if (content.partial()) {
                builder.header(HttpHeaders.CONTENT_RANGE, "bytes " + content.offset() + "-"
                        + (content.offset() + content.length() - 1) + "/" + content.totalSize());
            }
            return builder.contentLength(content.length())
                    .body(new InputStreamResource(content.stream()));
        } catch (PreviewService.InvalidPreviewTokenException e) {
            return ResponseEntity.notFound().build();
        } catch (PreviewService.UnsatisfiableRangeException e) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + e.getTotalSize())
                    .build();
        }
    }

    @GetMapping("/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@RequestParam(required = false) String w,
                                            @RequestParam(required = false) String token,
                                            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH,
                                                    required = false) String ifNoneMatch) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        try {
            PreviewService.Thumbnail thumb = chatFiles.thumbnail(token, w);
            if (thumb == null) {
                return ResponseEntity.noContent().build();
            }
            if (ifNoneMatch != null && ifNoneMatch.contains(thumb.etag())) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(thumb.etag()).build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .eTag(thumb.etag())
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600")
                    .body(thumb.jpeg());
        } catch (PreviewService.InvalidPreviewTokenException e) {
            return ResponseEntity.notFound().build();
        } catch (PreviewService.ThumbnailRateLimitedException e) {
            return error(HttpStatus.TOO_MANY_REQUESTS.value(), "缩略图请求过于频繁，请稍后重试",
                    String.valueOf(e.getRetryAfterSeconds()));
        }
    }

    @GetMapping("/office-pdf")
    public ResponseEntity<?> officePdf(
            @RequestParam(required = false) String token,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        try {
            PreviewService.Content content = chatFiles.openOfficePdf(token, range);
            ResponseEntity.BodyBuilder builder = ResponseEntity
                    .status(content.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, content.contentType())
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename*=UTF-8''" + encodeFileName(content.fileName()));
            if (content.partial()) {
                builder.header(HttpHeaders.CONTENT_RANGE, "bytes " + content.offset() + "-"
                        + (content.offset() + content.length() - 1) + "/" + content.totalSize());
            }
            return builder.contentLength(content.length())
                    .body(new InputStreamResource(content.stream()));
        } catch (PreviewService.InvalidPreviewTokenException e) {
            return ResponseEntity.notFound().build();
        } catch (PreviewService.UnsatisfiableRangeException e) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + e.getTotalSize())
                    .build();
        } catch (OfficePreviewService.OfficePreviewException e) {
            return error(e.getStatus(), e.getMessage(), null);
        }
    }

    // ------------------------------------------------------------------ 工具

    private static ResponseEntity<byte[]> error(int status, String message, String retryAfter) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON);
        if (retryAfter != null) {
            builder.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        String body = "{\"code\":" + status + ",\"message\":\"" + escapeJson(message) + "\"}";
        return builder.body(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeJson(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String encodeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
