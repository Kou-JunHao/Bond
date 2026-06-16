CREATE DATABASE IF NOT EXISTS bond DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE bond;

CREATE TABLE users (
    id              BIGINT PRIMARY KEY,
    username        VARCHAR(50) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    nickname        VARCHAR(100),
    avatar_url      VARCHAR(500),
    status          TINYINT DEFAULT 1,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
    status              TINYINT DEFAULT 0,
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

CREATE TABLE workspaces (
    id              BIGINT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500),
    owner_id        BIGINT NOT NULL,
    max_size        BIGINT DEFAULT 10737418240,
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
    role            TINYINT DEFAULT 2,
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
