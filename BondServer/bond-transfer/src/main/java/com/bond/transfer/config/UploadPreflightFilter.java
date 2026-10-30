package com.bond.transfer.config;

import com.bond.common.security.GatewaySignature;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.Entitlements;
import com.bond.transfer.service.ConfigService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 上传请求的 {@code Content-Length} 预检（task-26）。
 *
 * <p>背景：Spring 的 {@code MultipartResolver} 会在 Controller 执行<b>之前</b>把整个请求体
 * 解析并落盘到临时目录。task-25 把 multipart 硬上限提到 20GB（对齐 team 档）后，free 档用户
 * （单文件上限 2GB）可以发一个 20GB 的请求，服务端先吃掉 20GB 临时磁盘，再在业务层被拒绝——
 * 199GB 磁盘几次并发就能打满。</p>
 *
 * <p>本过滤器在过滤链最前端（{@link Ordered#HIGHEST_PRECEDENCE}，早于
 * {@code InternalAuthFilter} 与 {@code MultipartResolver}）只读 {@code Content-Length}
 * 请求头、<b>绝不读取 body</b>：</p>
 * <ul>
 *   <li>仅拦截上传路径的 POST/PUT 请求；</li>
 *   <li>按 {@code X-User-Id} 解析当前用户档位 {@code plans.limits.max_file_size}，
 *       超过即返回 {@code 413 {"code":413,"message":"文件超出当前档位上限"}}，不消费请求体；</li>
 *   <li>未登录（无 {@code X-User-Id}）直接放行，交给后续 JWT / 签名校验处理；</li>
 *   <li>缺少 {@code Content-Length}（chunked）放行，由 multipart/业务层兜底，并只 warn 一次；</li>
 *   <li>分片上传单请求额外受 {@code transfer.max_chunk_size} 约束（含 1MB multipart 边界开销）。</li>
 * </ul>
 *
 * <p>注意：过滤链更前面的 nginx {@code client_max_body_size} 是独立的硬门；Spring 的
 * {@code max-file-size}/{@code max-request-size} 在本过滤器之后生效。三者覆盖关系见
 * {@code BondServer/docs/upload-limits.md}。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadPreflightFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UploadPreflightFilter.class);

    /** 分片请求的默认上限（与 ConfigService 中 transfer.max_chunk_size 默认值一致）。 */
    static final long DEFAULT_MAX_CHUNK_SIZE = 64L * 1024 * 1024;

    /** multipart 边界/头部开销余量（仅用于分片请求；整文件上限按档位严格比较）。 */
    static final long MULTIPART_OVERHEAD = 1024L * 1024;

    private static final List<Pattern> UPLOAD_PATHS = List.of(
            Pattern.compile("^/api/files/upload$"),
            Pattern.compile("^/api/files/chunks(/.*)?$"),
            Pattern.compile("^/api/workspaces/[0-9]+/files/upload$"),
            Pattern.compile("^/api/workspaces/[0-9]+/files/chunks(/.*)?$"),
            Pattern.compile("^/api/transfer/chunks(/.*)?$"),
            Pattern.compile("^/api/transfer/avatar$")
    );

    /** 真正的分片请求体路径（末段为数字 chunkIndex，排除 init/complete/status 等 JSON 接口）。 */
    private static final Pattern CHUNK_BODY_PATH = Pattern.compile(".*/chunks/[^/]+/[0-9]+$");

    private final EntitlementResolver entitlementResolver;
    private final ConfigService configService;

    private final AtomicBoolean missingContentLengthWarned = new AtomicBoolean(false);

    public UploadPreflightFilter(EntitlementResolver entitlementResolver, ConfigService configService) {
        this.entitlementResolver = entitlementResolver;
        this.configService = configService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)) {
            return true;
        }
        return !isUploadPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();

        // 未登录/伪造头：不在这里判定身份，交给后续签名校验（InternalAuthFilter）处理。
        String userIdHeader = request.getHeader(GatewaySignature.HEADER_USER_ID);
        if (userIdHeader == null || userIdHeader.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        Long userId;
        try {
            userId = Long.valueOf(userIdHeader.trim());
        } catch (NumberFormatException e) {
            chain.doFilter(request, response);
            return;
        }

        long maxFileSize;
        try {
            Entitlements entitlements = entitlementResolver.resolveFresh(userId);
            maxFileSize = entitlements.limits().maxFileSize();
        } catch (RuntimeException e) {
            // 档位解析失败不应阻塞上传（后续业务层仍会校验），放行并留日志
            log.warn("upload preflight: failed to resolve entitlements, userId={}, path={}, cause={}",
                    userId, path, e.getMessage());
            chain.doFilter(request, response);
            return;
        }
        if (maxFileSize <= 0) {
            chain.doFilter(request, response);
            return;
        }

        long limit = maxFileSize;
        if (CHUNK_BODY_PATH.matcher(path).matches()) {
            long chunkMax = configService.getLong("transfer.max_chunk_size", DEFAULT_MAX_CHUNK_SIZE);
            if (chunkMax > 0 && chunkMax < limit) {
                limit = chunkMax;
            }
            limit += MULTIPART_OVERHEAD;
        }

        long contentLength = request.getContentLengthLong();
        if (contentLength < 0) {
            // chunked 上传没有 Content-Length：无法预检，放行并让 multipart/业务层兜底
            if (missingContentLengthWarned.compareAndSet(false, true)) {
                log.warn("upload preflight: Content-Length missing (chunked upload?), preflight skipped for path={}, "
                        + "subsequent requests will not log this again", path);
            }
            chain.doFilter(request, response);
            return;
        }

        if (contentLength > limit) {
            log.warn("upload preflight rejected: userId={}, path={}, contentLength={}, planMaxFileSize={}, limit={}",
                    userId, path, contentLength, maxFileSize, limit);
            reject(response);
            return;
        }

        chain.doFilter(request, response);
    }

    private static boolean isUploadPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (Pattern pattern : UPLOAD_PATHS) {
            if (pattern.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":413,\"message\":\"文件超出当前档位上限\"}");
    }
}
