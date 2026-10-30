package com.bond.gateway.filter;

import com.bond.common.security.GatewaySignature;
import com.bond.common.security.PublicEndpoints;
import com.bond.common.utils.JwtUtils;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 网关鉴权 + 服务间签名（P0 安全）。
 *
 * <ol>
 *   <li>任何请求先剥离客户端自带的 {@code X-User-Id / X-Gateway-*}（防头走私）；</li>
 *   <li>{@code /dav/**} 按前缀整体放行（HTTP Basic 由 transfer 的 WebDAV 入口解析）；</li>
 *   <li>白名单用精确匹配（{@link PublicEndpoints}，与下游过滤器共用）；</li>
 *   <li>非白名单请求校验 JWT；</li>
 *   <li>校验通过后用 {@code INTERNAL_GATEWAY_SECRET} 对 {@code userId:timestamp:nonce} 做 HMAC-SHA256，
 *       注入 4 个内部头；下游 {@code InternalAuthFilter} 验签后才信任 X-User-Id。</li>
 * </ol>
 *
 * <p>task-126：401 统一为 {@code {"code":401,"message":...,"data":null}} 信封，
 * 并区分「缺少令牌 / 格式错误 / 已过期 / 无效」，同时给出 {@code WWW-Authenticate} 头，
 * 客户端可以据此决定「跳登录」还是「提示无权限」。</p>
 *
 * <p>密钥缺失/过短时构造 Bean 即失败，网关无法启动（不允许空密钥回退）。</p>
 */
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    private static final String WWW_AUTHENTICATE_REALM = "Bearer realm=\"Bond\"";
    private static final String WWW_AUTHENTICATE_EXPIRED =
            "Bearer realm=\"Bond\", error=\"invalid_token\", error_description=\"token expired\"";

    private final String internalSecret;

    public AuthFilter() {
        this.internalSecret = GatewaySignature.requireSecretFromEnv();
    }

    /** 测试用：直接注入共享密钥（包内可见，避免测试依赖真实环境变量）。 */
    AuthFilter(String internalSecret) {
        this.internalSecret = internalSecret;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();
        String methodName = method != null ? method.name() : "";

        // 1) 剥离客户端伪造的内部头（对所有请求生效，包括白名单）
        ServerHttpRequest sanitized = request.mutate()
                .headers(headers -> {
                    headers.remove(GatewaySignature.HEADER_USER_ID);
                    headers.remove(GatewaySignature.HEADER_TIMESTAMP);
                    headers.remove(GatewaySignature.HEADER_NONCE);
                    headers.remove(GatewaySignature.HEADER_SIGNATURE);
                })
                .build();
        ServerWebExchange sanitizedExchange = exchange.mutate().request(sanitized).build();

        // 2) /dav/** 走 HTTP Basic（由 transfer 的 WebDavController 自己解析）：
        //    按前缀整体跳过 JWT 与内部签名注入，否则 PROPFIND/MKCOL 等会被 401 拦掉。
        if (path.equals("/dav") || path.startsWith("/dav/")) {
            return chain.filter(sanitizedExchange);
        }

        // 3) 白名单（精确匹配，避免 startsWith 前缀绕过）
        if (HttpMethod.OPTIONS.equals(method) || PublicEndpoints.isPublic(methodName, path)) {
            return chain.filter(sanitizedExchange);
        }

        // 4) JWT：区分“没带/格式错/过期/无效”，给出可操作提示
        String authHeader = sanitized.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || authHeader.isBlank()) {
            return unauthorized(sanitizedExchange, "缺少访问令牌", null);
        }
        if (!authHeader.startsWith("Bearer ")) {
            return unauthorized(sanitizedExchange, "凭证格式错误（应为 Bearer <token>）", null);
        }
        String token = authHeader.substring("Bearer ".length()).trim();
        JwtUtils.TokenState state = JwtUtils.inspect(token);
        if (state == JwtUtils.TokenState.EXPIRED) {
            return unauthorized(sanitizedExchange, "登录已过期，请重新登录", WWW_AUTHENTICATE_EXPIRED);
        }
        if (state != JwtUtils.TokenState.VALID) {
            return unauthorized(sanitizedExchange, "登录凭证无效", null);
        }

        // 5) HMAC 签名注入（userId + 时间戳 + 一次性 nonce）
        Long userId = JwtUtils.getUserId(token);
        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String signature = GatewaySignature.sign(internalSecret, userId, timestamp, nonce);

        ServerHttpRequest signedRequest = sanitized.mutate()
                .header(GatewaySignature.HEADER_USER_ID, String.valueOf(userId))
                .header(GatewaySignature.HEADER_TIMESTAMP, String.valueOf(timestamp))
                .header(GatewaySignature.HEADER_NONCE, nonce)
                .header(GatewaySignature.HEADER_SIGNATURE, signature)
                .build();
        return chain.filter(sanitizedExchange.mutate().request(signedRequest).build());
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message,
                                    String wwwAuthenticate) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
                wwwAuthenticate == null ? WWW_AUTHENTICATE_REALM : wwwAuthenticate);
        byte[] body = ("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Flux.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
