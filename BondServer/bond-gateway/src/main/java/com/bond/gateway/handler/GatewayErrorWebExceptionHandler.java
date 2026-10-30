package com.bond.gateway.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * 网关统一错误出口。
 *
 * <p>背景：下游服务重启/不可达时，Spring Cloud Gateway 会把
 * {@code ConnectException} 之类抛给默认的 {@code DefaultErrorWebExceptionHandler}，
 * 后者统一兜成 <b>500 + Spring 默认错误体</b>。客户端拿到"服务端 500"会误以为
 * 协议/实现有问题，也无法据此做退避重试。</p>
 *
 * <p>本处理器把"依赖短期不可用"映射为 <b>503 + 统一 JSON 信封</b>：
 * {@code {"code":503,"message":"服务暂时不可用，请稍后重试"}}；
 * 其余未预期异常仍为 500，响应体同样使用统一信封且不泄漏堆栈；
 * 框架抛出的 {@link ResponseStatusException}（如 404 无路由、405 等）保留原状态码，
 * 避免把正常的 HTTP 语义兜成 500。</p>
 *
 * <p>响应头回传 {@code X-Request-Id}（请求已带则透传，未带则生成），便于和网关日志对齐。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);

    private static final String MESSAGE_503 = "服务暂时不可用，请稍后重试";
    private static final String MESSAGE_500 = "服务器内部错误";
    private static final String MESSAGE_4XX = "请求无效";
    private static final String HEADER_REQUEST_ID = "X-Request-Id";

    private final ObjectMapper objectMapper;

    public GatewayErrorWebExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            // 响应已经开始写出，无法再改状态码，交给默认处理
            return Mono.error(ex);
        }

        String requestId = resolveRequestId(exchange);
        StatusAndMessage mapped = map(ex);
        HttpStatus status = mapped.status();
        byte[] body = buildBody(status.value(), mapped.message());

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HEADER_REQUEST_ID, requestId);

        if (status.is4xxClientError()) {
            // 4xx 是客户端/路由问题（404 路由未命中、405 等），用 warn + "client error"，
            // 避免污染 `docker logs | grep ERROR` 排障；不带堆栈。
            log.warn("client error requestId={} method={} path={} status={} cause={}",
                    requestId,
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI().getPath(),
                    status.value(),
                    rootCauseName(ex));
        } else if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            // 依赖不可用是预期内的短期故障，用 warn 避免污染 ERROR 统计
            log.warn("downstream unavailable requestId={} method={} path={} cause={}",
                    requestId,
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI().getPath(),
                    rootCauseName(ex));
        } else {
            // 真正的 5xx：error + 堆栈，才值得立刻排查
            log.error("gateway error requestId={} method={} path={} status={}",
                    requestId,
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI().getPath(),
                    status.value(), ex);
        }

        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private StatusAndMessage map(Throwable ex) {
        // 业务/框架 4xx 语义优先（即使 cause 链里带 IO 异常也不要被当成上游故障吞掉）
        if (ex instanceof ResponseStatusException statusException) {
            HttpStatus status = HttpStatus.resolve(statusException.getStatusCode().value());
            if (status != null && status.is4xxClientError()) {
                return new StatusAndMessage(status, MESSAGE_4XX);
            }
        }
        if (isDependencyUnavailable(ex)) {
            return new StatusAndMessage(HttpStatus.SERVICE_UNAVAILABLE, MESSAGE_503);
        }
        return new StatusAndMessage(HttpStatus.INTERNAL_SERVER_ERROR, MESSAGE_500);
    }

    /**
     * 依赖不可用的判定：沿 cause 链最多 16 层找"连接拒绝/路由不可达/DNS/超时/提前关闭"类异常。
     *
     * <p>生产实测补充：容器<b>停止</b>（而非重启窗口）时抛出的是
     * {@code io.netty.channel.AbstractChannel$AnnotatedNoRouteToHostException}
     * （父类 {@link NoRouteToHostException}，再往上 {@link SocketException}），
     * 仅匹配 {@link ConnectException} 会漏成 500。因此这里覆盖：
     * <ul>
     *   <li>{@link SocketException} 全族（ConnectException / NoRouteToHostException 均是其子类）</li>
     *   <li>{@link UnknownHostException}（DNS 解析失败）</li>
     *   <li>{@link SocketTimeoutException}、{@link ClosedChannelException}</li>
     *   <li>reactor-netty 的 {@code PrematureCloseException}、netty 的 Annotated* 包装类</li>
     *   <li>兜底：cause 链<b>前 4 层</b>内的任意 {@link IOException}——网关转发层 IO 失败本质都是上游不可用</li>
     * </ul>
     */
    static boolean isDependencyUnavailable(Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof SocketException
                    || current instanceof UnknownHostException
                    || current instanceof SocketTimeoutException
                    || current instanceof ClosedChannelException
                    || current instanceof ConnectTimeoutException
                    || current instanceof ReadTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof org.springframework.cloud.gateway.support.TimeoutException) {
                return true;
            }
            String name = current.getClass().getName();
            if (name.contains("AnnotatedConnectException")
                    || name.contains("AnnotatedNoRouteToHostException")
                    || name.contains("ConnectTimeoutException")
                    || name.contains("ReadTimeoutException")
                    || name.contains("NoRouteToHostException")
                    || name.contains("UnknownHostException")
                    || name.contains("PrematureCloseException")
                    || name.contains("ClosedChannelException")) {
                return true;
            }
            // 兜底：前 4 层内的 IO 失败（含 SocketException/ClosedChannel 之外的 IO 异常）
            if (depth < 4 && current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private byte[] buildBody(int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            // 理论上不会发生（只有 int/String），兜底手写
            return ("{\"code\":" + code + ",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        }
    }

    private String resolveRequestId(ServerWebExchange exchange) {
        String requestId = exchange.getRequest().getHeaders().getFirst(HEADER_REQUEST_ID);
        if (StringUtils.hasText(requestId)) {
            return requestId;
        }
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String rootCauseName(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getName() + ": " + current.getMessage();
    }

    private record StatusAndMessage(HttpStatus status, String message) {
    }
}
