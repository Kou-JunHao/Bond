package com.bond.transfer.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内 WS 会话表（单实例假设；多实例需换 Redis 广播，见 chat-feature-design §7）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatWsHub {

    private final ObjectMapper objectMapper;
    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public void register(Long userId, WebSocketSession session) {
        sessions.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(Long userId, WebSocketSession session) {
        Set<WebSocketSession> set = sessions.get(userId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                sessions.remove(userId, set);
            }
        }
    }

    public boolean isOnline(Long userId) {
        Set<WebSocketSession> set = sessions.get(userId);
        return set != null && !set.isEmpty();
    }

    /**
     * 向若干用户推送一帧：{"t": type, "d": data}。发送失败只记 warn，不影响业务事务。
     * 本方法是同步发送，调用方应放在事务提交之后（ChatService 里用 TransactionSynchronization 或直接发送前已 commit）。
     */
    public void sendToUsers(Collection<Long> userIds, String type, Object data) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        String text;
        try {
            text = objectMapper.writeValueAsString(Map.of("t", type, "d", data));
        } catch (Exception e) {
            log.warn("chat ws frame serialize failed: type={}", type, e);
            return;
        }
        for (Long userId : userIds) {
            Set<WebSocketSession> set = sessions.get(userId);
            if (set == null) {
                continue;
            }
            for (WebSocketSession session : set) {
                try {
                    synchronized (session) {
                        if (session.isOpen()) {
                            session.sendMessage(new TextMessage(text));
                        }
                    }
                } catch (Exception e) {
                    log.warn("chat ws send failed: userId={} type={}", userId, type, e);
                }
            }
        }
    }
}
