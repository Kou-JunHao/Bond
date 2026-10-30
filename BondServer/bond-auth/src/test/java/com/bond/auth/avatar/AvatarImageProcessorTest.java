package com.bond.auth.avatar;

import com.bond.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 头像图像处理：格式校验、居中裁切、512×512 JPEG 输出、坏图拒绝（task-152）。 */
class AvatarImageProcessorTest {

    @Test
    @DisplayName("PNG 800×400 → 512×512 JPEG（居中裁切 + 白底）")
    void cropsWidePngToSquareJpeg() throws IOException {
        byte[] png = png(800, 400);

        byte[] jpeg = AvatarImageProcessor.toSquareJpeg(png);

        assertNotNull(jpeg);
        assertTrue(jpeg.length > 500, "JPEG 应有实际内容");
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertEquals(512, out.getWidth());
        assertEquals(512, out.getHeight());
        assertEquals(BufferedImage.TYPE_3BYTE_BGR, out.getType(), "JPEG 解码后应为无 alpha 的 RGB");
    }

    @Test
    @DisplayName("小图（100×60）也放大到 512×512")
    void upscalesSmallImage() throws IOException {
        byte[] png = png(100, 60);

        byte[] jpeg = AvatarImageProcessor.toSquareJpeg(png);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertEquals(512, out.getWidth());
        assertEquals(512, out.getHeight());
    }

    @Test
    @DisplayName("损坏/伪造图片 → 400")
    void rejectsCorruptBytes() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> AvatarImageProcessor.toSquareJpeg("not-an-image".getBytes()));
        assertEquals(400, e.getCode());
    }

    @Test
    @DisplayName("空内容 → 400")
    void rejectsEmptyBytes() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> AvatarImageProcessor.toSquareJpeg(new byte[0]));
        assertEquals(400, e.getCode());
    }

    @Test
    @DisplayName("content-type 或扩展名任一合法即可通过；两者都不认识 → 400")
    void validatesDeclaredTypeOrExtension() {
        AvatarImageProcessor.assertSupported("image/png", "a.png");
        AvatarImageProcessor.assertSupported("application/octet-stream", "a.webp");
        AvatarImageProcessor.assertSupported("image/jpeg", null);
        assertThrows(BusinessException.class,
                () -> AvatarImageProcessor.assertSupported("text/plain", "a.txt"));
    }

    @Test
    @DisplayName("扩展名提取：大小写、无扩展名、带杂串")
    void extractsExtension() {
        assertEquals("png", AvatarImageProcessor.extensionOf("A.PNG"));
        assertEquals("jpg", AvatarImageProcessor.extensionOf("x.y.jpeg".replace("jpeg", "jpg")));
        assertEquals("webp", AvatarImageProcessor.extensionOf("a.webp?x=1"));
        assertEquals("", AvatarImageProcessor.extensionOf("noext"));
        assertEquals("", AvatarImageProcessor.extensionOf(null));
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(0xE5, 0x71, 0x53));
            g.fillRect(0, 0, width, height);
            g.setColor(Color.BLUE);
            g.fillOval(0, 0, Math.max(1, width / 2), Math.max(1, height / 2));
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
