-- V3: 权益层（档位 / 订阅 / 计量 / 用户级覆盖 / 订单 / 兑换码 / 操作日志）
--
-- 口径（见 BondClient/docs/BILLING-ENTITLEMENTS.md，Lead 已更新两处）：
--   * 只计量下行（下载）；上传不计流量、不限速；纯 LAN 用户已用流量恒为 0。
--   * 分享链接下载计到「链接创建者」（分享功能本期未做，表/接口预留）。
--   * 个人空间配额 = 用户档位额度；团队工作区配额 = owner 档位额度。
--
-- 注意：设计文档 §六 写的是 "Flyway V2"，但 V2 已被个人空间（task-11）占用，故本迁移为 V3。
-- 依赖 PG 能力：JSONB（档位权益）、btree_gist + EXCLUDE（防重复订阅）、ON CONFLICT（原子计量）。

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ── 档位（静态配置，可热改） ──
CREATE TABLE plans (
    code            VARCHAR(32) PRIMARY KEY,        -- free / plus / team
    name            VARCHAR(64) NOT NULL,
    price_cents     INTEGER NOT NULL DEFAULT 0,
    period          VARCHAR(16) NOT NULL DEFAULT 'month',
    limits          JSONB NOT NULL,                 -- 权益键值，见设计文档 §二
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO plans (code, name, price_cents, period, limits) VALUES
('free', 'Free', 0, 'month', '{
   "relay_bytes_month": 10737418240,
   "storage_bytes": 5368709120,
   "max_file_size": 2147483648,
   "concurrent_transfers": 2,
   "max_workspaces": 1,
   "max_workspace_members": 3,
   "relay_down_bytes_per_sec": 5242880,
   "share_expires_days": 7,
   "grace_days": 3
 }'),
('plus', 'Plus', 3000, 'month', '{
   "relay_bytes_month": 214748364800,
   "storage_bytes": 214748364800,
   "max_file_size": 10737418240,
   "concurrent_transfers": 4,
   "max_workspaces": 5,
   "max_workspace_members": 10,
   "relay_down_bytes_per_sec": 20971520,
   "share_expires_days": 30,
   "grace_days": 3
 }'),
('team', 'Team', 9800, 'month', '{
   "relay_bytes_month": 644245094400,
   "storage_bytes": 1099511627776,
   "max_file_size": 21474836480,
   "concurrent_transfers": 8,
   "max_workspaces": 20,
   "max_workspace_members": 50,
   "relay_down_bytes_per_sec": 0,
   "share_expires_days": 0,
   "grace_days": 3
 }')
ON CONFLICT (code) DO NOTHING;

-- ── 用户档位（users 上冗余一份，便于鉴权/展示；subscriptions 记录周期） ──
ALTER TABLE users ADD COLUMN plan_code VARCHAR(32) NOT NULL DEFAULT 'free';
ALTER TABLE users ADD COLUMN plan_expires_at TIMESTAMPTZ;
ALTER TABLE users ADD CONSTRAINT fk_users_plan FOREIGN KEY (plan_code) REFERENCES plans(code);

-- ── 订阅：同一用户时间区间不允许重叠（PG EXCLUDE，从根上杜绝重复计费周期） ──
CREATE TABLE subscriptions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_code       VARCHAR(32) NOT NULL REFERENCES plans(code),
    status          VARCHAR(16) NOT NULL,           -- active/past_due/grace/canceled/expired
    period_start    TIMESTAMPTZ NOT NULL,
    period_end      TIMESTAMPTZ NOT NULL,
    source          VARCHAR(16) NOT NULL DEFAULT 'manual',  -- manual/redeem/payment
    external_id     VARCHAR(128),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT no_overlap EXCLUDE USING gist (
        user_id WITH =,
        tstzrange(period_start, period_end) WITH &&
    )
);
CREATE INDEX idx_subscriptions_user ON subscriptions (user_id, period_end DESC);

-- ── 计量：只累加下行；按 (user_id, period) 原子 upsert ──
CREATE TABLE usage_counters (
    user_id          BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    period           CHAR(7) NOT NULL,               -- 'YYYY-MM'
    relay_bytes_up   BIGINT NOT NULL DEFAULT 0,      -- 预留观测字段，上传不计费，保持 0
    relay_bytes_down BIGINT NOT NULL DEFAULT 0,      -- 唯一计费项
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, period)
);
CREATE INDEX idx_usage_counters_period ON usage_counters (period);

-- ── 用户级权益覆盖（运营加量/开白，优先于档位；expires_at 为空表示永久） ──
CREATE TABLE user_entitlements (
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key             VARCHAR(64) NOT NULL,           -- 与 plans.limits 的键同名
    value           BIGINT NOT NULL,
    expires_at      TIMESTAMPTZ,
    note            VARCHAR(255),
    PRIMARY KEY (user_id, key)
);

-- ── 订单（先建表不接支付；兑换码也走这里，provider_txn_id 唯一保证幂等） ──
CREATE TABLE orders (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_code       VARCHAR(32) NOT NULL REFERENCES plans(code),
    amount_cents    INTEGER NOT NULL DEFAULT 0,
    provider        VARCHAR(16) NOT NULL DEFAULT 'redeem',  -- redeem/stripe/alipay/wechat
    provider_txn_id VARCHAR(128) UNIQUE,
    status          VARCHAR(16) NOT NULL DEFAULT 'pending',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_user ON orders (user_id, created_at DESC);

-- ── 兑换码（首发形态；orders.user_id NOT NULL 不能存未兑换的码，故单独建表，
--    兑换成功时再写入 orders(provider='redeem', provider_txn_id=code) 形成审计链） ──
CREATE TABLE redeem_codes (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(64) NOT NULL UNIQUE,
    plan_code       VARCHAR(32) NOT NULL REFERENCES plans(code),
    duration_days   INTEGER NOT NULL,
    expires_at      TIMESTAMPTZ,                    -- 码本身的失效时间（NULL=永久可兑）
    status          VARCHAR(16) NOT NULL DEFAULT 'pending',  -- pending/used/disabled
    used_by         BIGINT REFERENCES users(id) ON DELETE SET NULL,
    used_at         TIMESTAMPTZ,
    created_by      BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_redeem_codes_status ON redeem_codes (status, expires_at);

-- ── 管理端操作日志（改档位/加量/发码等） ──
CREATE TABLE admin_operation_logs (
    id              BIGSERIAL PRIMARY KEY,
    admin_user_id   BIGINT NOT NULL,
    action          VARCHAR(64) NOT NULL,
    target_user_id  BIGINT,
    detail          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_op_logs_created ON admin_operation_logs (created_at DESC);
