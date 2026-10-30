package com.bond.common.security;

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

/**
 * 下游服务入口过滤器（bond-auth / bond-transfer 共用，P0 安全）。
 *
 * <p>只接受由网关签名的请求（{@code X-User-Id + X-Gateway-Timestamp + X-Gateway-Nonce +
 * X-Gateway-Signature}）。直连服务端口伪造 X-User-Id 会被这里 401 拒绝。
 * 校验通过后把可信 userId 写入 request attribute，并依赖网关注入的同值 header 供 Controller 使用。</p>
 *
 * <p>共享密钥来自环境变量 {@code INTERNAL_GATEWAY_SECRET}；缺失/过短时构造本 Bean 即失败，
 * 服务无法启动（fail-fast，不允许回退默认值）。白名单与网关共用 {@link PublicEndpoints}。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class InternalAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthFilter.class);

    private final String secret;

    public InternalAuthFilter() {
        this(GatewaySignature.requireSecretFromEnv());
    }

    /** 测试用：直接注入共享密钥（包内可见，避免测试依赖真实环境变量）。 */
    InternalAuthFilter(String secret) {
        this.secret = secret;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        // WebDAV 入口（/dav 与 /dav/**）不走网关签名：网关对该前缀整体跳过 JWT 与身份头注入，
        // /dav 由 WebDavAuthService 用 HTTP Basic 自行鉴权，身份只来自 Basic 解析结果（不读 X-User-Id）。
        // 这里显式放行，避免将来收窄 "/api/" 判定时误伤 WebDAV。见 docs/webdav-design.md §10。
        if (path.equals("/dav") || path.startsWith("/dav/")) {
            return true;
        }
        if (!path.startsWith("/api/")) {
            return true;
        }
        String method = request.getMethod();
        // 预检请求不携带业务数据，放行（CORS 由网关/服务自行处理）
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        return PublicEndpoints.isPublic(method, path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = request.getHeader(GatewaySignature.HEADER_USER_ID);
        String timestamp = request.getHeader(GatewaySignature.HEADER_TIMESTAMP);
        String nonce = request.getHeader(GatewaySignature.HEADER_NONCE);
        String signature = request.getHeader(GatewaySignature.HEADER_SIGNATURE);

        if (!GatewaySignature.verify(secret, userId, timestamp, nonce, signature)) {
            log.warn("Rejected unsigned internal request: method={}, path={}, remote={}",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            boolean missing = userId == null || timestamp == null || nonce == null || signature == null;
            reject(response, missing ? "缺少内部签名凭证" : "内部签名不匹配");
            return;
        }

        request.setAttribute(GatewaySignature.ATTR_VERIFIED_USER_ID, Long.valueOf(userId.trim()));
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":401,\"message\":\"" + message + "\",\"data\":null}");
    }
}
