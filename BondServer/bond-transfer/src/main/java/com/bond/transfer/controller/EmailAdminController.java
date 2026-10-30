package com.bond.transfer.controller;

import com.bond.common.dto.PageResult;
import com.bond.common.dto.Result;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.audit.AdminAudit;
import com.bond.transfer.dto.EmailDeliveryDTO;
import com.bond.transfer.dto.EmailDeliveryStatsDTO;
import com.bond.transfer.service.EmailAdminService;
import com.bond.transfer.service.EmailDeliveryAdminService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理台「邮件」设置与模板管理（task-63）。
 *
 * <p>鉴权：路径落在 {@code /api/admin/**}，由 AdminInterceptor 校验管理员身份；
 * 改动类接口打 {@link AdminAudit} 审计注解（写 admin_operation_logs）。</p>
 *
 * <p>敏感值口径：GET 只返回掩码；PUT 时 password/apiKey 传空或掩码值表示「不修改」。</p>
 */
@RestController
@RequestMapping({"/api/admin/mail", "/api/admin/email"})
@RequiredArgsConstructor
public class EmailAdminController {

    private final EmailAdminService emailAdminService;
    private final EmailDeliveryAdminService emailDeliveryAdminService;

    // ── 发信设置 ──

    @GetMapping("/settings")
    public Result<Map<String, Object>> settings() {
        return Result.ok(emailAdminService.settings());
    }

    @AdminAudit(action = "email.settings.update", targetType = "email_settings")
    @PutMapping("/settings")
    public Result<Map<String, Object>> updateSettings(@RequestHeader("X-User-Id") Long adminUserId,
                                                      @RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        return Result.ok(emailAdminService.updateSettings(body, adminUserId));
    }

    @AdminAudit(action = "email.test", targetType = "email_settings")
    @PostMapping("/test")
    public Result<Map<String, Object>> test(@RequestHeader("X-User-Id") Long adminUserId,
                                            @RequestBody(required = false) Map<String, Object> body,
                                            HttpServletRequest request) {
        String to = body == null || body.get("to") == null ? null : String.valueOf(body.get("to"));
        if (to == null || to.isBlank()) {
            throw new BusinessException(400, "请提供收件人 to");
        }
        return Result.ok(emailAdminService.sendTest(to, adminUserId));
    }

    // ── 模板管理 ──

    @GetMapping("/templates")
    public Result<List<Map<String, Object>>> templates() {
        return Result.ok(emailAdminService.listTemplates());
    }

    /** 新增自定义模板（task-76）。 */
    @AdminAudit(action = "email.template.create", targetType = "email_template")
    @PostMapping("/templates")
    public Result<Map<String, Object>> createTemplate(@RequestHeader("X-User-Id") Long adminUserId,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      HttpServletRequest request) {
        return Result.ok(emailAdminService.createTemplate(body, adminUserId));
    }

    /** 删除自定义模板（task-76）：内置模板返回 409，提示先「恢复默认」；被用途绑定时返回 400。 */
    @AdminAudit(action = "email.template.delete", targetType = "email_template")
    @DeleteMapping("/templates/{code}")
    public ResponseEntity<Void> deleteTemplate(@RequestHeader("X-User-Id") Long adminUserId,
                                               @PathVariable String code,
                                               HttpServletRequest request) {
        emailAdminService.deleteTemplate(code, adminUserId);
        return ResponseEntity.noContent().build();
    }

    // ── 用途绑定（task-76 方案 B）──

    /** 发信用途目录 + 当前绑定（后台「用哪个模板」下拉的数据源）。 */
    @GetMapping("/purposes")
    public Result<List<Map<String, Object>>> purposes() {
        return Result.ok(emailAdminService.purposes());
    }

    /** 把某用途绑定到指定模板；返回 warnings（模板禁用/缺必需变量等软提示）。 */
    @AdminAudit(action = "email.purpose.bind", targetType = "email_purpose")
    @PutMapping("/purposes/{purpose}")
    public Result<Map<String, Object>> bindPurpose(@RequestHeader("X-User-Id") Long adminUserId,
                                                   @PathVariable String purpose,
                                                   @RequestBody(required = false) Map<String, Object> body,
                                                   HttpServletRequest request) {
        String templateCode = body == null || body.get("templateCode") == null
                ? null : String.valueOf(body.get("templateCode"));
        return Result.ok(emailAdminService.bindPurpose(purpose, templateCode, adminUserId));
    }

    @GetMapping("/templates/{code}")
    public Result<Map<String, Object>> template(@PathVariable String code) {
        return Result.ok(emailAdminService.getTemplate(code));
    }

    @AdminAudit(action = "email.template.update", targetType = "email_template")
    @PutMapping("/templates/{code}")
    public Result<Map<String, Object>> updateTemplate(@RequestHeader("X-User-Id") Long adminUserId,
                                                      @PathVariable String code,
                                                      @RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        return Result.ok(emailAdminService.updateTemplate(code, body, adminUserId));
    }

    @AdminAudit(action = "email.template.reset", targetType = "email_template")
    @PostMapping("/templates/{code}/reset")
    public Result<Map<String, Object>> resetTemplate(@RequestHeader("X-User-Id") Long adminUserId,
                                                     @PathVariable String code,
                                                     HttpServletRequest request) {
        return Result.ok(emailAdminService.resetTemplate(code, adminUserId));
    }

    /** 预览渲染（不写库、不发信）；用于后台编辑模板时实时看效果。可用 code 指定模板，或只给 purpose（绑定优先→内置回落）。 */
    @PostMapping("/preview")
    public Result<Map<String, Object>> preview(@RequestBody(required = false) Map<String, Object> body) {
        boolean hasCode = body != null && body.get("code") != null && !String.valueOf(body.get("code")).isBlank();
        boolean hasPurpose = body != null && body.get("purpose") != null && !String.valueOf(body.get("purpose")).isBlank();
        if (!hasCode && !hasPurpose) {
            throw new BusinessException(400, "请提供模板 code 或 purpose");
        }
        String code = hasCode ? String.valueOf(body.get("code")) : null;
        String purpose = hasPurpose ? String.valueOf(body.get("purpose")) : null;
        Object variables = body.get("variables");
        return Result.ok(emailAdminService.preview(code,
                variables instanceof Map<?, ?> ? castMap(variables) : null, purpose));
    }

    // ── 投递日志（task-80）──

    /** 投递日志分页：只含元数据（掩码收件人/模板/状态/耗时/变量名），不含正文与变量值。 */
    @GetMapping("/delivery")
    public Result<PageResult<EmailDeliveryDTO>> delivery(@RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int size,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(required = false) String purpose) {
        return Result.ok(emailDeliveryAdminService.page(page, size, status, purpose));
    }

    /** 投递统计（默认最近 7 天）：总数/成功/失败 + 按用途分组。 */
    @GetMapping("/delivery/stats")
    public Result<EmailDeliveryStatsDTO> deliveryStats(@RequestParam(defaultValue = "7") int days) {
        return Result.ok(emailDeliveryAdminService.stats(days));
    }

    /**
     * 手动重发一条投递记录（task-134）：原模板 + 原变量 + 原收件人。
     *
     * <p>审计由 service 内写 {@code mail.delivery.resend}（避免 @AdminAudit 重复一条）；
     * 同一条 10 秒内重复调用返回 429；发送失败直接透传 provider 错误的 503 中文消息。</p>
     */
    @PostMapping("/delivery/{id}/resend")
    public Result<EmailDeliveryDTO> resendDelivery(@PathVariable long id,
                                                   @RequestHeader("X-User-Id") Long actorId,
                                                   HttpServletRequest request) {
        EmailDeliveryAdminService.ResendOutcome outcome =
                emailDeliveryAdminService.resend(id, actorId, clientIp(request));
        Result<EmailDeliveryDTO> result = Result.ok(outcome.latestDelivery());
        result.setMessage(outcome.message());
        return result;
    }

    /** X-Forwarded-For 首个地址；缺省 remoteAddr（与 AdminShareController 同口径）。 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
