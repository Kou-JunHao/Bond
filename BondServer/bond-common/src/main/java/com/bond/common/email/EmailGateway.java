package com.bond.common.email;

/**
 * 发信网关（task-63）：运行时按「DB 设置 → env 回退 → 默认(off)」选实现；
 * 具体实现在 {@link ConfigurableEmailGateway} 内部按 provider 分派。
 *
 * <p>接口放在 bond-common，bond-auth（注册/找回/改邮箱验证码）与
 * bond-transfer（管理台重置密码、编辑邮箱、发信配置自检）共用同一实现，避免两套发信代码漂移。</p>
 */
public interface EmailGateway {

    /** 发信通道是否启用（provider != off）。 */
    boolean isEnabled();

    /**
     * 同步发送一封纯文本邮件。
     *
     * @throws com.bond.common.exception.BusinessException 503：未配置 / 发送失败（不泄漏内部细节）
     */
    void send(String to, String subject, String text);

    /**
     * 同步发送一封 HTML + 纯文本邮件（task-69）。正文为空由具体实现抛 400，不允许把空正文发出去。
     *
     * <p>默认实现把 HTML 粗转纯文本后走 {@link #send(String, String, String)}；
     * 支持 HTML 的通道（{@link ConfigurableEmailGateway}）会覆写此方法。</p>
     */
    default void send(String to, String subject, String html, String text) {
        String plain = (text == null || text.isBlank()) ? EmailTemplateRenderer.htmlToText(html) : text;
        send(to, subject, plain);
    }

    /**
     * 按模板发送（task-63）：按用途选模板 → 变量填充 → 渲染 → 发送。
     * 默认实现表示当前网关不支持模板发送；{@link ConfigurableEmailGateway} 提供真正实现。
     *
     * @param to           收件人
     * @param templateCode 模板 code（如 verification_code）
     * @param variables    变量（白名单内，见 {@link EmailTemplateRenderer#ALLOWED_VARIABLES}）
     * @throws com.bond.common.exception.BusinessException 503：未配置 / 发送失败
     */
    default void sendTemplate(String to, String templateCode, java.util.Map<String, String> variables) {
        throw new com.bond.common.exception.BusinessException(500, "当前发信通道不支持模板发送");
    }

    /**
     * 按「用途」发送（task-76）：用途 → 后台绑定模板 →（缺失/禁用时）该用途内置默认模板。
     *
     * <p>默认实现退化为按内置模板发送；{@link ConfigurableEmailGateway} 会尊重后台绑定。</p>
     *
     * @param to        收件人
     * @param purpose   用途编码（见 {@link EmailPurpose}）
     * @param variables 变量（白名单见 {@link EmailTemplateRenderer#ALLOWED_VARIABLES}）
     * @throws com.bond.common.exception.BusinessException 400 未知用途；503 未配置/发送失败
     */
    default void sendByPurpose(String to, String purpose, java.util.Map<String, String> variables) {
        String templateCode = EmailPurpose.builtinTemplateCode(purpose);
        if (templateCode == null) {
            throw new com.bond.common.exception.BusinessException(400, "未知的邮件用途: " + purpose);
        }
        sendTemplate(to, templateCode, variables);
    }
}
