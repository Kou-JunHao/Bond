-- ============================================================
--  task-63：可配置发信（默认 smtp，开箱即用）+ 邮件模板与样式管理
--  说明：
--   1) 发信设置统一存 system_config（不另开表），敏感值（smtp.password / resend.api_key）
--      由后台接口以 AES-GCM 加密写入，形如 enc:v1:<base64>，本迁移只写空默认值。
--   2) 默认 provider=smtp（开箱即用）：SMTP 参数由后台配置或环境变量提供，迁移不预置任何
--      具体服务商地址；SMTP 未配全时，服务端若发现 Resend Key 可用会自动回退 resend，
--      两者都不可用则发信接口返回 503「发信服务未配置」。
--   3) email_templates 内置 6 个模板（is_builtin=TRUE），后台可改、可 reset 回默认。
-- ============================================================

-- ── 发信设置（默认 smtp；SMTP 未配全且 Resend Key 可用时回退 resend） ──
INSERT INTO system_config (config_key, config_value, config_type, description) VALUES
('email.provider',            'smtp', 'string',  '发信通道: smtp|resend|bondmail|off（默认 smtp；SMTP 未配全且 Resend Key 可用时回退 resend；off 时发信接口返回 503「发信服务未配置」）'),
('email.from.address',        '',     'string',  '发件地址（留空时回退 SMTP 用户名）'),
('email.from.name',           'Bond', 'string',  '发件人显示名'),
('email.smtp.host',           '',     'string',  'SMTP 主机（仅来自后台配置/环境变量）'),
('email.smtp.port',           '587',  'int',     'SMTP 端口（465=隐式TLS；587=STARTTLS）'),
('email.smtp.username',       '',     'string',  'SMTP 用户名'),
('email.smtp.password',       '',     'string',  'SMTP 口令（AES-GCM 加密存储 enc:v1:）'),
('email.smtp.auth',           'true', 'boolean', 'SMTP 是否认证'),
('email.smtp.starttls',       'false','boolean', 'SMTP STARTTLS（与 ssl 互斥）'),
('email.smtp.ssl',            'false','boolean', 'SMTP 隐式 TLS（465）'),
('email.smtp.timeout_seconds','5',    'int',     'SMTP 超时秒数（默认 5）'),
('email.resend.api_key',      '',     'string',  'Resend API Key（AES-GCM 加密存储）'),
('email.resend.endpoint',     'https://api.resend.com', 'string', 'Resend endpoint'),
('email.bondmail.endpoint',   '',     'string',  'BondMail endpoint（可选兼容通道）')
ON CONFLICT (config_key) DO NOTHING;

-- ── 邮件模板 ──
CREATE TABLE email_templates (
    code        VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    subject     VARCHAR(300) NOT NULL,
    body_html   TEXT,
    body_text   TEXT,
    variables   JSONB NOT NULL DEFAULT '[]'::jsonb,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    is_builtin  BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by  BIGINT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_email_templates_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL
);

CREATE TRIGGER trg_email_templates_set_updated_at
    BEFORE UPDATE ON email_templates
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── 内置模板 seed（is_builtin=TRUE，后台可 reset 回本内容） ──
INSERT INTO email_templates (code, name, subject, body_html, body_text, variables, enabled, is_builtin) VALUES
(
 'verification_code', '邮箱验证码', '{{appName}} 邮箱验证',
 $html$<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{appName}} 邮箱验证</title></head>
<body style="margin:0;padding:0;background:#f5f6f8;font-family:-apple-system,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#1f2329;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f5f6f8;padding:32px 12px;">
<tr><td align="center">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border-radius:12px;padding:32px;">
<tr><td>
<h2 style="margin:0 0 16px;font-size:20px;font-weight:600;color:#1f2329;">{{appName}} 邮箱验证</h2>
<p style="margin:0 0 12px;font-size:14px;line-height:1.7;color:#4e5969;">你正在验证邮箱 <strong>{{email}}</strong>，验证码：</p>
<p style="margin:20px 0;text-align:center;font-size:32px;font-weight:700;letter-spacing:8px;color:#e57153;">{{code}}</p>
<p style="margin:0 0 8px;font-size:13px;line-height:1.7;color:#86909c;">验证码 {{expiresMinutes}} 分钟内有效，仅可使用一次。如非本人操作请忽略本邮件。</p>
<p style="margin:24px 0 0;padding-top:16px;border-top:1px solid #f0f1f3;font-size:12px;color:#c9cdd4;">{{appName}} · {{year}}</p>
</td></tr></table>
</td></tr></table>
</body></html>$html$,
 $text$你正在验证邮箱 {{email}}，验证码：{{code}}

验证码 {{expiresMinutes}} 分钟内有效，仅可使用一次。如非本人操作请忽略本邮件。

{{appName}} · {{year}}$text$,
 '["code","email","expiresMinutes","appName","year"]'::jsonb, TRUE, TRUE
),
(
 'password_reset', '找回密码验证码', '{{appName}} 密码重置',
 $html$<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{appName}} 密码重置</title></head>
<body style="margin:0;padding:0;background:#f5f6f8;font-family:-apple-system,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#1f2329;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f5f6f8;padding:32px 12px;">
<tr><td align="center">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border-radius:12px;padding:32px;">
<tr><td>
<h2 style="margin:0 0 16px;font-size:20px;font-weight:600;color:#1f2329;">{{appName}} 密码重置</h2>
<p style="margin:0 0 12px;font-size:14px;line-height:1.7;color:#4e5969;">我们收到重置账号（{{email}}）密码的请求，验证码：</p>
<p style="margin:20px 0;text-align:center;font-size:32px;font-weight:700;letter-spacing:8px;color:#e57153;">{{code}}</p>
<p style="margin:0 0 8px;font-size:13px;line-height:1.7;color:#86909c;">验证码 {{expiresMinutes}} 分钟内有效，仅可使用一次。</p>
<p style="margin:0;font-size:13px;line-height:1.7;color:#86909c;">如果并非你本人操作，请忽略本邮件，你的密码不会发生变化。</p>
<p style="margin:24px 0 0;padding-top:16px;border-top:1px solid #f0f1f3;font-size:12px;color:#c9cdd4;">{{appName}} · {{year}}</p>
</td></tr></table>
</td></tr></table>
</body></html>$html$,
 $text$我们收到重置账号（{{email}}）密码的请求，验证码：{{code}}

验证码 {{expiresMinutes}} 分钟内有效，仅可使用一次。
如果并非你本人操作，请忽略本邮件，你的密码不会发生变化。

{{appName}} · {{year}}$text$,
 '["code","email","expiresMinutes","appName","year"]'::jsonb, TRUE, TRUE
),
(
 'change_email', '改绑邮箱验证码', '{{appName}} 邮箱改绑确认',
 $html$<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{appName}} 邮箱改绑确认</title></head>
<body style="margin:0;padding:0;background:#f5f6f8;font-family:-apple-system,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#1f2329;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f5f6f8;padding:32px 12px;">
<tr><td align="center">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border-radius:12px;padding:32px;">
<tr><td>
<h2 style="margin:0 0 16px;font-size:20px;font-weight:600;color:#1f2329;">{{appName}} 邮箱改绑确认</h2>
<p style="margin:0 0 12px;font-size:14px;line-height:1.7;color:#4e5969;">你正在把账号绑定到邮箱 <strong>{{email}}</strong>，确认验证码：</p>
<p style="margin:20px 0;text-align:center;font-size:32px;font-weight:700;letter-spacing:8px;color:#e57153;">{{code}}</p>
<p style="margin:0 0 8px;font-size:13px;line-height:1.7;color:#86909c;">验证码 {{expiresMinutes}} 分钟内有效，仅可使用一次。如非本人操作，请立即修改密码并联系管理员。</p>
<p style="margin:24px 0 0;padding-top:16px;border-top:1px solid #f0f1f3;font-size:12px;color:#c9cdd4;">{{appName}} · {{year}}</p>
</td></tr></table>
</td></tr></table>
</body></html>$html$,
 $text$你正在把账号绑定到邮箱 {{email}}，确认验证码：{{code}}

验证码 {{expiresMinutes}} 分钟内有效，仅可使用一次。如非本人操作，请立即修改密码并联系管理员。

{{appName}} · {{year}}$text$,
 '["code","email","expiresMinutes","appName","year"]'::jsonb, TRUE, TRUE
),
(
 'admin_reset_password', '管理员重置密码', '{{appName}} 账号密码已被重置',
 $html$<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{appName}} 账号密码已被重置</title></head>
<body style="margin:0;padding:0;background:#f5f6f8;font-family:-apple-system,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#1f2329;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f5f6f8;padding:32px 12px;">
<tr><td align="center">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border-radius:12px;padding:32px;">
<tr><td>
<h2 style="margin:0 0 16px;font-size:20px;font-weight:600;color:#1f2329;">{{appName}} 账号密码已被重置</h2>
<p style="margin:0 0 12px;font-size:14px;line-height:1.7;color:#4e5969;">你好 {{username}}：管理员已重置你的账号密码。</p>
<p style="margin:0 0 12px;font-size:14px;line-height:1.7;color:#4e5969;">临时密码由管理员另行告知；请在登录后立即前往「设置 → 修改密码」更换为只有你知道的密码。</p>
<p style="margin:0 0 8px;font-size:13px;line-height:1.7;color:#86909c;">若你并未申请重置，请尽快联系管理员。</p>
<p style="margin:24px 0 0;padding-top:16px;border-top:1px solid #f0f1f3;font-size:12px;color:#c9cdd4;">{{appName}} · {{year}}</p>
</td></tr></table>
</td></tr></table>
</body></html>$html$,
 $text$你好 {{username}}：管理员已重置你的账号密码。

临时密码由管理员另行告知；请在登录后立即前往「设置 → 修改密码」更换为只有你知道的密码。
若你并未申请重置，请尽快联系管理员。

{{appName}} · {{year}}$text$,
 '["username","appName","year"]'::jsonb, TRUE, TRUE
),
(
 'welcome', '欢迎邮件', '欢迎使用 {{appName}}',
 $html$<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>欢迎使用 {{appName}}</title></head>
<body style="margin:0;padding:0;background:#f5f6f8;font-family:-apple-system,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#1f2329;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f5f6f8;padding:32px 12px;">
<tr><td align="center">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border-radius:12px;padding:32px;">
<tr><td>
<h2 style="margin:0 0 16px;font-size:20px;font-weight:600;color:#1f2329;">欢迎使用 {{appName}}</h2>
<p style="margin:0 0 12px;font-size:14px;line-height:1.7;color:#4e5969;">你好 {{username}}：你的 {{appName}} 账号已就绪，邮箱 {{email}} 已绑定。</p>
<p style="margin:0 0 12px;font-size:14px;line-height:1.7;color:#4e5969;">可以开始上传文件、创建在线文档、与好友互传文件了。</p>
<p style="margin:0 0 8px;font-size:13px;line-height:1.7;color:#86909c;">如需帮助，请联系 {{supportEmail}}。</p>
<p style="margin:24px 0 0;padding-top:16px;border-top:1px solid #f0f1f3;font-size:12px;color:#c9cdd4;">{{appName}} · {{year}}</p>
</td></tr></table>
</td></tr></table>
</body></html>$html$,
 $text$你好 {{username}}：你的 {{appName}} 账号已就绪，邮箱 {{email}} 已绑定。

可以开始上传文件、创建在线文档、与好友互传文件了。
如需帮助，请联系 {{supportEmail}}。

{{appName}} · {{year}}$text$,
 '["username","email","appName","supportEmail","year"]'::jsonb, TRUE, TRUE
),
(
 'admin_operation_notice', '管理员操作通知', '{{appName}} 账号操作通知',
 $html$<!DOCTYPE html>
<html><body style="margin:0;padding:24px;background:#f6f7f9;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1f2328;">
  <div style="max-width:520px;margin:0 auto;background:#ffffff;border-radius:12px;padding:28px;">
    <div style="font-size:20px;font-weight:600;color:#E57153;margin-bottom:16px;">{{appName}}</div>
    <p style="font-size:15px;line-height:1.7;">你好 {{username}}：</p>
    <p style="font-size:15px;line-height:1.7;">管理员对你的账号执行了操作：{{action}}。</p>
    <p style="font-size:13px;color:#6b7280;line-height:1.6;">如非本人预期，请尽快联系 {{supportEmail}}。</p>
    <hr style="border:none;border-top:1px solid #eeeeee;margin:24px 0 16px;">
    <p style="font-size:12px;color:#9ca3af;margin:0;">{{appName}} · {{year}} · 联系 {{supportEmail}}</p>
  </div>
</body></html>$html$,
 $text$你好 {{username}}：管理员对你的 {{appName}} 账号执行了操作：{{action}}。
如非本人预期，请尽快联系 {{supportEmail}}。

{{appName}} · {{year}} · 联系 {{supportEmail}}$text$,
 '["action","username","appName","year","supportEmail"]'::jsonb, TRUE, TRUE
)
ON CONFLICT (code) DO NOTHING;
