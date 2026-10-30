package com.bond.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 头像端点白名单（task-152）：只放行精确形状的 GET，其余方法/路径仍需登录与鉴权。 */
class AvatarPublicEndpointsTest {

    @Test
    @DisplayName("GET /api/users/{id}/avatar 匿名可读")
    void avatarGetIsPublic() {
        assertTrue(PublicEndpoints.isPublic("GET", "/api/users/7/avatar"));
        assertTrue(PublicEndpoints.isPublic("get", "/api/users/123456789012345678/avatar"));
    }

    @Test
    @DisplayName("非精确形状不放行：缺 id、尾斜杠、子路径、大小写路径")
    void rejectsNonExactShapes() {
        assertFalse(PublicEndpoints.isPublic("GET", "/api/users//avatar"));
        assertFalse(PublicEndpoints.isPublic("GET", "/api/users/7/avatar/"));
        assertFalse(PublicEndpoints.isPublic("GET", "/api/users/7/avatar/extra"));
        assertFalse(PublicEndpoints.isPublic("GET", "/api/users/avatar"));
        assertFalse(PublicEndpoints.isPublic("GET", "/api/users/7/profile"));
    }

    @Test
    @DisplayName("非 GET 方法不放行（POST 上传 / DELETE 删除仍需登录）")
    void rejectsOtherMethods() {
        assertFalse(PublicEndpoints.isPublic("POST", "/api/users/7/avatar"));
        assertFalse(PublicEndpoints.isPublic("DELETE", "/api/users/7/avatar"));
        assertFalse(PublicEndpoints.isPublic("PUT", "/api/users/7/avatar"));
    }

    @Test
    @DisplayName("路径穿越/编码点号不放行")
    void rejectsTraversal() {
        assertFalse(PublicEndpoints.isPublic("GET", "/api/users/../7/avatar"));
        assertFalse(PublicEndpoints.isPublic("GET", "/api/users/%2e%2e/7/avatar"));
        assertFalse(PublicEndpoints.isPublic("GET", "/api/users/7//avatar"));
    }
}
