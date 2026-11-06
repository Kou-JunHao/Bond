-- ============================================================
-- 修复：管理端「重置密码」邮件没有临时密码
--
-- 背景：V16 的 admin_reset_password 内置模板写的是
--   「临时密码由管理员另行告知」
-- 但 AdminUserEmailService 实际生成临时密码后只通过 {{code}} 传给模板，
-- 且该模板的 variables 也没有声明 code。DB 模板优先于 BuiltInEmailTemplates，
-- 因此线上邮件正文里根本没有临时密码，用户无法登录。
--
-- 处理：
--   1) 模板正文若还没有 {{code}}，替换为带临时密码的默认文案；
--   2) variables 追加 code（已存在则不重复追加）；
--   3) 已自定义且正文已含 {{code}} 的模板保持原样，避免覆盖管理员文案。
-- ============================================================

UPDATE email_templates
SET body_html = CASE
        WHEN body_html LIKE '%{{code}}%' THEN body_html
        ELSE $html$<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{appName}} 账号密码已被重置</title></head>
<body style="margin:0;padding:0;background:#f5f6f8;font-family:-apple-system,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#1f2329;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f5f6f8;padding:32px 12px;">
<tr><td align="center">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border-radius:12px;padding:32px;">
<tr><td>
<h2 style="margin:0 0 16px;font-size:20px;font-weight:600;color:#1f2329;">{{appName}} 账号密码已被重置</h2>
<p style="margin:0 0 12px;font-size:14px;line-height:1.7;color:#4e5969;">你好 {{username}}：管理员已重置你的账号密码。</p>
<p style="margin:0 0 12px;font-size:14px;line-height:1.7;color:#4e5969;">你的临时密码如下，请使用「用户名 + 临时密码」登录：</p>
<p style="margin:0 0 16px;padding:14px 16px;background:#fff4f0;border-radius:8px;font-size:22px;letter-spacing:4px;font-weight:700;text-align:center;color:#d46a4c;">{{code}}</p>
<p style="margin:0 0 12px;font-size:14px;line-height:1.7;color:#4e5969;">登录后请立即前往「设置 → 修改密码」更换为只有你知道的密码。</p>
<p style="margin:0 0 8px;font-size:13px;line-height:1.7;color:#86909c;">若你并未申请重置，请尽快联系管理员。</p>
<p style="margin:24px 0 0;padding-top:16px;border-top:1px solid #f0f1f3;font-size:12px;color:#c9cdd4;">{{appName}} · {{year}}</p>
</td></tr></table>
</td></tr></table>
</body></html>$html$
    END,
    body_text = CASE
        WHEN body_text LIKE '%{{code}}%' THEN body_text
        ELSE $text$你好 {{username}}：管理员已重置你的账号密码。

你的临时密码：{{code}}

请使用「用户名 + 临时密码」登录，并立即前往「设置 → 修改密码」更换为只有你知道的密码。
若你并未申请重置，请尽快联系管理员。

{{appName}} · {{year}}$text$
    END,
    variables = CASE
        WHEN variables @> '["code"]'::jsonb THEN variables
        ELSE variables || '["code"]'::jsonb
    END
WHERE code = 'admin_reset_password';
