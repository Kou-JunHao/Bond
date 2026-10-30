package com.bond.transfer.interceptor;

import com.bond.transfer.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * /api/admin/** 管理员校验。
 *
 * <p>身份头由网关 HMAC 签名保护（{@code InternalAuthFilter} 已验签），这里只做管理员判定；
 * 畸形头/非法值直接 401，不再抛 NumberFormatException 变成 500。</p>
 */
@Component
@RequiredArgsConstructor
public class AdminInterceptor implements HandlerInterceptor {

    private final UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader == null || userIdHeader.isBlank()) {
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "缺少访问令牌");
        }
        final Long userId;
        try {
            userId = Long.parseLong(userIdHeader.trim());
        } catch (NumberFormatException e) {
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "访问令牌格式非法");
        }
        if (userId <= 0) {
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "访问令牌格式非法");
        }
        var user = userMapper.selectById(userId);
        if (user == null || user.getIsAdmin() == null || !user.getIsAdmin()) {
            return reject(response, HttpServletResponse.SC_FORBIDDEN, "需要管理员权限");
        }
        return true;
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
        return false;
    }
}
