package com.bond.auth.controller;

import com.bond.auth.avatar.AvatarService;
import com.bond.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 头像访问端点：匿名 GET、v 版本校验、长缓存头（task-152）。 */
class UserAvatarControllerTest {

    private final AvatarService avatarService = mock(AvatarService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new UserAvatarController(avatarService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("版本匹配 → 200 image/jpeg + 长缓存（immutable）")
    void returnsImageWithLongCache() throws Exception {
        when(avatarService.load(7L, 1000L)).thenReturn(Optional.of(new byte[]{1, 2, 3}));

        MvcResult result = mockMvc.perform(get("/api/users/7/avatar").param("v", "1000"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn();

        String cacheControl = result.getResponse().getHeader("Cache-Control");
        assertNotNull(cacheControl, "缺少 Cache-Control 头");
        assertTrue(cacheControl.contains("max-age=604800"), "Cache-Control=" + cacheControl);
        assertTrue(cacheControl.contains("public"), "Cache-Control=" + cacheControl);
        assertTrue(cacheControl.contains("immutable"), "Cache-Control=" + cacheControl);
    }

    @Test
    @DisplayName("v 不匹配/缺失 → 404")
    void returns404OnMissingOrStaleVersion() throws Exception {
        when(avatarService.load(7L, 999L)).thenReturn(Optional.empty());
        when(avatarService.load(7L, null)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/users/7/avatar").param("v", "999")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/users/7/avatar")).andExpect(status().isNotFound());
    }
}
