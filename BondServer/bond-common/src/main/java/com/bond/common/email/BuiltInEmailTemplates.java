package com.bond.common.email;

import com.bond.common.entity.EmailTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置邮件模板（task-63）：DB（V16 seed）之外的兜底副本。
 *
 * <p>用途：① 首次部署/模板行被误删时仍能发信；② 后台「恢复默认」的数据源。
 * 文案与 V16 seed 保持一致（改文案时两处同步，README 有说明）。</p>
 */
public final class BuiltInEmailTemplates {

    public static final String VERIFICATION_CODE = "verification_code";
    public static final String PASSWORD_RESET = "password_reset";
    public static final String CHANGE_EMAIL = "change_email";
    public static final String ADMIN_RESET_PASSWORD = "admin_reset_password";
    public static final String ADMIN_OPERATION_NOTICE = "admin_operation_notice";
    public static final String WELCOME = "welcome";

    private static final Map<String, EmailTemplate> TEMPLATES = new LinkedHashMap<>();

    static {
        put(VERIFICATION_CODE, "注册验证码", "{{appName}} 注册验证码",
                codeBody("注册", "{{code}}", "{{expiresMinutes}}"),
                new String[]{"code", "username", "appName", "expiresMinutes", "year", "supportEmail"});
        put(PASSWORD_RESET, "密码重置验证码", "{{appName}} 密码重置验证码",
                codeBody("重置密码", "{{code}}", "{{expiresMinutes}}"),
                new String[]{"code", "username", "appName", "expiresMinutes", "year", "supportEmail"});
        put(CHANGE_EMAIL, "改绑邮箱验证码", "{{appName}} 邮箱改绑验证码",
                codeBody("改绑邮箱", "{{code}}", "{{expiresMinutes}}"),
                new String[]{"code", "username", "email", "appName", "expiresMinutes", "year", "supportEmail"});
        put(ADMIN_RESET_PASSWORD, "管理员重置密码", "{{appName}} 账号密码已被管理员重置",
                adminResetBody(),
                new String[]{"code", "username", "appName", "year", "supportEmail"});
        put(ADMIN_OPERATION_NOTICE, "管理员操作通知", "{{appName}} 账号操作通知",
                noticeBody(),
                new String[]{"action", "username", "appName", "year", "supportEmail"});
        put(WELCOME, "欢迎邮件", "欢迎加入 {{appName}}",
                welcomeBody(),
                new String[]{"username", "appName", "year", "supportEmail", "actionUrl"});
    }

    private BuiltInEmailTemplates() {
    }

    /** 返回内置模板副本；未知 code 返回 null。 */
    public static EmailTemplate get(String code) {
        EmailTemplate source = TEMPLATES.get(code);
        if (source == null) {
            return null;
        }
        EmailTemplate copy = new EmailTemplate();
        copy.setCode(source.getCode());
        copy.setName(source.getName());
        copy.setSubject(source.getSubject());
        copy.setBodyHtml(source.getBodyHtml());
        copy.setBodyText(source.getBodyText());
        copy.setVariables(source.getVariables());
        copy.setEnabled(true);
        copy.setIsBuiltin(true);
        return copy;
    }

    public static Map<String, EmailTemplate> all() {
        return TEMPLATES;
    }

    private static void put(String code, String name, String subject, String[] bodies, String[] variables) {
        EmailTemplate t = new EmailTemplate();
        t.setCode(code);
        t.setName(name);
        t.setSubject(subject);
        t.setBodyHtml(bodies[0]);
        t.setBodyText(bodies[1]);
        t.setVariables(toJsonArray(variables));
        t.setEnabled(true);
        t.setIsBuiltin(true);
        TEMPLATES.put(code, t);
    }

    private static String[] codeBody(String action, String code, String expires) {
        String html = "<!DOCTYPE html>\n"
                + "<html><body style=\"margin:0;padding:24px;background:#f6f7f9;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1f2328;\">\n"
                + "  <div style=\"max-width:520px;margin:0 auto;background:#ffffff;border-radius:12px;padding:28px;\">\n"
                + "    <div style=\"font-size:20px;font-weight:600;color:#E57153;margin-bottom:16px;\">{{appName}}</div>\n"
                + "    <p style=\"font-size:15px;line-height:1.7;margin:0 0 8px;\">你好 {{username}}：</p>\n"
                + "    <p style=\"font-size:15px;line-height:1.7;margin:0 0 16px;\">你正在进行「" + action + "」操作，验证码如下：</p>\n"
                + "    <div style=\"font-size:30px;letter-spacing:8px;font-weight:700;background:#fff4f0;border-radius:8px;padding:16px;text-align:center;color:#D46A4C;\">" + code + "</div>\n"
                + "    <p style=\"font-size:13px;color:#6b7280;line-height:1.6;margin:16px 0 0;\">有效期 " + expires + " 分钟，仅可使用一次。若非本人操作，请忽略本邮件。</p>\n"
                + "    <hr style=\"border:none;border-top:1px solid #eeeeee;margin:24px 0 16px;\">\n"
                + "    <p style=\"font-size:12px;color:#9ca3af;margin:0;\">{{appName}} · {{year}} · 联系 {{supportEmail}}</p>\n"
                + "  </div>\n"
                + "</body></html>";
        String text = "你好 {{username}}：\n\n"
                + "你正在进行「" + action + "」操作，验证码：" + code + "\n"
                + "有效期 " + expires + " 分钟，仅可使用一次。\n"
                + "若非本人操作，请忽略本邮件。\n\n"
                + "{{appName}} · {{year}} · 联系 {{supportEmail}}\n";
        return new String[]{html, text};
    }

    private static String[] adminResetBody() {
        String html = "<!DOCTYPE html>\n"
                + "<html><body style=\"margin:0;padding:24px;background:#f6f7f9;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1f2328;\">\n"
                + "  <div style=\"max-width:520px;margin:0 auto;background:#ffffff;border-radius:12px;padding:28px;\">\n"
                + "    <div style=\"font-size:20px;font-weight:600;color:#E57153;margin-bottom:16px;\">{{appName}}</div>\n"
                + "    <p style=\"font-size:15px;line-height:1.7;\">你好 {{username}}：</p>\n"
                + "    <p style=\"font-size:15px;line-height:1.7;\">管理员已重置你的账号密码，临时密码如下：</p>\n"
                + "    <div style=\"font-size:22px;letter-spacing:4px;font-weight:700;background:#fff4f0;border-radius:8px;padding:16px;text-align:center;color:#D46A4C;\">{{code}}</div>\n"
                + "    <p style=\"font-size:13px;color:#6b7280;line-height:1.6;\">请使用用户名 + 临时密码登录，并立即在「设置 → 修改密码」中修改。若你并未申请重置，请尽快联系管理员。</p>\n"
                + "    <hr style=\"border:none;border-top:1px solid #eeeeee;margin:24px 0 16px;\">\n"
                + "    <p style=\"font-size:12px;color:#9ca3af;margin:0;\">{{appName}} · {{year}} · 联系 {{supportEmail}}</p>\n"
                + "  </div>\n"
                + "</body></html>";
        String text = "你好 {{username}}：\n\n管理员已重置你的 {{appName}} 账号密码，临时密码：{{code}}\n\n"
                + "请使用用户名 + 临时密码登录，并立即修改。若你并未申请重置，请尽快联系管理员。\n\n"
                + "{{appName}} · {{year}} · 联系 {{supportEmail}}\n";
        return new String[]{html, text};
    }

    private static String[] welcomeBody() {
        String html = "<!DOCTYPE html>\n"
                + "<html><body style=\"margin:0;padding:24px;background:#f6f7f9;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1f2328;\">\n"
                + "  <div style=\"max-width:520px;margin:0 auto;background:#ffffff;border-radius:12px;padding:28px;\">\n"
                + "    <div style=\"font-size:20px;font-weight:600;color:#E57153;margin-bottom:16px;\">{{appName}}</div>\n"
                + "    <p style=\"font-size:15px;line-height:1.7;\">你好 {{username}}，欢迎加入 {{appName}}！</p>\n"
                + "    <p style=\"font-size:15px;line-height:1.7;\">你可以上传文件、创建共享空间，或与好友互传文件。</p>\n"
                + "    <p style=\"font-size:15px;line-height:1.7;\"><a href=\"{{actionUrl}}\" style=\"color:#D46A4C;\">打开 {{appName}}</a></p>\n"
                + "    <hr style=\"border:none;border-top:1px solid #eeeeee;margin:24px 0 16px;\">\n"
                + "    <p style=\"font-size:12px;color:#9ca3af;margin:0;\">{{appName}} · {{year}} · 联系 {{supportEmail}}</p>\n"
                + "  </div>\n"
                + "</body></html>";
        String text = "你好 {{username}}，欢迎加入 {{appName}}！\n\n"
                + "你可以上传文件、创建共享空间，或与好友互传文件。\n\n"
                + "{{appName}} · {{year}} · 联系 {{supportEmail}}\n";
        return new String[]{html, text};
    }

    private static String[] noticeBody() {
        String html = "<!DOCTYPE html>\n"
                + "<html><body style=\"margin:0;padding:24px;background:#f6f7f9;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1f2328;\">\n"
                + "  <div style=\"max-width:520px;margin:0 auto;background:#ffffff;border-radius:12px;padding:28px;\">\n"
                + "    <div style=\"font-size:20px;font-weight:600;color:#E57153;margin-bottom:16px;\">{{appName}}</div>\n"
                + "    <p style=\"font-size:15px;line-height:1.7;\">你好 {{username}}：</p>\n"
                + "    <p style=\"font-size:15px;line-height:1.7;\">管理员对你的账号执行了操作：{{action}}。</p>\n"
                + "    <p style=\"font-size:13px;color:#6b7280;line-height:1.6;\">如非本人预期，请尽快联系 {{supportEmail}}。</p>\n"
                + "    <hr style=\"border:none;border-top:1px solid #eeeeee;margin:24px 0 16px;\">\n"
                + "    <p style=\"font-size:12px;color:#9ca3af;margin:0;\">{{appName}} · {{year}} · 联系 {{supportEmail}}</p>\n"
                + "  </div>\n"
                + "</body></html>";
        String text = "你好 {{username}}：\n\n管理员对你的 {{appName}} 账号执行了操作：{{action}}。\n"
                + "如非本人预期，请尽快联系 {{supportEmail}}。\n\n"
                + "{{appName}} · {{year}} · 联系 {{supportEmail}}\n";
        return new String[]{html, text};
    }

    private static String toJsonArray(String[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values[i]).append('"');
        }
        return sb.append(']').toString();
    }
}
