-- V7: 网盘大文件分片/断点续传上传会话（task-40）
-- 复用对象存储的 S3 Multipart Upload：会话只保存协议状态与业务元数据，
-- 真正的 parts 在 S3 侧，complete 成功后才会出现 workspace_files 行。
--
-- 时间字段统一 TIMESTAMPTZ（PATCH/分享链接两次事故的教训），Java 侧对应 OffsetDateTime。
-- 生产基线为 v6，本迁移只新增表与索引，不改动已有表结构。

CREATE TABLE file_upload_sessions (
    id                BIGINT        PRIMARY KEY,
    user_id           BIGINT        NOT NULL,
    workspace_id      BIGINT        NOT NULL,
    parent_path       VARCHAR(1024) NOT NULL,
    file_name         VARCHAR(512)  NOT NULL,
    content_type      VARCHAR(255),
    file_size         BIGINT        NOT NULL,
    chunk_size        INTEGER       NOT NULL,
    total_chunks      INTEGER       NOT NULL,
    storage_upload_id VARCHAR(255),
    storage_key       VARCHAR(1024),
    etags             JSONB         NOT NULL DEFAULT '[]'::jsonb,
    received_bytes    BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ   NOT NULL
);

-- 续传列表/清理扫描
CREATE INDEX IF NOT EXISTS idx_file_upload_sessions_user
    ON file_upload_sessions (user_id, workspace_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_file_upload_sessions_expires
    ON file_upload_sessions (expires_at);

-- 同名未完成会话复用：按 (workspace, parent_path, file_name) 命中
CREATE INDEX IF NOT EXISTS idx_file_upload_sessions_reuse
    ON file_upload_sessions (workspace_id, parent_path, file_name);

-- updated_at 自动维护（复用 V1 的 set_updated_at()）
DROP TRIGGER IF EXISTS trg_file_upload_sessions_set_updated_at ON file_upload_sessions;
CREATE TRIGGER trg_file_upload_sessions_set_updated_at
    BEFORE UPDATE ON file_upload_sessions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
