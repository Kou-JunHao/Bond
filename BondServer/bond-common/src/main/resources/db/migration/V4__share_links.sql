-- V4：分享链接（匿名下载入口）。
--
-- 设计要点（Lead 定稿）：
--   * 匿名下载不需要登录；token = 32 字节 SecureRandom（base64url，43 字符），不可枚举；
--   * 提取码只存 HMAC 摘要（永不落明文），连续失败 5 次锁 10 分钟（可在 system_configs 调整）；
--   * 永久链接（expires_at IS NULL）仅付费档可用，且必须带 max_downloads 上限；
--   * download_count / max_downloads 只用于「次数限制/审计」，**不是计费依据**；
--     计费口径是 usage_counters.relay_bytes_down（按实际下行字节，ON CONFLICT 原子累加）；
--   * 文件/工作区/用户删除时链接级联删除（ON DELETE CASCADE），避免悬空指向。
CREATE TABLE share_links (
    id                BIGINT PRIMARY KEY,
    token             VARCHAR(64) NOT NULL,
    file_id           BIGINT NOT NULL REFERENCES workspace_files(id) ON DELETE CASCADE,
    workspace_id      BIGINT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    creator_id        BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    requires_code     BOOLEAN NOT NULL DEFAULT FALSE,
    code_hash         VARCHAR(64),                   -- hex(HMAC-SHA256(pepper, token:code))，仅摘要
    failed_attempts   INTEGER NOT NULL DEFAULT 0,    -- 连续提取码错误次数（成功后清零）
    code_locked_until TIMESTAMPTZ,                   -- 提取码锁定截止时间
    expires_at        TIMESTAMPTZ,                   -- NULL = 永久（仅付费档）
    max_downloads     INTEGER NOT NULL DEFAULT 0,    -- 0 = 不限；永久链接必须 > 0
    download_count    INTEGER NOT NULL DEFAULT 0,    -- 完整下载开始次数（Range 续传不重复计）
    status            SMALLINT NOT NULL DEFAULT 0,   -- 0 正常 / 1 已撤销
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_share_links_token ON share_links (token);
CREATE INDEX idx_share_links_creator ON share_links (creator_id, status, created_at DESC);
CREATE INDEX idx_share_links_file ON share_links (file_id);

CREATE TRIGGER trg_share_links_set_updated_at
    BEFORE UPDATE ON share_links
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE share_links IS '文件分享链接（匿名下载入口；token 不可枚举）';
COMMENT ON COLUMN share_links.max_downloads IS '下载次数上限（0=不限）。仅用于次数限制/审计，不参与计费';
COMMENT ON COLUMN share_links.download_count IS '完整下载开始次数（Range 续传不重复计）。审计口径，非计费口径';

-- 档位默认值（管理端可改 plans.limits）：
--   share_max_links     单用户活跃链接数上限
--   share_max_downloads 永久链接默认下载次数上限（创建时可在该值以内覆盖）
UPDATE plans SET limits = limits || '{"share_max_links": 5, "share_max_downloads": 30}'::jsonb
 WHERE code = 'free';
UPDATE plans SET limits = limits || '{"share_max_links": 20, "share_max_downloads": 200}'::jsonb
 WHERE code = 'plus';
UPDATE plans SET limits = limits || '{"share_max_links": 50, "share_max_downloads": 1000}'::jsonb
 WHERE code = 'team';
