-- V9: WebDAV 应用专用密码（task-48）
--
-- 背景：/dav 的 Basic 认证原先只能使用账号登录密码。用户一旦把登录密码填进
-- Windows 凭据管理器就无法单独吊销；改登录密码还会让所有挂载下线。
-- 本表存放「应用专用密码」（仅存 bcrypt），支持单独生成 / 吊销 / 记录最后使用时间。
--
-- 语义：
--   * 同一个用户最多 10 个未吊销的专用密码（业务层限制，见 WebDavPasswordService）；
--   * 吊销只置 revoked_at（软吊销，不物理删除，便于审计）；
--   * 删除用户时随 FK 级联删除（ON DELETE CASCADE）。
CREATE TABLE IF NOT EXISTS webdav_passwords (
    id            BIGINT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    name          VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    last_used_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at    TIMESTAMPTZ,
    CONSTRAINT fk_webdav_passwords_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- 认证时只遍历未吊销的密码，部分索引让该查询走索引（同事/后续批次仍可改为复合索引）
CREATE INDEX IF NOT EXISTS ix_webdav_passwords_user
    ON webdav_passwords (user_id) WHERE revoked_at IS NULL;
