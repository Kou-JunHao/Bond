package com.bond.transfer.webdav;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * WebDAV 能力探测（OPTIONS）的 servlet 过滤器。
 *
 * <p>为什么不用 MVC handler：Servlet 规范里 OPTIONS 默认由 {@code HttpServlet#doOptions} 处理，
 * Spring MVC 需要显式打开 {@code spring.mvc.dispatch-options-request=true} 才会把 OPTIONS 交给
 * handler mapping；而 WebDAV 客户端（Windows Explorer / Finder / rclone）挂载前**必定**先发
 * OPTIONS 并校验 {@code DAV: 1,2} 响应头，漏掉会导致“服务器不支持 WebDAV”。</p>
 *
 * <p>这里在过滤器层直接应答，不依赖 MVC 配置，也不要求任何凭据（客户端先探测能力、后弹登录框）。
 * 仅当 {@code webdav.enabled=false}（默认）时返回 404，避免暴露挂载点。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebDavOptionsFilter extends OncePerRequestFilter {

    static final String ALLOW_METHODS =
            "OPTIONS, PROPFIND, GET, HEAD, PUT, MKCOL, DELETE, MOVE, COPY, LOCK, UNLOCK";

    private final WebDavAuthService authService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return path == null || !(path.equals("/dav") || path.startsWith("/dav/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 与其它 WebDAV 请求一致：enabled=false → 404；非 https → 403。
            authService.ensureAvailable(request);
        } catch (WebDavException e) {
            response.setStatus(e.getStatus());
            for (java.util.Map.Entry<String, String> header : e.getHeaders().entrySet()) {
                response.setHeader(header.getKey(), header.getValue());
            }
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("DAV", "1,2");
        response.setHeader("MS-Author-Via", "DAV");
        response.setHeader("Allow", ALLOW_METHODS);
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(0);
        response.flushBuffer();
    }
}
