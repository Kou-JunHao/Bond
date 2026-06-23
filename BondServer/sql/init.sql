CREATE DATABASE IF NOT EXISTS bond DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE bond;

-- ═══════════════════════════════════════
--  用户
-- ═══════════════════════════════════════

CREATE TABLE users (
    id              BIGINT PRIMARY KEY,
    username        VARCHAR(50) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    nickname        VARCHAR(100),
    avatar_url      VARCHAR(500),
    status          TINYINT DEFAULT 1,          -- 1=正常 0=禁用
    is_admin        BOOLEAN DEFAULT FALSE,      -- 管理员标识
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ═══════════════════════════════════════
--  设备
-- ═══════════════════════════════════════

CREATE TABLE devices (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    device_name     VARCHAR(100) NOT NULL,
    device_type     VARCHAR(20),
    public_key      TEXT,
    last_seen_at    DATETIME,
    is_online       BOOLEAN DEFAULT FALSE,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
    chunk_size          INT NOT NULL DEFAULT 5242880,
    chunk_count         INT NOT NULL,
    minio_path          VARCHAR(500),
    encrypted_key       TEXT,
    status              TINYINT DEFAULT 0,      -- 0=PENDING 1=UPLOADING 2=COMPLETED 3=FAILED 4=EXPIRED
    uploaded_chunks     INT DEFAULT 0,
    workspace_id        BIGINT,
    expires_at          DATETIME,
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_sender (sender_id),
    INDEX idx_receiver (receiver_id),
    INDEX idx_status (status),
    INDEX idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE transfer_chunks (
    id              BIGINT PRIMARY KEY,
    task_id         BIGINT NOT NULL,
    chunk_index     INT NOT NULL,
    chunk_size      BIGINT NOT NULL,
    etag            VARCHAR(100),
    status          TINYINT DEFAULT 0,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_chunk (task_id, chunk_index),
    FOREIGN KEY (task_id) REFERENCES transfer_tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ═══════════════════════════════════════
--  工作区
-- ═══════════════════════════════════════

CREATE TABLE workspaces (
    id              BIGINT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500),
    owner_id        BIGINT NOT NULL,
    max_size        BIGINT DEFAULT 5368709120,  -- 5GB
    used_size       BIGINT DEFAULT 0,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_owner (owner_id),
    FOREIGN KEY (owner_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workspace_members (
    id              BIGINT PRIMARY KEY,
    workspace_id    BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    role            TINYINT DEFAULT 2,          -- 0=OWNER 1=ADMIN 2=MEMBER
    joined_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ws_user (workspace_id, user_id),
    FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workspace_files (
    id              BIGINT PRIMARY KEY,
    workspace_id    BIGINT NOT NULL,
    file_name       VARCHAR(500) NOT NULL,
    file_size       BIGINT NOT NULL,
    minio_path      VARCHAR(500) NOT NULL,
    content_type    VARCHAR(100),
    uploaded_by     BIGINT NOT NULL,
    parent_path     VARCHAR(500) DEFAULT '/',
    is_directory    BOOLEAN DEFAULT FALSE,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_workspace (workspace_id),
    INDEX idx_parent (workspace_id, parent_path),
    FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
    FOREIGN KEY (uploaded_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ═══════════════════════════════════════
--  好友
-- ═══════════════════════════════════════

CREATE TABLE friendships (
    id              BIGINT PRIMARY KEY,
    requester_id    BIGINT NOT NULL,
    addressee_id    BIGINT NOT NULL,
    status          TINYINT DEFAULT 0,          -- 0=待同意 1=已接受 2=已拒绝 3=已拉黑
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pair (requester_id, addressee_id),
    FOREIGN KEY (requester_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (addressee_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ═══════════════════════════════════════
--  工作区邀请
-- ═══════════════════════════════════════

CREATE TABLE workspace_invitations (
    id              BIGINT PRIMARY KEY,
    workspace_id    BIGINT NOT NULL,
    inviter_id      BIGINT NOT NULL,
    invitee_id      BIGINT,                     -- NULL = generic invite link
    invite_token    VARCHAR(64),
    role            TINYINT DEFAULT 2,
    status          TINYINT DEFAULT 0,          -- 0=待处理 1=已接受 2=已拒绝 3=已过期
    expires_at      DATETIME,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_invitee (invitee_id, status),
    INDEX idx_token (invite_token),
    FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
    FOREIGN KEY (inviter_id) REFERENCES users(id),
    FOREIGN KEY (invitee_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ═══════════════════════════════════════
--  系统配置
-- ═══════════════════════════════════════

CREATE TABLE system_config (
    config_key      VARCHAR(100) PRIMARY KEY,
    config_value    TEXT NOT NULL,
    config_type     VARCHAR(20) NOT NULL DEFAULT 'string',  -- string/int/long/boolean
    description     VARCHAR(500),
    updated_by      BIGINT,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ═══════════════════════════════════════
--  默认配置数据
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
('system.cleanup_cron',             '0 0 3 * * ?',  'string',  '数据清理Cron表达式');
