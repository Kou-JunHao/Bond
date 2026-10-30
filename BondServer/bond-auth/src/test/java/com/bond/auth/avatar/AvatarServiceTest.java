package com.bond.auth.avatar;

import com.bond.auth.mapper.UserMapper;
import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 头像服务：上传/删除/版本校验/旧 avatarUrl 加固（task-152）。 */
@ExtendWith(MockitoExtension.class)
class AvatarServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private AvatarStorage avatarStorage;

    @InjectMocks
    private AvatarService avatarService;

    @Test
    @DisplayName("上传：写对象 + 显式 UPDATE 自托管 URL 与版本时间")
    void uploadStoresObjectAndUpdatesUser() throws IOException {
        User user = new User();
        user.setId(7L);
        when(userMapper.selectById(7L)).thenReturn(user);

        avatarService.upload(7L, pngMultipart(600, 300, "avatar.png"));

        verify(avatarStorage).put(eq(7L), any(byte[].class));
        verify(userMapper).update(eq(null), any());
    }

    @Test
    @DisplayName("上传：超过 2MB → 400，且不写对象/不写库")
    void rejectsOversize() {
        MockMultipartFile big = new MockMultipartFile("file", "big.png", "image/png", new byte[3 * 1024 * 1024]);
        BusinessException e = assertThrows(BusinessException.class, () -> avatarService.upload(7L, big));
        assertEquals(400, e.getCode());
        verify(avatarStorage, never()).put(anyLong(), any());
    }

    @Test
    @DisplayName("上传：非图片 → 400")
    void rejectsNonImage() {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hi".getBytes());
        BusinessException e = assertThrows(BusinessException.class, () -> avatarService.upload(7L, file));
        assertEquals(400, e.getCode());
    }

    @Test
    @DisplayName("删除：显式清空 avatar_url 并异步删对象（幂等）")
    void deleteClearsAndDeletesObject() {
        avatarService.delete(7L);

        verify(userMapper).update(eq(null), any());
        verify(avatarStorage, org.mockito.Mockito.timeout(2000)).delete(7L);
    }

    @Test
    @DisplayName("读取：v 与库中 avatar_updated_at 不一致 → 404（empty）")
    void loadRejectsStaleVersion() {
        User user = new User();
        user.setId(7L);
        user.setAvatarUrl("/api/users/7/avatar?v=1000");
        user.setAvatarUpdatedAt(OffsetDateTime.parse("2026-10-27T10:00:00+08:00"));
        when(userMapper.selectById(7L)).thenReturn(user);

        assertTrue(avatarService.load(7L, 1000L).isEmpty());
        verify(avatarStorage, never()).get(anyLong());
    }

    @Test
    @DisplayName("读取：版本匹配 → 返回对象字节")
    void loadMatchesVersion() {
        OffsetDateTime at = OffsetDateTime.parse("2026-10-27T10:00:00+08:00");
        User user = new User();
        user.setId(7L);
        user.setAvatarUrl("/api/users/7/avatar?v=" + at.toEpochSecond());
        user.setAvatarUpdatedAt(at);
        when(userMapper.selectById(7L)).thenReturn(user);
        byte[] bytes = new byte[]{1, 2, 3};
        when(avatarStorage.get(7L)).thenReturn(Optional.of(bytes));

        assertArrayEquals(bytes, avatarService.load(7L, at.toEpochSecond()).orElseThrow());
    }

    @Test
    @DisplayName("加固：PUT profile 的 avatarUrl 只接受清空或本人自托管路径")
    void normalizesProfileAvatarUrl() {
        assertNull(AvatarService.normalizeSelfHostedAvatarUrl(7L, null));
        assertNull(AvatarService.normalizeSelfHostedAvatarUrl(7L, "  "));
        assertEquals("/api/users/7/avatar?v=5",
                AvatarService.normalizeSelfHostedAvatarUrl(7L, "/api/users/7/avatar?v=5"));

        assertThrows(BusinessException.class,
                () -> AvatarService.normalizeSelfHostedAvatarUrl(7L, "https://tracker.example/pixel.gif"));
        assertThrows(BusinessException.class,
                () -> AvatarService.normalizeSelfHostedAvatarUrl(7L, "/api/users/8/avatar?v=5"));
        assertThrows(BusinessException.class,
                () -> AvatarService.normalizeSelfHostedAvatarUrl(7L, "/api/users/7/avatar/../../etc"));
        assertThrows(BusinessException.class,
                () -> AvatarService.normalizeSelfHostedAvatarUrl(7L, "javascript:alert(1)"));
    }

    @Test
    @DisplayName("自托管 URL 形状：/api/users/{id}/avatar?v={epochSecond}")
    void buildsSelfHostedUrl() {
        assertEquals("/api/users/42/avatar?v=1700000000", AvatarService.selfHostedUrl(42L, 1700000000L));
    }

    private static MockMultipartFile pngMultipart(int w, int h, String name) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.ORANGE);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", name, "image/png", out.toByteArray());
    }
}
