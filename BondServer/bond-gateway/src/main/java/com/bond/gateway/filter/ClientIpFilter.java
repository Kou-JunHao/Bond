package com.bond.gateway.filter;

import com.bond.common.security.ClientIpResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 真实客户端 IP 覆写（task-68）。
 *
 * <p>客户端自带的 {@code X-Forwarded-For} / {@code X-Real-IP} 一律不可信：
 * 本过滤器先剥离这两个头，再根据「直连来源是否属于信任代理」决定采用代理头还是直连地址，
 * 最后把**计算出的单一客户端 IP** 写回两个头。下游 auth 服务因此只会看到网关写入的值，
 * 无法通过伪造头绕过验证码失败计数。</p>
 *
 * <p>必须早于 {@link AuthFilter}（-100）执行，保证鉴权与业务都拿到同一份 IP。</p>
 */
@Component
@Slf4j
public class ClientIpFilter implements GlobalFilter, Ordered {

    private static final long INTERNAL_WARN_INTERVAL_MS = 60_000L;

    private final ClientIpResolver resolver;
    private final AtomicLong lastInternalWarn = new AtomicLong(0L);

    public ClientIpFilter(
            @Value("${bond.gateway.trusted-proxies:" + ClientIpResolver.DEFAULT_TRUSTED_PROXIES + "}") String trustedProxies) {
        this.resolver = new ClientIpResolver(trustedProxies);
    }

    @Override
    public int getOrder() {
        return -200;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String remoteAddr = request.getRemoteAddress() == null
                ? "" : request.getRemoteAddress().getAddress().getHostAddress();
        String clientIp = resolveClientIp(remoteAddr,
                request.getHeaders().getFirst(ClientIpResolver.forwardedForHeader()),
                request.getHeaders().getFirst(ClientIpResolver.realIpHeader()));
        if (ClientIpResolver.isInternalIp(clientIp)) {
            warnInternalIpDegraded(remoteAddr, clientIp, request);
        }
        ServerHttpRequest mutated = request.mutate()
                .headers(headers -> {
                    headers.remove(ClientIpResolver.forwardedForHeader());
                    headers.remove(ClientIpResolver.realIpHeader());
                    if (!clientIp.isEmpty()) {
                        headers.set(ClientIpResolver.forwardedForHeader(), clientIp);
                        headers.set(ClientIpResolver.realIpHeader(), clientIp);
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /** 包级可见，便于单测直接验证决策逻辑。 */
    String resolveClientIp(String remoteAddr, String xForwardedFor, String xRealIp) {
        return resolver.resolve(remoteAddr, xForwardedFor, xRealIp);
    }

    /**
     * 解析结果是内部地址（127.x / 10.x / 172.16-31.x / 192.168.x 等）时打 WARN（每 60s 最多一条）：
     * 说明上游代理头缺失或信任链配置不一致，审计 IP 与 fail2ban 都不可信，需要尽快排查（task-146）。
     */
    private void warnInternalIpDegraded(String remoteAddr, String clientIp, ServerHttpRequest request) {
        long now = System.currentTimeMillis();
        long last = lastInternalWarn.get();
        if (now - last < INTERNAL_WARN_INTERVAL_MS || !lastInternalWarn.compareAndSet(last, now)) {
            return;
        }
        log.warn("gateway client ip degraded: resolved internal ip={} remoteAddr={} hasXRealIp={} hasXff={} path={}",
                clientIp, remoteAddr,
                request.getHeaders().containsKey(ClientIpResolver.realIpHeader()),
                request.getHeaders().containsKey(ClientIpResolver.forwardedForHeader()),
                request.getURI().getPath());
    }
}
