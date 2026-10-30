package com.bond.auth.service;

import com.bond.auth.mapper.EmailVerificationCodeMapper;
import com.bond.common.email.BuiltInEmailTemplates;
import com.bond.common.email.EmailAddresses;
import com.bond.common.email.EmailGateway;
import com.bond.common.email.EmailPurpose;
import com.bond.common.entity.EmailVerificationCode;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.RateLimitedException;
import com.bond.common.utils.SnowflakeId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 邮箱验证码（task-57）：生成 6 位数字码，HMAC-SHA256 存库，10 分钟有效、一次性消费。
 *
 * <p>安全口径：不存明文；日志不打码值；同一邮箱 60 秒冷却、每天最多 10 条；错误 5 次作废。
 * 所有阈值都可用 system_configs 覆盖（email.code.ttl_minutes / email.code.resend_seconds /
 * email.code.daily_limit / email.code.max_attempts），未配置时用代码默认值。</p>
 */
@Slf4j
@Service
public class VerificationCodeService {

    public static final String PURPOSE_REGISTER = "register";
    public static final String PURPOSE_RESET_PASSWORD = "reset_password";
    public static final String PURPOSE_CHANGE_EMAIL = "change_email";
    public static final Set<String> PURPOSES = Set.of(
            PURPOSE_REGISTER, PURPOSE_RESET_PASSWORD, PURPOSE_CHANGE_EMAIL);

    private static final Pattern CODE_PATTERN = Pattern.compile("^\\d{6}$");
    private static final int CODE_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** HMAC 密钥：EMAIL_CODE_SECRET 优先，回退 JWT_SECRET；仅用于验证码摘要。 */
    private static final String SECRET = resolveSecret();

    private final EmailVerificationCodeMapper codeMapper;
    private final ConfigService configService;
    private final EmailGateway emailGateway;
    private final EmailSendGuard sendGuard;
    private final Clock clock;

    /**
     * Spring 注入用（另一个构造器是测试注入可控时钟用的，两个构造器必须显式指定一个给 Spring，
     * 否则启动即 "No default constructor found" —— 见 engineering-pitfalls #13 同类事故）。
     */
    @Autowired
    public VerificationCodeService(EmailVerificationCodeMapper codeMapper,
                                   ConfigService configService,
                                   EmailGateway emailGateway,
                                   EmailSendGuard sendGuard) {
        this(codeMapper, configService, emailGateway, sendGuard, Clock.systemUTC());
    }

    /** 测试注入可控时钟（TTL/冷却/尝试次数都依赖「现在」）。 */
    VerificationCodeService(EmailVerificationCodeMapper codeMapper,
                           ConfigService configService,
                           EmailGateway emailGateway,
                           EmailSendGuard sendGuard,
                           Clock clock) {
        this.codeMapper = codeMapper;
        this.configService = configService;
        this.emailGateway = emailGateway;
        this.sendGuard = sendGuard;
        this.clock = clock;
    }

    /**
     * 发送验证码。返回冷却秒数（客户端据此倒计时）。
     *
     * @throws RateLimitedException 60 秒内重复请求
     * @throws BusinessException    今日次数达上限（429）/邮箱格式错误（400）
     */
    public long send(String email, String purpose, Long userId, String ip) {
        String normalized = normalizeEmail(email);
        requirePurpose(purpose);

        long cooldownSeconds = Math.max(1, configService.getInt("email.code.resend_seconds", 60));
        if (codeMapper.countWithinCooldown(normalized, purpose) > 0) {
            throw new RateLimitedException("验证码发送过于频繁，请稍后再试", cooldownSeconds);
        }
        int dailyLimit = Math.max(1, configService.getInt("email.code.daily_limit", 10));
        if (codeMapper.countToday(normalized, purpose) >= dailyLimit) {
            throw new BusinessException(429, "今日验证码发送次数已达上限，请明天再试");
        }
        // 发信防洪（task-63 硬化）：按 IP/用户 + 全局 + 同收件人三层限流，超限 429 且不发信。
        sendGuard.check(normalized, purpose, userId, ip);

        String code = generateCode();
        int ttlMinutes = Math.max(1, configService.getInt("email.code.ttl_minutes", 10));

        EmailVerificationCode row = new EmailVerificationCode();
        row.setId(SnowflakeId.nextId());
        row.setEmail(normalized);
        row.setUserId(userId);
        row.setPurpose(purpose);
        row.setCodeHash(hmacHex(code, normalized, purpose));
        row.setExpiresAt(OffsetDateTime.now(clock).plusMinutes(ttlMinutes));
        row.setAttempts(0);
        row.setIp(ip);
        codeMapper.insertCode(row);

        try {
            // task-76：按「用途」选模板（后台可绑定自定义模板；未绑定/禁用回退内置默认）。
            java.util.Map<String, String> variables = new java.util.LinkedHashMap<>();
            variables.put("code", code);
            variables.put("email", normalized);
            variables.put("expiresMinutes", String.valueOf(ttlMinutes));
            // username（如有）：注册时用户还不存在，用邮箱前缀兜底；重置/改邮箱时调用方没传用户，也走兜底。
            variables.put("username", localPart(normalized));
            emailGateway.sendByPurpose(normalized, emailPurposeOf(purpose), variables);
        } catch (RuntimeException e) {
            // 发送失败时删掉刚插入的行，避免「没收到信却要等 60 秒冷却」
            try {
                codeMapper.deleteById(row.getId());
            } catch (RuntimeException cleanupError) {
                log.warn("failed to clean up unsent verification code: {}", cleanupError.getClass().getSimpleName());
            }
            throw e;
        }
        log.info("verification code sent: purpose={} email={}", purpose, maskEmail(normalized));
        return cooldownSeconds;
    }

    /** 默认冷却秒数（未发送时用于前端倒计时/防枚举响应）。 */
    public long cooldownSeconds() {
        return Math.max(1, configService.getInt("email.code.resend_seconds", 60));
    }

    /**
     * 校验并消费验证码；成功返回该验证码对应的邮箱（已规范化）。
     *
     * @throws BusinessException 400：无效/过期/已使用/尝试次数过多/输入格式错误
     */
    public String verifyAndConsume(String email, String purpose, String code) {
        String normalized = normalizeEmail(email);
        requirePurpose(purpose);
        if (code == null || !CODE_PATTERN.matcher(code.trim()).matches()) {
            throw new BusinessException(400, "验证码格式不正确");
        }
        String submitted = code.trim();

        EmailVerificationCode row = codeMapper.selectActive(normalized, purpose);
        if (row == null) {
            throw new BusinessException(400, "验证码无效或已过期");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (row.getConsumedAt() != null) {
            throw new BusinessException(400, "验证码已使用，请重新获取");
        }
        if (row.getExpiresAt() == null || now.isAfter(row.getExpiresAt())) {
            throw new BusinessException(400, "验证码已过期，请重新获取");
        }
        int maxAttempts = Math.max(1, configService.getInt("email.code.max_attempts", 5));
        int attempts = row.getAttempts() == null ? 0 : row.getAttempts();
        if (attempts >= maxAttempts) {
            codeMapper.invalidate(row.getId());
            throw new BusinessException(400, "验证码尝试次数过多，请重新获取");
        }

        String expected = row.getCodeHash() == null ? "" : row.getCodeHash();
        String actual = hmacHex(submitted, normalized, purpose);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            codeMapper.incrementAttempts(row.getId());
            if (attempts + 1 >= maxAttempts) {
                codeMapper.invalidate(row.getId());
                throw new BusinessException(400, "验证码尝试次数过多，请重新获取");
            }
            throw new BusinessException(400, "验证码错误");
        }

        int consumed = codeMapper.consume(row.getId());
        if (consumed == 0) {
            // 并发下另一个请求已消费（一次性语义）
            throw new BusinessException(400, "验证码已使用，请重新获取");
        }
        return row.getEmail();
    }

    /** 改邮箱确认：从该用户最近一条 change_email 验证码中取目标邮箱后校验消费。 */
    public String verifyChangeEmail(Long userId, String code) {
        EmailVerificationCode row = codeMapper.selectActiveByUser(userId, PURPOSE_CHANGE_EMAIL);
        if (row == null) {
            throw new BusinessException(400, "验证码无效或已过期");
        }
        verifyAndConsume(row.getEmail(), PURPOSE_CHANGE_EMAIL, code);
        return row.getEmail();
    }

    // ── 对内工具 ──

    public static String normalizeEmail(String email) {
        return EmailAddresses.normalize(email);
    }

    private static void requirePurpose(String purpose) {
        if (purpose == null || !PURPOSES.contains(purpose)) {
            throw new BusinessException(400, "不支持的验证码用途");
        }
    }

    static String generateCode() {
        int value = RANDOM.nextInt(1_000_000);
        return String.format(Locale.ROOT, "%0" + CODE_LENGTH + "d", value);
    }

    /** HMAC-SHA256(secret, code + ":" + purpose + ":" + email)，小写 hex。 */
    static String hmacHex(String code, String email, String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((code + ":" + purpose + ":" + email).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC init failed", e);
        }
    }

    private static String resolveSecret() {
        String secret = System.getenv("EMAIL_CODE_SECRET");
        if (secret == null || secret.isBlank()) {
            secret = System.getenv().getOrDefault("JWT_SECRET", "dev-email-code-secret");
        }
        return secret;
    }

    /**
     * 用途 → 模板 code（task-63）。模板可在后台改写；未知用途回退 verification_code 兜底，
     * 保证新增用途时也不会因模板缺失而发不出信。
     */
    static String templateCodeOf(String purpose) {
        return switch (purpose) {
            case PURPOSE_RESET_PASSWORD -> BuiltInEmailTemplates.PASSWORD_RESET;
            case PURPOSE_CHANGE_EMAIL -> BuiltInEmailTemplates.CHANGE_EMAIL;
            default -> BuiltInEmailTemplates.VERIFICATION_CODE;
        };
    }

    /** 用途 → 邮件目的（task-76 方案 B：后台按「目的」绑定自定义模板）。 */
    static String emailPurposeOf(String purpose) {
        return switch (purpose) {
            case PURPOSE_RESET_PASSWORD -> EmailPurpose.PASSWORD_RESET;
            case PURPOSE_CHANGE_EMAIL -> EmailPurpose.CHANGE_EMAIL;
            default -> EmailPurpose.REGISTER_CODE;
        };
    }

    /** 邮箱前缀（注册时用户尚不存在，用作模板里的 username 兜底）。 */
    private static String localPart(String email) {
        int at = email.indexOf('@');
        return at <= 0 ? "用户" : email.substring(0, at);
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
