-- task-80：邮件投递日志（只记元数据，不存正文/变量值）。
-- 目的：让「到底发了什么、发成功没有、有没有 {{ 残留」可查，替代再造一次性信箱的人工排查。
-- 注意：本表是纯追加（新表），不改动任何既有表与 V1–V17。
CREATE TABLE IF NOT EXISTS email_delivery_log (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NULL,
    -- 收件人掩码（如 bo***@example.com），绝不存完整地址
    to_masked VARCHAR(120) NOT NULL,
    -- 按用途发送时的 purpose（register_code / password_reset / ... 可为空）
    purpose VARCHAR(40) NULL,
    -- 实际使用的模板 code（DB 自定义或内置）
    template_code VARCHAR(64) NULL,
    -- 渲染后的主题（不含正文）
    subject VARCHAR(300) NOT NULL,
    -- sent / failed
    status VARCHAR(16) NOT NULL,
    -- 失败原因摘要（provider 原文截断 + 脱敏，≤500）
    failure_reason VARCHAR(500) NULL,
    -- 只存变量名列表的 JSON 数组（如 ["code","username"]），绝不存变量值
    variable_keys TEXT NOT NULL DEFAULT '[]',
    -- 渲染后残留 `{{` 的个数（0 = 正常）
    placeholders_left INT NOT NULL DEFAULT 0,
    -- smtp / resend / bondmail
    provider VARCHAR(20) NOT NULL,
    duration_ms INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_email_delivery_log_created
    ON email_delivery_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_email_delivery_log_status_created
    ON email_delivery_log (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_email_delivery_log_purpose_created
    ON email_delivery_log (purpose, created_at DESC);
