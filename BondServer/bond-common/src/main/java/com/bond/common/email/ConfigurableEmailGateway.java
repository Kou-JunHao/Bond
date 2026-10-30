package com.bond.common.email;

import com.bond.common.entity.EmailTemplate;
import com.bond.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * 运行时发信网关（task-63 v3）：唯一 {@code EmailGateway} bean，按 provider 分派到
 * SMTP / Resend / BondMail / off。
 *
 * <ul>
 *   <li>配置每次按 DB→env→默认(off) 解析，结果缓存 60 秒；后台保存设置后调用 {@link #invalidate()} 立即失效。</li>
 *   <li>provider=off（默认）一律 503「发信服务未配置」。</li>
 *   <li>模板：先查 DB（{@link EmailTemplateStore}），缺省回退内置默认（{@link BuiltInEmailTemplates}）。</li>
 *   <li>日志只记 provider + 收件人掩码 + 错误类别；绝不打印验证码/正文/密码。</li>
 *   <li>task-129：瞬时错误（429/5xx/连接超时/IO）指数退避重试 3 次；永久 4xx 不重试；
 *       失败日志带 provider/httpStatus/attempts/耗时。</li>
 * </ul>
 */
@Slf4j
@Service
public class ConfigurableEmailGateway implements EmailGateway {

    public static final long CACHE_MILLIS = 60_000L;

    /** 最多重试次数（首次尝试之外）。 */
    static final int MAX_RETRIES = 3;
    /** 指数退避间隔：200ms / 600ms / 1800ms。 */
    static final long[] RETRY_DELAYS_MILLIS = {200L, 600L, 1_800L};
    /** 整体重试预算：超过则不再追加等待，避免把请求线程拖死（同步接口 ~5s 内）。 */
    static final long RETRY_TOTAL_BUDGET_MILLIS = 5_000L;

    private final EmailSettingsProvider settingsProvider;
    private final EmailTemplateStore templateStore;
    /** 用途绑定（task-76）；测试/未装库场景为 null，退化为内置默认。 */
    private final EmailPurposeStore purposeStore;
    private final SmtpEmailGateway smtpEmailGateway = new SmtpEmailGateway();
    private final AtomicLong cacheLoadedAt = new AtomicLong(0);
    /** 重发载荷加密器（task-134）：懒加载；缺密钥时按 ConfigCipher 的既有回退策略处理。 */
    private volatile ConfigCipher resendCipher;
    /** 重发载荷序列化（仅内部使用，不落明文）。 */
    private static final ObjectMapper RESEND_MAPPER = new ObjectMapper();
    private volatile EmailSettings cached;
    /** 投递日志记录器（task-80）；null 表示未装配（单测/无 DB 场景），发信照常。 */
    private volatile EmailDeliveryRecorder deliveryRecorder;
    /** 退避 sleeper；单测替换为「记录不睡眠」，生产用 Thread.sleep。 */
    private volatile LongConsumer backoffSleeper = ConfigurableEmailGateway::sleepQuietly;

    /** 测试/无绑定场景的兼容构造器。 */
    public ConfigurableEmailGateway(EmailSettingsProvider settingsProvider,
                                    EmailTemplateStore templateStore) {
        this(settingsProvider, templateStore, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ConfigurableEmailGateway(EmailSettingsProvider settingsProvider,
                                    EmailTemplateStore templateStore,
                                    EmailPurposeStore purposeStore) {
        this.settingsProvider = settingsProvider;
        this.templateStore = templateStore;
        this.purposeStore = purposeStore;
    }

    @Override
    public boolean isEnabled() {
        return settings().enabled();
    }

    /** 后台保存设置后调用，令下一次发信重新读库。 */
    public void invalidate() {
        cached = null;
        cacheLoadedAt.set(0);
    }

    /**
     * task-80：注入投递日志记录器（auth / transfer 各自的落库实现）。
     *
     * <p>可选依赖（{@code required = false}）：测试或无 DB 场景不装配，
     * 发信路径完全不依赖它；写库异常由 {@link #recordDelivery} 兜底。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDeliveryRecorder(EmailDeliveryRecorder deliveryRecorder) {
        this.deliveryRecorder = deliveryRecorder;
    }

    /** 测试用：替换退避 sleeper（避免单测真的 sleep 200/600/1800ms）。 */
    void setBackoffSleeper(LongConsumer sleeper) {
        this.backoffSleeper = sleeper == null ? ConfigurableEmailGateway::sleepQuietly : sleeper;
    }

    public EmailSettings settings() {
        long now = System.currentTimeMillis();
        EmailSettings snapshot = cached;
        if (snapshot != null && now - cacheLoadedAt.get() < CACHE_MILLIS) {
            return snapshot;
        }
        synchronized (this) {
            if (cached != null && now - cacheLoadedAt.get() < CACHE_MILLIS) {
                return cached;
            }
            EmailSettings loaded;
            try {
                loaded = settingsProvider.load();
            } catch (RuntimeException e) {
                log.warn("load email settings failed, fallback to off: {}", e.getClass().getSimpleName());
                loaded = EmailSettings.defaults();
            }
            cached = loaded;
            cacheLoadedAt.set(System.currentTimeMillis());
            return loaded;
        }
    }

    @Override
    public void send(String to, String subject, String text) {
        EmailSettings settings = requireEnabled();
        deliver(settings, to, new EmailTemplateRenderer.RenderedEmail(
                subject == null ? "" : subject, null, text == null ? "" : text), null, null, null, List.of());
    }

    /** HTML + 纯文本发送（task-69）：后台自检邮件等需要品牌化 HTML 的场景使用。 */
    @Override
    public void send(String to, String subject, String html, String text) {
        EmailSettings settings = requireEnabled();
        deliver(settings, to, new EmailTemplateRenderer.RenderedEmail(
                subject == null ? "" : subject,
                html == null ? "" : html,
                text == null ? "" : text), null, null, null, List.of());
    }

    @Override
    public void sendTemplate(String to, String templateCode, Map<String, String> variables) {
        EmailSettings settings = requireEnabled();
        EmailTemplate template = lookupTemplate(templateCode);
        Map<String, String> vars = enrichVariables(settings, variables);
        EmailTemplateRenderer.RenderedEmail rendered = EmailTemplateRenderer.render(template, vars);
        List<String> missing = EmailTemplateRenderer.missingVariables(template, vars);
        warnMissingVariables(template, vars);
        deliver(settings, to, rendered, templateCode, null, vars, missing);
    }

    /**
     * 按用途发送（task-76 方案 B）：绑定模板 → 该用途内置默认 → 仍无则 500。
     *
     * <p>绑定可能指向「已删除/被禁用」的模板：此时 warn 并回退内置默认，保证信一定发得出去。</p>
     */
    @Override
    public void sendByPurpose(String to, String purpose, Map<String, String> variables) {
        EmailSettings settings = requireEnabled();
        EmailTemplate template = resolveTemplateForPurpose(purpose);
        Map<String, String> vars = enrichVariables(settings, variables);
        EmailTemplateRenderer.RenderedEmail rendered = EmailTemplateRenderer.render(template, vars);
        List<String> missing = EmailTemplateRenderer.missingVariables(template, vars);
        warnMissingVariables(template, vars);
        deliver(settings, to, rendered, template.getCode(), purpose, vars, missing);
    }

    /** 注入默认变量（appName/year/supportEmail）；显式传入的值优先。 */
    private Map<String, String> enrichVariables(EmailSettings settings, Map<String, String> variables) {
        Map<String, String> vars = variables == null ? new LinkedHashMap<>() : new LinkedHashMap<>(variables);
        vars.putIfAbsent("appName", "Bond");
        vars.putIfAbsent("year", String.valueOf(java.time.Year.now().getValue()));
        String supportEmail = settings.effectiveFromAddress();
        vars.putIfAbsent("supportEmail", supportEmail == null ? "" : supportEmail);
        return vars;
    }

    /**
     * task-79 防回归护栏：比较「模板声明变量」与「实际提供 + 网关默认注入」，差集非空只 WARN。
     * task-129：缺失变量会渲染成 “—”，但仍应 WARN 提示调用方补变量。
     */
    void warnMissingVariables(EmailTemplate template, Map<String, String> vars) {
        List<String> missing = EmailTemplateValidator.missingVariables(template, vars);
        if (!missing.isEmpty()) {
            log.warn("email template {} references undeclared variables: {}",
                    template.getCode(), missing);
        }
    }

    /** 用途 → 模板解析（绑定优先；绑定失效回退内置），包级可见便于单测。 */
    EmailTemplate resolveTemplateForPurpose(String purpose) {
        EmailPurpose.Purpose defined = EmailPurpose.require(purpose);
        String boundCode = null;
        if (purposeStore != null) {
            try {
                boundCode = purposeStore.boundTemplateCode(purpose);
            } catch (RuntimeException e) {
                log.warn("load email purpose binding failed: purpose={} err={}",
                        purpose, e.getClass().getSimpleName());
            }
        }
        if (boundCode != null && !boundCode.isBlank()) {
            EmailTemplate bound = lookupTemplateOrNull(boundCode.trim());
            if (bound != null) {
                return bound;
            }
            log.warn("email purpose binding points to missing/disabled template, fallback builtin: "
                    + "purpose={} code={}", purpose, boundCode);
        }
        EmailTemplate fallback = lookupTemplateOrNull(defined.builtinTemplateCode());
        if (fallback == null) {
            throw new BusinessException(500, "邮件模板不存在: " + defined.builtinTemplateCode());
        }
        return fallback;
    }

    /** 模板查找：DB（含 disabled 则视为缺省）→ 内置默认；仍无则 500（说明是部署/配置缺陷）。 */
    EmailTemplate lookupTemplate(String templateCode) {
        if (templateCode == null || templateCode.isBlank()) {
            throw new BusinessException(500, "邮件模板 code 不能为空");
        }
        EmailTemplate template = lookupTemplateOrNull(templateCode.trim());
        if (template == null) {
            throw new BusinessException(500, "邮件模板不存在: " + templateCode);
        }
        return template;
    }

    /** 同上，但找不到时返回 null（供「按用途回退」判断，不抛错）。 */
    EmailTemplate lookupTemplateOrNull(String templateCode) {
        if (templateCode == null || templateCode.isBlank()) {
            return null;
        }
        EmailTemplate template;
        try {
            template = templateStore.find(templateCode.trim());
        } catch (RuntimeException e) {
            log.warn("load email template failed: code={} err={}", templateCode, e.getClass().getSimpleName());
            template = null;
        }
        if (template == null || Boolean.FALSE.equals(template.getEnabled())) {
            template = BuiltInEmailTemplates.get(templateCode.trim());
        }
        return template;
    }

    private EmailSettings requireEnabled() {
        EmailSettings settings = settings();
        if (!settings.enabled()) {
            throw new BusinessException(503, "发信服务未配置");
        }
        return settings;
    }

    /**
     * 发信主流程（task-129）：瞬时错误指数退避重试；永久失败不重试。
     */
    private void deliver(EmailSettings settings, String to, EmailTemplateRenderer.RenderedEmail email,
                         String templateCode, String purpose, Map<String, String> vars,
                         List<String> missingVariables) {
        String provider = settings.provider();
        long startNanos = System.nanoTime();
        Integer finalHttpStatus = null;
        String finalError = null;
        int attempts = 0;

        while (attempts < MAX_RETRIES + 1) {
            attempts++;
            try {
                sendViaProvider(settings, to, email, provider);
                if (attempts > 1) {
                    log.info("email sent after retry: provider={} template={} to={} attempts={} durationMs={}",
                            provider, templateCode == null ? "-" : templateCode, mask(to), attempts,
                            elapsedMs(startNanos));
                } else {
                    log.info("email sent: provider={} template={} to={}", provider,
                            templateCode == null ? "-" : templateCode, mask(to));
                }
                recordDelivery(to, purpose, templateCode, email, vars, missingVariables, provider,
                        startNanos, null, attempts);
                return;
            } catch (BusinessException e) {
                // 配置/参数类错误：永久失败，不重试
                finalError = e.getClass().getSimpleName() + ": " + e.getMessage();
                recordDelivery(to, purpose, templateCode, email, vars, missingVariables, provider,
                        startNanos, failureReason(provider, attempts, null, finalError), attempts);
                throw e;
            } catch (EmailSendException e) {
                finalHttpStatus = e.httpStatus();
                finalError = e.getClass().getSimpleName() + ": " + e.detail();
                if (!e.retryable() || attempts > MAX_RETRIES) {
                    break;
                }
                long delay = RETRY_DELAYS_MILLIS[attempts - 1];
                if (elapsedMs(startNanos) + delay > RETRY_TOTAL_BUDGET_MILLIS) {
                    finalError = finalError + " (retry budget exhausted)";
                    break;
                }
                log.warn("email send transient failure, retrying: provider={} attempt={} delayMs={} "
                                + "httpStatus={} reason={}", provider, attempts, delay, e.httpStatus(), e.detail());
                backoffSleeper.accept(delay);
            } catch (RuntimeException e) {
                // 未知运行时异常：保守视为永久失败（避免对代码缺陷无限重试）
                finalError = e.getClass().getSimpleName() + ": " + e.getMessage();
                break;
            }
        }

        String reason = failureReason(provider, attempts, finalHttpStatus, finalError);
        log.warn("email send failed: provider={} to={} httpStatus={} attempts={} durationMs={} error={}",
                provider, mask(to), finalHttpStatus, attempts, elapsedMs(startNanos), finalError);
        recordDelivery(to, purpose, templateCode, email, vars, missingVariables, provider,
                startNanos, reason, attempts);
        throw new BusinessException(503, "邮件服务暂时不可用，请稍后再试");
    }

    /** task-129：失败摘要 = provider + httpStatus + 错误 + 尝试次数；供投递日志展示。 */
    static String failureReason(String provider, int attempts, Integer httpStatus, String error) {
        return "provider=" + provider
                + ", httpStatus=" + (httpStatus == null ? "-" : httpStatus)
                + ", attempts=" + attempts
                + ", error=" + (error == null ? "-" : error);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 真正的 provider 分派。包级可见且可覆盖，便于单测用假的发送实现验证
     * 「成功路径也有投递日志」「瞬时错误重试」。
     */
    void sendViaProvider(EmailSettings settings, String to, EmailTemplateRenderer.RenderedEmail email,
                         String provider) {
        switch (provider) {
            case EmailSettings.PROVIDER_SMTP -> smtpEmailGateway.send(settings, to, email);
            case EmailSettings.PROVIDER_RESEND -> new ResendEmailGateway(settings)
                    .send(to, email.subject(), email.html(), email.text());
            case EmailSettings.PROVIDER_BONDMAIL -> new BondMailEmailGateway(settings)
                    .send(to, email.subject(), email.text());
            default -> throw new BusinessException(503, "发信服务未配置");
        }
    }

    /**
     * 写投递日志（task-80 / task-129）。
     *
     * <p>安全口径：只记掩码收件人、模板、主题、状态、耗时、变量<b>名</b>与残留占位符数；
     * 绝不记录变量值（验证码/临时密码）或正文。任何异常只 WARN —— 绝不影响发信。</p>
     */
    private void recordDelivery(String to, String purpose, String templateCode,
                                EmailTemplateRenderer.RenderedEmail email,
                                Map<String, String> vars, List<String> missingVariables,
                                String provider, long startNanos, String failureReason, int attempts) {
        EmailDeliveryRecorder recorder = this.deliveryRecorder;
        if (recorder == null) {
            return;
        }
        try {
            int durationMs = (int) Math.min(Integer.MAX_VALUE, elapsedMs(startNanos));
            String status = failureReason == null
                    ? EmailDeliveryRecorder.EmailDeliveryRecord.STATUS_SENT
                    : EmailDeliveryRecorder.EmailDeliveryRecord.STATUS_FAILED;
            List<String> keys = vars == null
                    ? List.of()
                    : List.copyOf(new java.util.TreeSet<>(vars.keySet()));
            recorder.record(new EmailDeliveryRecorder.EmailDeliveryRecord(
                    null,
                    mask(to),
                    blankToNull(purpose),
                    blankToNull(templateCode),
                    truncate(email == null ? "" : email.subject(), 300),
                    status,
                    failureReason == null ? null : truncate(sanitize(failureReason), 500),
                    keys,
                    missingVariables == null ? List.of() : List.copyOf(missingVariables),
                    EmailTemplateRenderer.countPlaceholders(email),
                    provider,
                    durationMs,
                    attempts,
                    encryptResendPayload(to, vars)));
        } catch (RuntimeException e) {
            log.warn("record email delivery log failed (send not affected): provider={} err={}",
                    provider, e.getClass().getSimpleName());
        }
    }

    /**
     * 构造「管理端手动重发」载荷（task-134）：AES-GCM 加密的 {@code {to, variables}} JSON。
     *
     * <p>安全口径：绝不落明文收件人/变量值；返回 {@code null} 表示该条不可重发
     * （无收件人或加密失败）。任何异常只 WARN，不影响本次发信。</p>
     */
    private String encryptResendPayload(String to, Map<String, String> vars) {
        if (to == null || to.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("to", to);
            payload.put("variables", vars == null ? Map.of() : new LinkedHashMap<>(vars));
            ConfigCipher cipher = resendCipher;
            if (cipher == null) {
                cipher = ConfigCipher.fromEnvironment();
                resendCipher = cipher;
            }
            return cipher.encrypt(RESEND_MAPPER.writeValueAsString(payload));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("build resend payload failed (manual resend unavailable for this row): {}",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String sanitize(String value) {
        return value.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
