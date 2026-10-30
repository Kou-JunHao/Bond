package com.bond.transfer.chat;

import com.bond.common.utils.JwtUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * `/ws/chat?token=&lt;JWT&gt;` 的握手鉴权与心跳。
 * <p>WS 不经网关（nginx 直连 transfer），所以这里自己校验 JWT：无效 token 握手直接拒绝；
 * 连接建立后客户端每 30s 发 {"t":"ping"}，服务端回 {"t":"pong"}。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatWsHandler extends TextWebSocketHandler {

    private final ChatWsHub hub;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = userId(session);
        if (userId == null) {
            safeClose(session);
            return;
        }
        hub.register(userId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long userId = userId(session);
        if (userId == null) {
            safeClose(session);
            return;
        }
        String payload = message.getPayload();
        String type = null;
        try {
            JsonNode node = objectMapper.readTree(payload);
            type = node.path("t").asText(null);
        } catch (Exception e) {
            log.debug("chat ws ignore malformed frame from userId={}", userId);
        }
        if ("ping".equals(type)) {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage("{\"t\":\"pong\",\"d\":{\"ts\":" + System.currentTimeMillis() + "}}"));
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = userId(session);
        if (userId != null) {
            hub.unregister(userId, session);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Long userId = userId(session);
        log.debug("chat ws transport error: userId={} err={}", userId, exception.toString());
        safeClose(session);
    }

    private Long userId(WebSocketSession session) {
        Object value = session.getAttributes().get(ChatWsHandshakeInterceptor.ATTR_USER_ID);
        return value instanceof Long id ? id : null;
    }

    private void safeClose(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception ignored) {
            // 关不掉就算了，容器最终会回收
        }
    }

    /**
     * 握手阶段从 query 解析 token 并校验，把 userId 放进 session attributes。
     */
    public static class ChatWsHandshakeInterceptor implements HandshakeInterceptor {

        public static final String ATTR_USER_ID = "chatUserId";

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       org.springframework.web.socket.WebSocketHandler wsHandler,
                                       Map<String, Object> attributes) {
            String token = queryParam(request.getURI().getRawQuery(), "token");
            if (token == null || token.isBlank() || !JwtUtils.isTokenValid(token)) {
                // 不升级：直接以 HTTP 401 拒绝握手（不会先 101 再 close）
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }
            try {
                Long userId = JwtUtils.getUserId(token);
                if (userId == null) {
                    response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                    return false;
                }
                attributes.put(ATTR_USER_ID, userId);
                return true;
            } catch (Exception e) {
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   org.springframework.web.socket.WebSocketHandler wsHandler, Exception exception) {
            // no-op
        }

        /** 手动解析 query（token 只含 JWT 的 base64url 字符，无需考虑解码）。 */
        static String queryParam(String rawQuery, String name) {
            if (rawQuery == null || rawQuery.isEmpty()) {
                return null;
            }
            for (String pair : rawQuery.split("&")) {
                int idx = pair.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                if (name.equals(pair.substring(0, idx))) {
                    return pair.substring(idx + 1);
                }
            }
            return null;
        }
    }
}
