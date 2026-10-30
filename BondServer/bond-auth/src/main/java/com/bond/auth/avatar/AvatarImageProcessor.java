package com.bond.auth.avatar;

import com.bond.common.exception.BusinessException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * 头像图像处理（task-152）：任意合法图片 → 居中裁切、白底、512×512 JPEG。
 *
 * <p>只用 JDK 内置 ImageIO；PNG/JPEG 原生支持。WebP 依赖 bond-auth 引入的 TwelveMonkeys
 * {@code imageio-webp} 插件（Java 21 的 ImageIO 默认没有 WebP 解码器）。</p>
 */
public final class AvatarImageProcessor {

    public static final int SIZE = 512;
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/x-png");

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp");

    private AvatarImageProcessor() {
    }

    /** 只校验声明类型与扩展名（真正解码交给 {@link #toSquareJpeg}）。 */
    public static void assertSupported(String contentType, String originalFilename) {
        String type = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        String ext = extensionOf(originalFilename);
        boolean typeOk = !type.isEmpty() && ALLOWED_CONTENT_TYPES.contains(type);
        boolean extOk = ALLOWED_EXTENSIONS.contains(ext);
        // 浏览器对 webp/png 的声明类型偶有 application/octet-stream，扩展名可兜底；两者都不可信时拒绝。
        if (!typeOk && !extOk) {
            throw new BusinessException(400, "头像仅支持 PNG/JPEG/WebP 格式");
        }
    }

    /**
     * 解码并转 512×512 JPEG（白底，等比裁切居中）。解码失败（伪造/损坏/不支持的格式）→ 400。
     */
    public static byte[] toSquareJpeg(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new BusinessException(400, "图片内容为空");
        }
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException e) {
            throw new BusinessException(400, "图片格式不支持");
        }
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            throw new BusinessException(400, "图片格式不支持");
        }

        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;

        // RGB（无 alpha）：JPEG 不支持透明，先铺白底，避免透明 PNG 转出黑块。
        BufferedImage scaled = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, SIZE, SIZE);
            graphics.drawImage(source, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(scaled, "jpg", out)) {
                throw new BusinessException(400, "图片格式不支持");
            }
        } catch (IOException e) {
            throw new BusinessException(400, "图片格式不支持");
        }
        return out.toByteArray();
    }

    static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        int query = ext.indexOf('?');
        if (query >= 0) {
            ext = ext.substring(0, query);
        }
        // 只保留字母数字，防 "png?x" 之类杂串
        StringBuilder cleaned = new StringBuilder(ext.length());
        for (int i = 0; i < ext.length(); i++) {
            char c = ext.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }
}
