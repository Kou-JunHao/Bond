package com.bond.transfer.interceptor;

import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AdminInterceptor implements HandlerInterceptor {

    private final UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader == null) {
            throw new BusinessException(401, "未认证");
        }
        Long userId = Long.parseLong(userIdHeader);
        var user = userMapper.selectById(userId);
        if (user == null || user.getIsAdmin() == null || !user.getIsAdmin()) {
            throw new BusinessException(403, "需要管理员权限");
        }
        return true;
    }
}
