-- task-134：投递日志补「尝试次数 / 重发载荷」。
-- 说明：
--   attempts      本次投递实际尝试次数（1 = 首次即结束；task-129 的自动重试会累加）。
--   resend_payload 仅用于「管理端手动重发失败邮件」：存 AES-GCM（enc:v1:）加密后的
--                 {"to":"<原始收件人>","variables":{...原始变量...}}。
--                 不存明文收件人/变量值；列可为 NULL（历史行 → 重发接口返回 400）。
--                 该列随投递日志按 30 天保留期一起清理，不单独暴露任何读接口。
-- 注意：本表是追加式审计表（V18 起），本迁移只加列，不改动既有列与索引。
-- 注意：这里故意不写 IF NOT EXISTS —— scripts/verify-schema.ps1 的 ALTER 解析只识别
-- 「ADD COLUMN <列名> ...」形式，多余关键字会被误当成列名导致校验失败（V25 同类坑）。
ALTER TABLE email_delivery_log ADD COLUMN attempts INT NOT NULL DEFAULT 1;

ALTER TABLE email_delivery_log ADD COLUMN resend_payload TEXT NULL;

COMMENT ON COLUMN email_delivery_log.attempts IS '本次投递尝试次数（含自动重试；首次成功=1）';
COMMENT ON COLUMN email_delivery_log.resend_payload IS '管理端重发用载荷：AES-GCM(enc:v1:) 加密的 to+variables JSON；NULL=不可重发';
