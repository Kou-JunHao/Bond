-- 用户邮箱 + 邮箱验证码（task-57）。
--
-- 纪律（工程坑 #17）：本文件一旦被生产 Flyway 应用即不可再改内容，
-- 任何后续调整必须新建更高版本号；上线顺序必须 V14（本文件）先于 V15（公告/审计）。
--
-- 只新增列/表/索引，不动既有列；历史用户 email 为 NULL，不受唯一索引影响。

ALTER TABLE users ADD COLUMN email VARCHAR(254);
ALTER TABLE users ADD COLUMN tokens_valid_after TIMESTAMPTZ;

COMMENT ON COLUMN users.email IS '登录/找回邮箱；不区分大小写唯一（ux_users_email_lower）';
COMMENT ON COLUMN users.tokens_valid_after IS '早于该时刻签发的 refresh token 一律失效（改密/管理员重置时置 now()）';

-- 仅对非 NULL 邮箱做唯一：历史用户全为 NULL，不冲突
CREATE UNIQUE INDEX ux_users_email_lower ON users (lower(email)) WHERE email IS NOT NULL;

-- 验证码只存 HMAC-SHA256（不存明文、不可逆），一次性消费、有尝试次数上限
CREATE TABLE email_verification_codes (
    id          BIGINT       PRIMARY KEY,
    email       VARCHAR(254) NOT NULL,
    user_id     BIGINT       NULL,
    purpose     VARCHAR(32)  NOT NULL,
    code_hash   VARCHAR(128) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    attempts    INT          NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ  NULL,
    ip          VARCHAR(64)  NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_evcodes_email_purpose ON email_verification_codes (email, purpose, created_at DESC);
CREATE INDEX idx_evcodes_expires ON email_verification_codes (expires_at);
