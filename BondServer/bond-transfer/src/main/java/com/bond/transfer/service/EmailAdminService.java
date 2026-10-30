package com.bond.transfer.service;

import com.bond.common.email.BuiltInEmailTemplates;
import com.bond.common.email.ConfigCipher;
import com.bond.common.email.EmailAddresses;
import com.bond.common.email.EmailPurpose;
import com.bond.common.email.EmailSettings;
import com.bond.common.email.EmailTemplateRenderer;
import com.bond.common.email.SmtpEmailGateway;
import com.bond.common.entity.EmailTemplate;
import com.bond.common.entity.EmailTemplateBinding;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.ConflictException;
import com.bond.transfer.mapper.EmailTemplateBindingMapper;
import com.bond.transfer.mapper.EmailTemplateMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台「邮件」设置与模板管理（task-63）。
 *
 * <p>设置统一存 {@code system_config} 的 {@code email.*} 键；SMTP 口令 / Resend Key 以
 * AES-GCM（{@link ConfigCipher}，{@code enc:v1:} 前缀）落库，GET 只返回掩码，
 * PUT 传空字符串或掩码值表示「不修改」。</p>
 *
 * <p>发送侧调用 {@code ConfigurableEmailGateway}（DB 设置优先，60s 缓存；
 * 保存后立即 invalidate 令下一次发信重新读库）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailAdminService {

    private static final ConfigCipher CIPHER = ConfigCipher.fromEnvironment();
    private static final Set<String> PROVIDERS = Set.of("smtp", "resend", "bondmail", "off");
    private static final int MAX_TEMPLATE_NAME = 100;
    private static final int MAX_TEMPLATE_SUBJECT = 300;
    private static final int MAX_TEST_ERROR = 300;
    /** 自定义模板 code 仍保持「小写字母开头、[a-z0-9_]、2-63」格式，但由后端生成（task-76 方案 B：用「用途」而非手填 code）。 */
    private static final String CUSTOM_CODE_PREFIX = "custom_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConfigService configService;
    private final EmailTemplateMapper emailTemplateMapper;
    private final EmailTemplateBindingMapper bindingMapper;
    private final TransferEmailSettingsProvider settingsProvider;
    private final com.bond.common.email.ConfigurableEmailGateway configurableEmailGateway;
    private final ObjectMapper objectMapper;

    // ── 发信设置 ──

    /** 管理台读取设置（敏感值只回掩码）。 */
    public Map<String, Object> settings() {
        EmailSettings resolved = settingsProvider.load();

        Map<String, Object> from = new LinkedHashMap<>();
        from.put("address", resolved.fromAddress());
        from.put("name", resolved.fromName());

        Map<String, Object> smtp = new LinkedHashMap<>();
        smtp.put("host", resolved.smtp().host());
        smtp.put("port", resolved.smtp().port());
        smtp.put("username", resolved.smtp().username());
        // 口令只回显「是否已设置」+ 尾 4 位掩码（任务书 v3 契约 passwordMasked；绝不返回明文）。
        String storedPassword = configService.get("email.smtp.password");
        smtp.put("passwordSet", !storedPassword.isBlank());
        smtp.put("passwordMasked", maskSecret(storedPassword));
        smtp.put("auth", resolved.smtp().auth());
        smtp.put("starttls", resolved.smtp().starttls());
        smtp.put("ssl", resolved.smtp().ssl());
        smtp.put("timeoutSeconds", resolved.smtp().timeoutSeconds());

        Map<String, Object> resend = new LinkedHashMap<>();
        String storedApiKey = configService.get("email.resend.api_key");
        resend.put("apiKeySet", !storedApiKey.isBlank());
        resend.put("apiKeyMasked", maskSecret(storedApiKey));
        resend.put("endpoint", resolved.resendEndpoint());

        Map<String, Object> bondmail = new LinkedHashMap<>();
        bondmail.put("endpoint", resolved.bondmailEndpoint());

        Map<String, Object> body = new LinkedHashMap<>();
        // provider=管理员保存的配置值；effectiveProvider=服务端实际生效通道
        // （provider=smtp 但 SMTP 未配全且 Resend Key 可用时会回退 resend，便于设置页如实提示）。
        body.put("provider", configService.get("email.provider", "smtp"));
        body.put("effectiveProvider", resolved.provider());
        body.put("from", from);
        body.put("smtp", smtp);
        body.put("resend", resend);
        body.put("bondmail", bondmail);
        body.put("configured", resolved.configured());
        // 本期不自检结果持久化：字段保留给前端契约，避免以后加字段破坏解析。
        body.put("lastTest", null);
        return body;
    }

    /** 保存设置；返回与 GET 同结构（敏感值仍为掩码）。 */
    public Map<String, Object> updateSettings(Map<String, Object> body, Long adminUserId) {
        if (body == null || body.isEmpty()) {
            throw new BusinessException(400, "请求体不能为空");
        }
        Map<String, Object> updates = new LinkedHashMap<>();

        String provider = text(body.get("provider"));
        if (provider == null) {
            provider = configService.get("email.provider", "smtp");
        }
        provider = provider.trim().toLowerCase();
        if (!PROVIDERS.contains(provider)) {
            throw new BusinessException(400, "provider 只能是 smtp / resend / bondmail / off");
        }
        updates.put("email.provider", provider);

        Map<String, Object> from = asMap(body.get("from"));
        if (from != null) {
            if (from.containsKey("address")) {
                String address = text(from.get("address"));
                if (address != null && !address.isBlank()) {
                    EmailAddresses.normalize(address);
                    updates.put("email.from.address", address.trim());
                } else {
                    updates.put("email.from.address", "");
                }
            }
            if (from.containsKey("name")) {
                String name = text(from.get("name"));
                updates.put("email.from.name", name == null || name.isBlank() ? "Bond" : name.trim());
            }
        }

        Map<String, Object> smtp = asMap(body.get("smtp"));
        if (smtp != null) {
            if (smtp.containsKey("host")) {
                updates.put("email.smtp.host", text(smtp.get("host")) == null ? "" : text(smtp.get("host")).trim());
            }
            if (smtp.containsKey("port")) {
                int port = intValue(smtp.get("port"), -1);
                if (port < 1 || port > 65535) {
                    throw new BusinessException(400, "SMTP 端口必须在 1-65535 之间");
                }
                updates.put("email.smtp.port", String.valueOf(port));
            }
            if (smtp.containsKey("username")) {
                updates.put("email.smtp.username", text(smtp.get("username")) == null ? "" : text(smtp.get("username")).trim());
            }
            if (smtp.containsKey("password")) {
                String password = text(smtp.get("password"));
                // 空 / 掩码值（****xxxx）=「保持原值」
                if (password != null && !password.isBlank() && !password.contains("****")) {
                    updates.put("email.smtp.password", CIPHER.encrypt(password.trim()));
                }
            }
            if (smtp.containsKey("auth")) {
                updates.put("email.smtp.auth", String.valueOf(boolValue(smtp.get("auth"))));
            }
            if (smtp.containsKey("starttls")) {
                updates.put("email.smtp.starttls", String.valueOf(boolValue(smtp.get("starttls"))));
            }
            if (smtp.containsKey("ssl")) {
                updates.put("email.smtp.ssl", String.valueOf(boolValue(smtp.get("ssl"))));
            }
            if (smtp.containsKey("timeoutSeconds")) {
                int timeout = intValue(smtp.get("timeoutSeconds"), -1);
                if (timeout < 1 || timeout > 60) {
                    throw new BusinessException(400, "SMTP 超时秒数必须在 1-60 之间");
                }
                updates.put("email.smtp.timeout_seconds", String.valueOf(timeout));
            }
        }

        Map<String, Object> resend = asMap(body.get("resend"));
        if (resend != null) {
            if (resend.containsKey("apiKey")) {
                String apiKey = text(resend.get("apiKey"));
                if (apiKey != null && !apiKey.isBlank() && !apiKey.contains("****")) {
                    updates.put("email.resend.api_key", CIPHER.encrypt(apiKey.trim()));
                }
            }
            if (resend.containsKey("endpoint")) {
                String endpoint = text(resend.get("endpoint"));
                updates.put("email.resend.endpoint",
                        endpoint == null || endpoint.isBlank() ? "https://api.resend.com" : endpoint.trim());
            }
        }

        Map<String, Object> bondmail = asMap(body.get("bondmail"));
        if (bondmail != null && bondmail.containsKey("endpoint")) {
            String endpoint = text(bondmail.get("endpoint"));
            updates.put("email.bondmail.endpoint", endpoint == null ? "" : endpoint.trim());
        }

        // 合并后的视图做交叉校验，避免「只改一项」时绕过约束
        String mergedProvider = text(updates.getOrDefault("email.provider", configService.get("email.provider", "smtp")));
        String mergedHost = text(updates.getOrDefault("email.smtp.host", configService.get("email.smtp.host", "")));
        boolean mergedSsl = boolValue(updates.getOrDefault("email.smtp.ssl",
                configService.get("email.smtp.ssl", "false")));
        boolean mergedStarttls = boolValue(updates.getOrDefault("email.smtp.starttls",
                configService.get("email.smtp.starttls", "false")));
        if (mergedSsl && mergedStarttls) {
            throw new BusinessException(400, "ssl 与 starttls 互斥：465 用 ssl，587 用 starttls");
        }
        // 允许「先保存 provider=smtp、SMTP 参数稍后补」：解析层会在 SMTP 未配全且 Resend Key 可用时回退 resend。
        // 仅当已经填了 host 且开启认证时，才要求用户名非空（避免 obvious 配置错误）。
        if ("smtp".equals(mergedProvider) && mergedHost != null && !mergedHost.isBlank()) {
            String mergedUsername = text(updates.getOrDefault("email.smtp.username",
                    configService.get("email.smtp.username", "")));
            boolean mergedAuth = boolValue(updates.getOrDefault("email.smtp.auth",
                    configService.get("email.smtp.auth", "true")));
            if (mergedAuth && (mergedUsername == null || mergedUsername.isBlank())) {
                throw new BusinessException(400, "开启认证时 SMTP 用户名不能为空");
            }
        }

        updates.forEach((key, value) -> configService.set(key, String.valueOf(value), adminUserId));
        configurableEmailGateway.invalidate();
        log.info("admin updated email settings: provider={} by={}", provider, adminUserId);
        return settings();
    }

    /**
     * 发送自检邮件：成功 200；失败抛 400 并把下游真实错误摘要（已脱敏、截断）带给前端排障。
     * 注意：错误信息不含口令——脱敏由 {@link SmtpEmailGateway} 完成。
     */
    public Map<String, Object> sendTest(String to, Long adminUserId) {
        String normalized = EmailAddresses.normalize(to);
        EmailSettings emailSettings = settingsProvider.load();
        if (!emailSettings.enabled()) {
            throw new BusinessException(400, "发信服务未配置（provider=off），请先保存并启用 SMTP 设置");
        }
        if (!emailSettings.configured()) {
            throw new BusinessException(400, "发信配置不完整，请检查 host / 端口 / 账号 / 发件地址");
        }

        String subject = "Bond 邮件配置自检";
        String sentAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String smtpHost = emailSettings.smtp().host() == null || emailSettings.smtp().host().isBlank()
                ? "(未配置)" : emailSettings.smtp().host();
        String text = "这是一封来自 Bond 后台的测试邮件，收到说明当前发信通道可用。\n\n"
                + "发送时间：" + sentAt + "\n"
                + "provider：" + emailSettings.provider() + "\n"
                + "SMTP 主机：" + smtpHost + "\n"
                + "调用者：管理员 #" + adminUserId + "\n\n"
                + "此邮件由系统自动发送，请勿回复。";
        // task-69：自检邮件必须同时带 text 与品牌化 html（不带任何凭据），否则下游网关 550。
        String html = "<!DOCTYPE html><html lang=\"zh-CN\"><body style=\"margin:0;padding:0;background:#f6f7f9;"
                + "font-family:-apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,'PingFang SC','Microsoft YaHei',sans-serif;"
                + "color:#1f2328;\"><div style=\"max-width:520px;margin:32px auto;background:#ffffff;border-radius:12px;"
                + "overflow:hidden;box-shadow:0 2px 12px rgba(16,24,40,.06);\">"
                + "<div style=\"background:#E57153;padding:20px 24px;color:#ffffff;font-size:18px;font-weight:600;\">"
                + "Bond 邮件配置自检</div><div style=\"padding:24px;font-size:14px;line-height:1.7;\">"
                + "<p style=\"margin:0 0 12px;\">这是一封来自 Bond 后台的测试邮件，收到说明当前发信通道可用。</p>"
                + "<table style=\"width:100%;border-collapse:collapse;font-size:13px;\">"
                + "<tr><td style=\"padding:8px 0;color:#667085;width:96px;\">发送时间</td><td style=\"padding:8px 0;\">"
                + escapeHtml(sentAt) + "</td></tr>"
                + "<tr><td style=\"padding:8px 0;color:#667085;\">provider</td><td style=\"padding:8px 0;\">"
                + escapeHtml(emailSettings.provider()) + "</td></tr>"
                + "<tr><td style=\"padding:8px 0;color:#667085;\">SMTP 主机</td><td style=\"padding:8px 0;\">"
                + escapeHtml(smtpHost) + "</td></tr>"
                + "<tr><td style=\"padding:8px 0;color:#667085;\">调用者</td><td style=\"padding:8px 0;\">管理员 #"
                + escapeHtml(String.valueOf(adminUserId)) + "</td></tr></table>"
                + "<p style=\"margin:16px 0 0;color:#98a2b3;font-size:12px;\">此邮件由系统自动发送，请勿回复。</p>"
                + "</div></div></body></html>";
        try {
            if (EmailSettings.PROVIDER_SMTP.equals(emailSettings.provider())) {
                new SmtpEmailGateway().send(emailSettings, normalized,
                        new EmailTemplateRenderer.RenderedEmail(subject, html, text));
            } else {
                configurableEmailGateway.send(normalized, subject, html, text);
            }
        } catch (com.bond.common.email.EmailSendException e) {
            throw new BusinessException(400, "发送失败：" + trimError(e.getMessage()));
        } catch (BusinessException e) {
            throw new BusinessException(400, "发送失败：" + trimError(e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("email test failed: provider={} err={}", emailSettings.provider(), e.getClass().getSimpleName());
            throw new BusinessException(400, "发送失败：" + trimError(e.getMessage()));
        }
        log.info("admin email test sent: provider={} to={} by={}",
                emailSettings.provider(), com.bond.common.email.EmailAddresses.normalize(normalized)
                        .replaceAll("(?<=.).(?=.*@)", "*"), adminUserId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("message", "已发送自检邮件（实际通道 " + emailSettings.provider() + "）");
        return result;
    }

    // ── 模板管理 ──

    public List<Map<String, Object>> listTemplates() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (EmailTemplate template : emailTemplateMapper.selectAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", template.getCode());
            item.put("name", template.getName());
            // task-71：后台模板列表要显示标题（subject）
            item.put("subject", template.getSubject());
            item.put("enabled", template.getEnabled());
            item.put("isBuiltin", template.getIsBuiltin());
            item.put("updatedAt", template.getUpdatedAt());
            list.add(item);
        }
        return list;
    }

    public Map<String, Object> getTemplate(String code) {
        EmailTemplate template = emailTemplateMapper.selectByCode(code);
        if (template == null) {
            throw new BusinessException(404, "邮件模板不存在: " + code);
        }
        return toTemplateDetail(template);
    }

    public Map<String, Object> updateTemplate(String code, Map<String, Object> body, Long adminUserId) {
        EmailTemplate existing = emailTemplateMapper.selectByCode(code);
        if (existing == null) {
            throw new BusinessException(404, "邮件模板不存在: " + code);
        }
        if (body == null || body.isEmpty()) {
            throw new BusinessException(400, "请求体不能为空");
        }
        String name = text(body.get("name"));
        String subject = text(body.get("subject"));
        if (name != null && name.length() > MAX_TEMPLATE_NAME) {
            throw new BusinessException(400, "模板名称不能超过 " + MAX_TEMPLATE_NAME + " 字符");
        }
        if (subject != null && subject.length() > MAX_TEMPLATE_SUBJECT) {
            throw new BusinessException(400, "模板主题不能超过 " + MAX_TEMPLATE_SUBJECT + " 字符");
        }
        if (subject != null && subject.isBlank()) {
            throw new BusinessException(400, "模板主题不能为空");
        }

        boolean builtin = Boolean.TRUE.equals(existing.getIsBuiltin());
        if (name != null) existing.setName(name.trim());
        if (subject != null) existing.setSubject(subject.trim());
        if (body.containsKey("bodyHtml")) existing.setBodyHtml(text(body.get("bodyHtml")));
        if (body.containsKey("bodyText")) existing.setBodyText(text(body.get("bodyText")));
        if (body.containsKey("enabled")) existing.setEnabled(boolValue(body.get("enabled")));
        if (body.containsKey("variables")) existing.setVariables(variablesJson(body.get("variables")));
        existing.setIsBuiltin(builtin);
        existing.setUpdatedBy(adminUserId);
        // task-71：variables 是 jsonb，必须走手写 SQL（#{variables}::jsonb），不能用 updateById
        emailTemplateMapper.updateTemplate(existing);
        log.info("admin updated email template: code={} by={}", code, adminUserId);
        return toTemplateDetail(emailTemplateMapper.selectByCode(code));
    }

    /**
     * 新增自定义模板（task-76 方案 B）。
     *
     * <p>code 由后端生成（{@code custom_xxxxxxxx}），防止「手填 code 的固定流程」把自定义模板变成一次性消耗品：
     * 前端只提供 name/subject/bodyHtml/bodyText/enabled，然后到「用途绑定」里选用该模板。
     * {@code variables} 自动从标题+正文解析（白名单），无需前端手写。</p>
     */
    public Map<String, Object> createTemplate(Map<String, Object> body, Long adminUserId) {
        if (body == null || body.isEmpty()) {
            throw new BusinessException(400, "请求体不能为空");
        }
        String name = text(body.get("name"));
        String subject = text(body.get("subject"));
        String bodyHtml = text(body.get("bodyHtml"));
        String bodyText = text(body.get("bodyText"));
        if (name == null || name.isBlank()) {
            throw new BusinessException(400, "模板名称不能为空");
        }
        if (name.trim().length() > MAX_TEMPLATE_NAME) {
            throw new BusinessException(400, "模板名称不能超过 " + MAX_TEMPLATE_NAME + " 字符");
        }
        if (subject == null || subject.isBlank()) {
            throw new BusinessException(400, "模板主题不能为空");
        }
        if (subject.trim().length() > MAX_TEMPLATE_SUBJECT) {
            throw new BusinessException(400, "模板主题不能超过 " + MAX_TEMPLATE_SUBJECT + " 字符");
        }
        if ((bodyHtml == null || bodyHtml.isBlank()) && (bodyText == null || bodyText.isBlank())) {
            throw new BusinessException(400, "正文 bodyHtml 与 bodyText 至少提供一个");
        }

        String code = generateCustomCode();
        EmailTemplate template = new EmailTemplate();
        template.setCode(code);
        template.setName(name.trim());
        template.setSubject(subject.trim());
        template.setBodyHtml(bodyHtml == null ? "" : bodyHtml);
        template.setBodyText(bodyText == null ? "" : bodyText);
        template.setVariables(variablesJson(EmailTemplateRenderer.referencedVariables(
                subject,
                bodyHtml == null ? "" : bodyHtml,
                bodyText == null ? "" : bodyText)));
        template.setEnabled(body.containsKey("enabled") ? boolValue(body.get("enabled")) : Boolean.TRUE);
        template.setIsBuiltin(false);
        template.setUpdatedBy(adminUserId);
        emailTemplateMapper.insertTemplate(template);
        log.info("admin created email template: code={} by={}", code, adminUserId);
        return toTemplateDetail(emailTemplateMapper.selectByCode(code));
    }

    /** 生成不与现有模板冲突的 custom_xxxxxxxx code（8 位十六进制，足够后台人工可读且不易撞）。 */
    private String generateCustomCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            byte[] bytes = new byte[4];
            RANDOM.nextBytes(bytes);
            StringBuilder code = new StringBuilder(CUSTOM_CODE_PREFIX);
            for (byte b : bytes) {
                code.append(String.format("%02x", b));
            }
            String candidate = code.toString();
            if (emailTemplateMapper.selectByCode(candidate) == null
                    && BuiltInEmailTemplates.get(candidate) == null) {
                return candidate;
            }
        }
        throw new BusinessException(500, "生成模板 code 失败，请重试");
    }

    /** 删除自定义模板（task-76）：内置模板不可删除（409）；已被用途绑定时先让管理员解绑（400）。 */
    public Map<String, Object> deleteTemplate(String code, Long adminUserId) {
        EmailTemplate existing = emailTemplateMapper.selectByCode(code);
        if (existing == null) {
            throw new BusinessException(404, "邮件模板不存在: " + code);
        }
        if (Boolean.TRUE.equals(existing.getIsBuiltin())
                || BuiltInEmailTemplates.get(code) != null) {
            throw new ConflictException(
                    "内置模板不可删除，可先「恢复默认」或直接编辑", Map.of("field", "isBuiltin"));
        }
        List<String> boundPurposes = bindingMapper.selectPurposesByTemplate(code);
        if (!boundPurposes.isEmpty()) {
            throw new BusinessException(400,
                    "该模板已被用途绑定，请先解除绑定: " + String.join(", ", boundPurposes));
        }
        emailTemplateMapper.deleteByCode(code);
        log.info("admin deleted email template: code={} by={}", code, adminUserId);
        return Map.of("code", code, "deleted", true);
    }


    /** 恢复内置默认（DB 行保留、内容重置；若行被删则重建）。 */
    public Map<String, Object> resetTemplate(String code, Long adminUserId) {
        EmailTemplate builtIn = com.bond.common.email.BuiltInEmailTemplates.get(code);
        if (builtIn == null) {
            throw new BusinessException(404, "没有可恢复的内置模板: " + code);
        }
        EmailTemplate existing = emailTemplateMapper.selectByCode(code);
        if (existing == null) {
            builtIn.setUpdatedBy(adminUserId);
            emailTemplateMapper.insertTemplate(builtIn);
        } else {
            builtIn.setIsBuiltin(true);
            builtIn.setUpdatedBy(adminUserId);
            emailTemplateMapper.updateTemplate(builtIn);
        }
        log.info("admin reset email template: code={} by={}", code, adminUserId);
        return toTemplateDetail(emailTemplateMapper.selectByCode(code));
    }

    // ── 用途绑定（task-76 方案 B）──

    /**
     * 用途列表：内置默认模板 + 当前绑定 + 生效模板。
     *
     * <p>后台 UI 用「用途 → 选择自定义模板」来表达文案替换，而不是让用户理解模板 code。</p>
     */
    public List<Map<String, Object>> purposes() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (EmailPurpose.Purpose purpose : EmailPurpose.all()) {
            EmailTemplateBinding binding = bindingMapper.selectByPurpose(purpose.code());
            String bound = binding == null ? null : binding.getTemplateCode();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("purpose", purpose.code());
            item.put("label", purpose.label());
            item.put("builtinTemplateCode", purpose.builtinTemplateCode());
            item.put("requiredVariables", purpose.requiredVariables());
            item.put("boundTemplateCode", bound);
            item.put("effectiveTemplateCode", bound == null ? purpose.builtinTemplateCode() : bound);
            // task-76：前端「已覆盖内置」标记；绑定内置 code 本身不算覆盖
            item.put("overridden", bound != null && !bound.equals(purpose.builtinTemplateCode()));
            list.add(item);
        }
        return list;
    }

    /**
     * 绑定「用途 → 模板」；返回绑定结果与 warnings（不是 4xx 的软提示，前端置顶展示）。
     *
     * <p>warnings 场景：模板禁用（发信会回退内置）、模板未使用该用途必需变量（可能渲染出空内容）。</p>
     *
     * <p>解绑：{@code templateCode} 传 null / 空串时删除该用途的绑定（恢复内置默认），
     * 返回 {@code boundTemplateCode=null}、{@code effectiveTemplateCode=<内置默认>}，HTTP 200。</p>
     */
    public Map<String, Object> bindPurpose(String purpose, String templateCode, Long adminUserId) {
        EmailPurpose.Purpose defined = EmailPurpose.require(purpose);
        if (templateCode == null || templateCode.isBlank()) {
            // task-76：解绑 = 用途回到内置默认模板（幂等：本来没绑定也返回 200）
            bindingMapper.deleteByPurpose(defined.code());
            log.info("admin unbound email purpose: purpose={} by={}", defined.code(), adminUserId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("purpose", defined.code());
            result.put("label", defined.label());
            result.put("boundTemplateCode", null);
            result.put("effectiveTemplateCode", defined.builtinTemplateCode());
            result.put("warnings", List.of());
            return result;
        }
        String code = templateCode.trim();
        EmailTemplate template = emailTemplateMapper.selectByCode(code);
        List<String> warnings = new ArrayList<>();
        if (template == null) {
            EmailTemplate builtin = BuiltInEmailTemplates.get(code);
            if (builtin == null) {
                throw new BusinessException(400, "模板不存在: " + code);
            }
            // 内置模板被删过：按内置默认重建，保证绑定可编辑、可预览
            builtin.setUpdatedBy(adminUserId);
            emailTemplateMapper.insertTemplate(builtin);
            template = emailTemplateMapper.selectByCode(code);
            warnings.add("模板 " + code + " 未入库，已按内置默认重建");
        }
        if (Boolean.FALSE.equals(template.getEnabled())) {
            warnings.add("模板当前为禁用状态，发信将回退内置默认");
        }
        List<String> used = EmailTemplateRenderer.referencedVariables(
                template.getSubject(), template.getBodyHtml(), template.getBodyText());
        List<String> missingRequired = new ArrayList<>();
        for (String required : defined.requiredVariables()) {
            if (!used.contains(required)) {
                missingRequired.add(required);
            }
        }
        if (!missingRequired.isEmpty()) {
            warnings.add("模板未使用必需变量: " + String.join(", ", missingRequired)
                    + "（渲染时不会出现在邮件中，可能缺少关键信息）");
        }

        EmailTemplateBinding binding = new EmailTemplateBinding();
        binding.setPurpose(defined.code());
        binding.setTemplateCode(code);
        binding.setUpdatedBy(adminUserId);
        bindingMapper.upsert(binding);
        log.info("admin bound email purpose: purpose={} code={} by={}", defined.code(), code, adminUserId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("purpose", defined.code());
        result.put("label", defined.label());
        result.put("boundTemplateCode", code);
        result.put("effectiveTemplateCode", code);
        result.put("warnings", warnings);
        return result;
    }

    /**
     * 预览渲染结果（不写库、不发信）。
     *
     * <p>两种入参二选一：{@code code}（直接指定模板）或 {@code purpose}（按用途解析：绑定优先 → 内置回落，
     * 与真实发信路径一致）。未赋值的占位符会置空，并在 missingVariables 中列出；warnings 提示用途必需变量缺失。</p>
     */
    public Map<String, Object> preview(String code, Map<String, Object> variables, String purpose) {
        if (purpose != null && !purpose.isBlank() && EmailPurpose.get(purpose) == null) {
            throw new BusinessException(400, "未知的邮件用途: " + purpose);
        }
        EmailPurpose.Purpose defined = (purpose == null || purpose.isBlank()) ? null : EmailPurpose.get(purpose);
        EmailTemplate template;
        if (code != null && !code.isBlank()) {
            template = emailTemplateMapper.selectByCode(code);
            if (template == null) {
                template = BuiltInEmailTemplates.get(code);
            }
            if (template == null) {
                throw new BusinessException(404, "邮件模板不存在: " + code);
            }
        } else if (defined != null) {
            // 给了 purpose：按「绑定 → 内置」解析，预览用户实际会收到的内容
            template = resolvePreviewTemplate(defined);
        } else {
            throw new BusinessException(400, "请提供模板 code 或 purpose");
        }
        Map<String, String> vars = new LinkedHashMap<>();
        Map<String, Object> input = asMap(variables);
        if (input != null) {
            input.forEach((key, value) -> vars.put(key, value == null ? "" : String.valueOf(value)));
        }
        // 先按调用方给的变量计算「缺哪些变量」，再加演示值方便后台看版式（task-76）
        List<String> missing = new ArrayList<>(EmailTemplateRenderer.missingVariables(template, vars));
        List<String> warnings = new ArrayList<>();
        if (defined != null) {
            for (String required : defined.requiredVariables()) {
                if (vars.get(required) == null || vars.get(required).isBlank()) {
                    if (!missing.contains(required)) {
                        missing.add(required);
                    }
                    warnings.add("缺少该用途必需变量: " + required);
                }
            }
        }
        vars.putIfAbsent("code", "123456");
        vars.putIfAbsent("username", "演示用户");
        vars.putIfAbsent("email", "user@example.com");
        vars.putIfAbsent("expiresMinutes", "10");
        vars.putIfAbsent("actionUrl", "https://example.com");
        vars.putIfAbsent("appName", configService.get("system.name", "Bond"));
        vars.putIfAbsent("year", String.valueOf(java.time.Year.now().getValue()));
        vars.putIfAbsent("supportEmail", configService.get("email.from.address", ""));
        vars.putIfAbsent("action", "账号已被禁用");
        EmailTemplateRenderer.RenderedEmail rendered = EmailTemplateRenderer.render(template, vars);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subject", rendered.subject());
        result.put("html", rendered.html());
        result.put("text", rendered.text());
        result.put("missingVariables", missing);
        result.put("warnings", warnings);
        return result;
    }

    // ── 私有工具 ──

    /** 预览用：按「绑定 → 内置」解析模板（与真实发信一致；绑定模板缺失/禁用时回落内置）。 */
    private EmailTemplate resolvePreviewTemplate(EmailPurpose.Purpose purpose) {
        EmailTemplateBinding binding = bindingMapper.selectByPurpose(purpose.code());
        if (binding != null) {
            EmailTemplate bound = emailTemplateMapper.selectByCode(binding.getTemplateCode());
            if (bound != null && Boolean.TRUE.equals(bound.getEnabled())) {
                return bound;
            }
        }
        EmailTemplate builtin = emailTemplateMapper.selectByCode(purpose.builtinTemplateCode());
        if (builtin == null) {
            builtin = BuiltInEmailTemplates.get(purpose.builtinTemplateCode());
        }
        if (builtin == null) {
            throw new BusinessException(400, "该用途暂无可用模板: " + purpose.code());
        }
        return builtin;
    }

    private Map<String, Object> toTemplateDetail(EmailTemplate template) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", template.getCode());
        detail.put("name", template.getName());
        detail.put("subject", template.getSubject());
        detail.put("bodyHtml", template.getBodyHtml());
        detail.put("bodyText", template.getBodyText());
        detail.put("variables", parseVariables(template.getVariables()));
        detail.put("enabled", template.getEnabled());
        detail.put("isBuiltin", template.getIsBuiltin());
        detail.put("updatedAt", template.getUpdatedAt());
        return detail;
    }

    private List<String> parseVariables(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (RuntimeException | java.io.IOException e) {
            return List.of();
        }
    }

    private String variablesJson(Object variables) {
        List<String> list;
        if (variables == null) {
            return "[]";
        }
        if (variables instanceof List<?> rawList) {
            list = new ArrayList<>();
            for (Object item : rawList) {
                if (item != null) {
                    list.add(String.valueOf(item).trim());
                }
            }
        } else {
            String raw = text(variables);
            list = new ArrayList<>();
            if (raw != null && !raw.isBlank()) {
                for (String part : raw.split("[,\\s]+")) {
                    if (!part.isBlank()) {
                        list.add(part.trim());
                    }
                }
            }
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BusinessException(400, "variables 不是合法的变量列表");
        }
    }

    /** 敏感值掩码：未配置返回空串；已配置只露尾 4 位（明文优先，密文解密失败则用密文尾）。 */
    private static String maskSecret(String stored) {
        if (stored == null || stored.isBlank()) {
            return "";
        }
        String plain = com.bond.common.email.ConfigCipher.fromEnvironment().tryDecrypt(stored);
        String value = plain == null || plain.isBlank() ? stored : plain;
        if (value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }

    private static String trimError(String message) {
        if (message == null || message.isBlank()) {
            return "下游服务未返回错误信息";
        }
        return message.length() > MAX_TEST_ERROR ? message.substring(0, MAX_TEST_ERROR) + "..." : message;
    }

    /** 自检邮件 HTML 插值转义（值来自配置/管理员 id，仍按不可信处理）。 */
    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String raw = text(value);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String raw = text(value);
        return raw != null && "true".equalsIgnoreCase(raw.trim());
    }
}
