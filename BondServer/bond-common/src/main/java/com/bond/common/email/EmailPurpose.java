package com.bond.common.email;

import com.bond.common.exception.BusinessException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邮件用途目录（task-76 方案 B）。
 *
 * <p>「用途」是后端固定的业务语义（注册验证码、重置密码、欢迎邮件等），每个用途有一个内置默认模板；
 * 后台可以给用途绑定任意自定义模板（绑定关系存 {@code email_template_bindings}，V17），
 * 发信时统一按「绑定 → 内置默认」解析。这样用户改文案不必改代码，也不会因为自定义模板缺失而发不出信。</p>
 *
 * <p>用途编码是稳定契约（出现在绑定表与后台接口里），不要随意重命名。</p>
 */
public final class EmailPurpose {

    public static final String REGISTER_CODE = "register_code";
    public static final String PASSWORD_RESET = "password_reset";
    public static final String CHANGE_EMAIL = "change_email";
    public static final String WELCOME = "welcome";
    public static final String ADMIN_RESET_PASSWORD = "admin_reset_password";
    public static final String ADMIN_OPERATION_NOTICE = "admin_operation_notice";

    /** 单个用途定义：编码、中文名、内置默认模板 code、最少需要的变量（用于后台提示）。 */
    public record Purpose(String code, String label, String builtinTemplateCode, List<String> requiredVariables) {
    }

    private static final List<Purpose> ALL = List.of(
            new Purpose(REGISTER_CODE, "注册验证码", BuiltInEmailTemplates.VERIFICATION_CODE,
                    List.of("code", "expiresMinutes")),
            new Purpose(PASSWORD_RESET, "重置密码验证码", BuiltInEmailTemplates.PASSWORD_RESET,
                    List.of("code", "expiresMinutes")),
            new Purpose(CHANGE_EMAIL, "变更邮箱验证码", BuiltInEmailTemplates.CHANGE_EMAIL,
                    List.of("code", "expiresMinutes")),
            new Purpose(WELCOME, "注册欢迎", BuiltInEmailTemplates.WELCOME,
                    List.of("username")),
            new Purpose(ADMIN_RESET_PASSWORD, "管理员重置密码", BuiltInEmailTemplates.ADMIN_RESET_PASSWORD,
                    List.of("code", "username")),
            new Purpose(ADMIN_OPERATION_NOTICE, "管理员操作通知", BuiltInEmailTemplates.ADMIN_OPERATION_NOTICE,
                    List.of("action", "username")));

    private static final Map<String, Purpose> BY_CODE = new LinkedHashMap<>();

    static {
        for (Purpose purpose : ALL) {
            BY_CODE.put(purpose.code(), purpose);
        }
    }

    private EmailPurpose() {
    }

    public static List<Purpose> all() {
        return ALL;
    }

    /** 找不到返回 null（后台接口据此报 400）。 */
    public static Purpose get(String code) {
        return code == null ? null : BY_CODE.get(code.trim());
    }

    /** 用途的内置默认模板 code；未知用途返回 null。 */
    public static String builtinTemplateCode(String code) {
        Purpose purpose = get(code);
        return purpose == null ? null : purpose.builtinTemplateCode();
    }

    /** 发信前调用：未知用途直接 400，避免把信发成「无模板」。 */
    public static Purpose require(String code) {
        Purpose purpose = get(code);
        if (purpose == null) {
            throw new BusinessException(400, "未知的邮件用途: " + code);
        }
        return purpose;
    }
}
