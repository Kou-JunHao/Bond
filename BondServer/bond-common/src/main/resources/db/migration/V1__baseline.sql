-- ============================================================================
-- Bond Server - PostgreSQL baseline schema (Flyway V1)
-- Target: PostgreSQL 16/17
--
-- 从 MySQL 8 (sql/init.sql) 迁移，逐表转换：
--   * ON UPDATE CURRENT_TIMESTAMP  -> set_updated_at() 触发器函数
--   * TINYINT                      -> SMALLINT（保持实体 Integer 映射）
--   * BOOLEAN                      -> BOOLEAN（PG 原生）
--   * DATETIME                     -> TIMESTAMP（对应 java.time.LocalDateTime）
--   * ENGINE / CHARSET             -> 删除（库级 ENCODING 'UTF8'）
--   * 补齐 schema 漂移列：transfer_tasks.transfer_type、workspace_files.file_hash
--
-- 约定：标识符不加引号（PG 会折叠为小写）；BIGINT 主键由应用侧雪花算法生成，
--       因此不使用 GENERATED/IDENTITY，也不使用序列。
-- 建表顺序：被引用表必须先建（transfer_tasks 引用 workspaces，故 workspaces 提前）。
-- ============================================================================

-- MySQL 的 ON UPDATE CURRENT_TIMESTAMP 在 PG 没有等价语法，统一用触发器实现
-- （后台管理/手工 UPDATE 也能正确刷新 updated_at，不依赖 MyBatis-Plus fill）。
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

-- ═══════════════════════════════════════
--  用户
-- ═══════════════════════════════════════
CREATE TABLE users (
    id              BIGINT PRIMARY KEY,
    username        VARCHAR(50) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    nickname        VARCHAR(100),
    avatar_url      VARCHAR(500),
    status          SMALLINT DEFAULT 1,          -- 1=正常 0=禁用
    is_admin        BOOLEAN DEFAULT FALSE,       -- 管理员标识
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_users_username UNIQUE (username)
);

-- 大小写不敏感唯一：防止 Alice / alice 重复注册。
-- 未使用 CITEXT 是为了避免对 PG 扩展权限的依赖（生产库可能无 CREATE EXTENSION 权限）。
CREATE UNIQUE INDEX ux_users_username_lower ON users (lower(username));

CREATE TRIGGER trg_users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ═══════════════════════════════════════
--  设备
-- ═══════════════════════════════════════
CREATE TABLE devices (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    device_name     VARCHAR(100) NOT NULL,
    device_type     VARCHAR(20),
    public_key      TEXT,
    last_seen_at    TIMESTAMP,
    is_online       BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_devices_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX idx_devices_user ON devices (user_id);

-- ═══════════════════════════════════════
--  工作区（必须在 transfer_tasks 之前建表：transfer_tasks.workspace_id 引用它）
-- ═══════════════════════════════════════
CREATE TABLE workspaces (
    id              BIGINT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500),
    owner_id        BIGINT NOT NULL,
    max_size        BIGINT DEFAULT 5368709120,  -- 5GB
    used_size       BIGINT DEFAULT 0,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_workspaces_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX idx_workspaces_owner ON workspaces (owner_id);

CREATE TRIGGER trg_workspaces_set_updated_at
    BEFORE UPDATE ON workspaces
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE workspace_members (
    id              BIGINT PRIMARY KEY,
    workspace_id    BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    role            SMALLINT DEFAULT 2,          -- 0=OWNER 1=ADMIN 2=MEMBER
    joined_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_workspace_members_ws_user UNIQUE (workspace_id, user_id),
    CONSTRAINT fk_workspace_members_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_members_user      FOREIGN KEY (user_id)      REFERENCES users (id)      ON DELETE CASCADE
);

CREATE TABLE workspace_files (
    id              BIGINT PRIMARY KEY,
    workspace_id    BIGINT NOT NULL,
    file_name       VARCHAR(500) NOT NULL,
    file_size       BIGINT NOT NULL,
    minio_path      VARCHAR(500) NOT NULL,
    content_type    VARCHAR(100),
    uploaded_by     BIGINT,                       -- 允许为空：用户注销后保留其上传的工作区文件
    parent_path     VARCHAR(500) DEFAULT '/',
    is_directory    BOOLEAN DEFAULT FALSE,
    file_hash       VARCHAR(64),                  -- V1 补齐：实体 WorkspaceFile.fileHash（秒传/去重）
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_workspace_files_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_files_uploader  FOREIGN KEY (uploaded_by)  REFERENCES users (id)      ON DELETE SET NULL
);

-- 同一目录下不允许重名。用部分唯一索引而不是整列唯一约束：
-- 目录行与文件行可以同名（is_directory 区分），文件之间/目录之间各自唯一。
-- 注意：并发上传同名文件时由 DB 保证唯一，业务层 resolveFileName 的重命名逻辑
--       在并发竞争下可能收到唯一键冲突（如出现请重试，后续可改为 INSERT ... ON CONFLICT）。
CREATE UNIQUE INDEX ux_workspace_files_file_name
    ON workspace_files (workspace_id, parent_path, file_name) WHERE is_directory = FALSE;
CREATE UNIQUE INDEX ux_workspace_files_dir_name
    ON workspace_files (workspace_id, parent_path, file_name) WHERE is_directory = TRUE;

CREATE INDEX idx_workspace_files_workspace ON workspace_files (workspace_id);
CREATE INDEX idx_workspace_files_parent    ON workspace_files (workspace_id, parent_path);
CREATE INDEX idx_workspace_files_hash      ON workspace_files (workspace_id, file_hash);  -- V1 补齐列配套索引

-- ═══════════════════════════════════════
--  传输
-- ═══════════════════════════════════════
CREATE TABLE transfer_tasks (
    id                  BIGINT PRIMARY KEY,
    sender_id           BIGINT NOT NULL,
    receiver_id         BIGINT NOT NULL,
    sender_device_id    BIGINT,
    receiver_device_id  BIGINT,
    file_name           VARCHAR(500) NOT NULL,
    file_size           BIGINT NOT NULL,
    chunk_size          INTEGER NOT NULL DEFAULT 5242880,
    chunk_count         INTEGER NOT NULL,
    minio_path          VARCHAR(500),
    encrypted_key       TEXT,
    status              SMALLINT DEFAULT 0,      -- 0=PENDING 1=UPLOADING 2=COMPLETED 3=FAILED 4=EXPIRED
    uploaded_chunks     INTEGER DEFAULT 0,
    workspace_id        BIGINT,
    transfer_type       VARCHAR(20) NOT NULL DEFAULT 'inbox',  -- V1 补齐：实体 TransferTask.transferType
    expires_at          TIMESTAMP,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_transfer_tasks_sender   FOREIGN KEY (sender_id)   REFERENCES users (id)      ON DELETE CASCADE,
    CONSTRAINT fk_transfer_tasks_receiver FOREIGN KEY (receiver_id) REFERENCES users (id)      ON DELETE CASCADE,
    CONSTRAINT fk_transfer_tasks_sender_device   FOREIGN KEY (sender_device_id)   REFERENCES devices (id)   ON DELETE SET NULL,
    CONSTRAINT fk_transfer_tasks_receiver_device FOREIGN KEY (receiver_device_id) REFERENCES devices (id)   ON DELETE SET NULL,
    CONSTRAINT fk_transfer_tasks_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE SET NULL
);

-- 管理端/列表查询：按发送者/接收者 + 时间倒序
CREATE INDEX idx_transfer_tasks_sender_created   ON transfer_tasks (sender_id, created_at);
CREATE INDEX idx_transfer_tasks_receiver_created ON transfer_tasks (receiver_id, created_at);
CREATE INDEX idx_transfer_tasks_status           ON transfer_tasks (status);
CREATE INDEX idx_transfer_tasks_expires          ON transfer_tasks (expires_at);
CREATE INDEX idx_transfer_tasks_workspace        ON transfer_tasks (workspace_id);
CREATE INDEX idx_transfer_tasks_type             ON transfer_tasks (transfer_type);  -- V1 补齐列配套索引

CREATE TRIGGER trg_transfer_tasks_set_updated_at
    BEFORE UPDATE ON transfer_tasks
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 一行一片的设计保留（大规模化重构留到计费任务之后）。
-- 批量插入建议：uploadChunk 完成后用单条 INSERT ... VALUES (...),(...) 或 JDBC batch，
-- 不要逐片 insert；task_id + chunk_index 唯一约束保证幂等。
CREATE TABLE transfer_chunks (
    id              BIGINT PRIMARY KEY,
    task_id         BIGINT NOT NULL,
    chunk_index     INTEGER NOT NULL,
    chunk_size      BIGINT NOT NULL,
    etag            VARCHAR(100),
    status          SMALLINT DEFAULT 0,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_transfer_chunks_task_chunk UNIQUE (task_id, chunk_index),
    CONSTRAINT fk_transfer_chunks_task FOREIGN KEY (task_id) REFERENCES transfer_tasks (id) ON DELETE CASCADE
);

-- ═══════════════════════════════════════
--  好友
-- ═══════════════════════════════════════
CREATE TABLE friendships (
    id              BIGINT PRIMARY KEY,
    requester_id    BIGINT NOT NULL,
    addressee_id    BIGINT NOT NULL,
    status          SMALLINT DEFAULT 0,          -- 0=待同意 1=已接受 2=已拒绝 3=已拉黑
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_friendships_pair UNIQUE (requester_id, addressee_id),
    CONSTRAINT fk_friendships_requester FOREIGN KEY (requester_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_friendships_addressee FOREIGN KEY (addressee_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TRIGGER trg_friendships_set_updated_at
    BEFORE UPDATE ON friendships
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ═══════════════════════════════════════
--  工作区邀请
-- ═══════════════════════════════════════
CREATE TABLE workspace_invitations (
    id              BIGINT PRIMARY KEY,
    workspace_id    BIGINT NOT NULL,
    inviter_id      BIGINT NOT NULL,
    invitee_id      BIGINT,                       -- NULL = 通用邀请链接
    invite_token    VARCHAR(64),
    role            SMALLINT DEFAULT 2,
    status          SMALLINT DEFAULT 0,           -- 0=待处理 1=已接受 2=已拒绝 3=已过期
    expires_at      TIMESTAMP,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_workspace_invitations_token UNIQUE (invite_token),  -- PG 唯一约束允许多个 NULL
    CONSTRAINT fk_workspace_invitations_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_invitations_inviter   FOREIGN KEY (inviter_id)   REFERENCES users (id)      ON DELETE CASCADE,
    CONSTRAINT fk_workspace_invitations_invitee   FOREIGN KEY (invitee_id)   REFERENCES users (id)      ON DELETE CASCADE
);
CREATE INDEX idx_workspace_invitations_invitee ON workspace_invitations (invitee_id, status);

-- ═══════════════════════════════════════
--  系统配置
-- ═══════════════════════════════════════
CREATE TABLE system_config (
    config_key      VARCHAR(100) PRIMARY KEY,
    config_value    TEXT NOT NULL,
    config_type     VARCHAR(20) NOT NULL DEFAULT 'string',  -- string/int/long/boolean
    description     VARCHAR(500),
    updated_by      BIGINT,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_system_config_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL
);

CREATE TRIGGER trg_system_config_set_updated_at
    BEFORE UPDATE ON system_config
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ═══════════════════════════════════════
--  默认配置数据（与 MySQL init.sql 保持一致，ON CONFLICT 保证可重复执行）
-- ═══════════════════════════════════════
INSERT INTO system_config (config_key, config_value, config_type, description) VALUES
-- 传输限制
('transfer.max_file_size',          '5368709120',   'long',    '单文件最大体积(字节), 默认5GB'),
('transfer.chunk_size',             '5242880',      'int',     '默认分片大小(字节), 默认5MB'),
('transfer.large_file_threshold',   '1073741824',   'long',    '大文件分片阈值(字节), 默认1GB'),
('transfer.large_chunk_size',       '10485760',     'int',     '大文件分片大小(字节), 默认10MB'),
('transfer.upload_concurrency',     '4',            'int',     '分片上传并发数'),
('transfer.task_expire_days',       '7',            'int',     '任务过期天数'),
('transfer.free_upload_limit',      '2097152',      'long',    '免费用户上行限速(字节/秒), 默认2MB/s'),
('transfer.free_download_limit',    '5242880',      'long',    '免费用户下行限速(字节/秒), 默认5MB/s'),
('transfer.paid_upload_limit',      '10485760',     'long',    '付费用户上行限速(字节/秒), 默认10MB/s'),
('transfer.paid_download_limit',    '20971520',     'long',    '付费用户下行限速(字节/秒), 默认20MB/s'),
('transfer.require_friend',         'true',         'boolean', '跨网传输是否需要好友关系'),
-- 工作区限制
('workspace.max_size',              '5368709120',   'long',    '单工作区最大空间(字节), 默认5GB'),
('workspace.max_create',            '3',            'int',     '每用户可创建工作区数'),
('workspace.max_join',              '10',           'int',     '每用户可加入工作区数(含自己创建的)'),
('workspace.max_members',           '20',           'int',     '单工作区最大成员数'),
('workspace.max_file_size',         '2147483648',   'long',    '工作区单文件最大体积(字节), 默认2GB'),
('workspace.file_retention_days',   '90',           'int',     '文件保留天数(无访问自动清理)'),
('workspace.invite_expire_days',    '7',            'int',     '邀请链接有效期(天)'),
-- 好友系统
('user.allow_register',             'true',         'boolean', '是否允许新用户注册'),
('friend.request_expire_days',      '7',            'int',     '好友请求有效期(天)'),
('friend.max_friends',              '500',          'int',     '最大好友数'),
-- NAT/Relay
('nat.enabled',                     'true',         'boolean', '是否启用NAT穿透'),
('nat.stun_server',                 '',             'string',  'STUN服务器地址(IP:Port)'),
('nat.punch_timeout_ms',            '5000',         'int',     'NAT打洞超时(毫秒)'),
('relay.enabled',                   'true',         'boolean', '是否启用Relay中继'),
('relay.chunk_size',                '65536',        'int',     'Relay子块大小(字节), 默认64KB'),
('relay.timeout_seconds',           '30',           'int',     'Relay超时(秒)'),
('relay.fallback_to_minio',         'true',         'boolean', 'Relay降级到MinIO'),
-- 系统
('system.name',                     'Bond',         'string',  '系统名称'),
('system.public_url',               '',             'string',  '公网地址'),
('system.jwt_access_expire_hours',  '24',           'int',     'JWT Access Token有效期(小时)'),
('system.jwt_refresh_expire_days',  '7',            'int',     'JWT Refresh Token有效期(天)'),
('system.cleanup_cron',             '0 0 3 * * ?',  'string',  '数据清理Cron表达式')
ON CONFLICT (config_key) DO NOTHING;
