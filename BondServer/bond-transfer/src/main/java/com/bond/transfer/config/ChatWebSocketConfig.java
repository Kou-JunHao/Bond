package com.bond.transfer.config;

import com.bond.transfer.chat.ChatWsHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点注册：{@code /ws/chat?token=<JWT>}。
 * <p>该路径不经网关（nginx 直连 transfer），因此不在网关路由/鉴权里；见 chat-feature-design §1。</p>
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class ChatWebSocketConfig implements WebSocketConfigurer {

    private final ChatWsHandler chatWsHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWsHandler, "/ws/chat")
                .addInterceptors(new ChatWsHandler.ChatWsHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
    }
}
