package com.bond.auth.service;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@EnableScheduling
public class CaptchaService {

    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final int CODE_LENGTH = 4;
    private static final long CAPTCHA_EXPIRE_MS = 5 * 60 * 1000;
    private static final int MAX_ATTEMPTS = 2;
    private static final long FAIL_COUNT_EXPIRE_MS = 30 * 60 * 1000;

    private final ConcurrentHashMap<String, CaptchaEntry> captchas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FailEntry> failCounts = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 60000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        captchas.entrySet().removeIf(e -> now - e.getValue().timestamp > CAPTCHA_EXPIRE_MS);
        failCounts.entrySet().removeIf(e -> now - e.getValue().timestamp > FAIL_COUNT_EXPIRE_MS);
    }

    /**
     * 是否需要验证码（task-68）。
     *
     * <p>计数键分两层：{@code ip}（同一出口 IP 的聚合，防撞库）与 {@code ip|username}
     * （精确到账号，避免同一出口/办公网里别人的失败把当前用户拖下水）。
     * 登录接口总是带 username，因此普通用户只看自己的复合键；{@code /captcha/check}
     * 未带 username 时退回 IP 聚合键。</p>
     */
    public boolean needsCaptcha(String ip, String username) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String userKey = userKey(ip, username);
        if (userKey != null) {
            return exceeds(userKey);
        }
        return exceeds(ip);
    }

    public void recordFail(String ip, String username) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        bump(ip);
        String userKey = userKey(ip, username);
        if (userKey != null) {
            bump(userKey);
        }
    }

    /** 登录成功后只清当前账号的复合键；IP 聚合键保留到过期，避免攻击者用一次成功重置同 IP 的防护。 */
    public void clearFails(String ip, String username) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        String userKey = userKey(ip, username);
        if (userKey != null) {
            failCounts.remove(userKey);
        }
    }

    /** 登录成功但未带用户名时的兜底清理（仅供兼容调用，正常路径应传用户名）。 */
    public void clearFails(String ip) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        failCounts.remove(ip);
    }

    private boolean exceeds(String key) {
        FailEntry entry = failCounts.get(key);
        return entry != null && entry.count.get() >= MAX_ATTEMPTS;
    }

    private void bump(String key) {
        failCounts.compute(key, (k, v) -> {
            if (v == null) return new FailEntry();
            v.count.incrementAndGet();
            return v;
        });
    }

    private static String userKey(String ip, String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return ip + "|" + username.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public CaptchaResult generate() {
        String code = randomCode();
        String id = java.util.UUID.randomUUID().toString().replace("-", "");
        captchas.put(id, new CaptchaEntry(code, System.currentTimeMillis()));

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        drawBackground(g);
        drawCode(g, code);
        drawNoise(g);
        g.dispose();

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            return new CaptchaResult(id, "data:image/png;base64," + base64);
        } catch (IOException e) {
            throw new RuntimeException("生成验证码失败", e);
        }
    }

    public boolean verify(String id, String code) {
        if (id == null || code == null) return false;
        CaptchaEntry entry = captchas.remove(id);
        if (entry == null) return false;
        if (System.currentTimeMillis() - entry.timestamp > CAPTCHA_EXPIRE_MS) return false;
        return entry.code.equalsIgnoreCase(code.trim());
    }

    private String randomCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private void drawBackground(Graphics2D g) {
        g.setColor(new Color(245, 247, 250));
        g.fillRect(0, 0, WIDTH, HEIGHT);
    }

    private void drawCode(Graphics2D g, String code) {
        g.setFont(new Font("SansSerif", Font.BOLD, 28));
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color(50 + r.nextInt(100), 50 + r.nextInt(100), 50 + r.nextInt(100)));
            int x = 10 + i * 26;
            int y = 28 + r.nextInt(8) - 4;
            double angle = Math.toRadians(r.nextInt(30) - 15);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.rotate(angle, x + 10, y - 5);
            g2.drawString(String.valueOf(code.charAt(i)), x, y);
            g2.dispose();
        }
    }

    private void drawNoise(Graphics2D g) {
        java.util.Random r = new java.util.Random();
        g.setColor(new Color(200, 200, 200));
        for (int i = 0; i < 30; i++) {
            int x1 = r.nextInt(WIDTH), y1 = r.nextInt(HEIGHT);
            g.fillOval(x1, y1, 3, 3);
        }
        for (int i = 0; i < 4; i++) {
            g.setColor(new Color(180 + r.nextInt(60), 180 + r.nextInt(60), 180 + r.nextInt(60)));
            int x1 = r.nextInt(WIDTH), y1 = r.nextInt(HEIGHT);
            int x2 = r.nextInt(WIDTH), y2 = r.nextInt(HEIGHT);
            g.drawLine(x1, y1, x2, y2);
        }
    }

    private static class CaptchaEntry {
        final String code;
        final long timestamp;
        CaptchaEntry(String code, long timestamp) {
            this.code = code;
            this.timestamp = timestamp;
        }
    }

    private static class FailEntry {
        final AtomicInteger count = new AtomicInteger(1);
        final long timestamp = System.currentTimeMillis();
    }

    public static class CaptchaResult {
        public final String id;
        public final String image;
        public CaptchaResult(String id, String image) {
            this.id = id;
            this.image = image;
        }
    }
}
