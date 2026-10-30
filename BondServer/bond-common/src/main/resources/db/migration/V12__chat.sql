-- V12: 轻聊天（MVP）——1:1 会话 + 工作区频道。
-- 注意：V10/V11 已上生产，禁止回改；本文件是新增增量迁移。
-- 约定：时间列统一 TIMESTAMPTZ（实体用 OffsetDateTime）；主键为应用侧雪花（IdType.ASSIGN_ID），不建序列。

CREATE TABLE IF NOT EXISTS chat_conversations (
    id              BIGINT PRIMARY KEY,
    type            VARCHAR(16) NOT NULL CHECK (type IN ('direct', 'workspace')),
    workspace_id    BIGINT REFERENCES workspaces(id) ON DELETE CASCADE,
    direct_key      VARCHAR(64),
    created_by      BIGINT NOT NULL REFERENCES users(id),
    last_message_id BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((type = 'direct'    AND direct_key IS NOT NULL AND workspace_id IS NULL)
        OR (type = 'workspace' AND workspace_id IS NOT NULL AND direct_key IS NULL))
);

-- 1:1 会话按“两个用户 id 排序拼接”唯一；每个工作区只有一个默认频道。
CREATE UNIQUE INDEX IF NOT EXISTS ux_chat_direct_key ON chat_conversations (direct_key) WHERE type = 'direct';
CREATE UNIQUE INDEX IF NOT EXISTS ux_chat_ws_channel  ON chat_conversations (workspace_id) WHERE type = 'workspace';

CREATE TABLE IF NOT EXISTS chat_members (
    conversation_id      BIGINT NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
    user_id              BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    last_read_message_id BIGINT NOT NULL DEFAULT 0,
    muted                BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, user_id)
);
CREATE INDEX IF NOT EXISTS ix_chat_members_user ON chat_members (user_id);

CREATE TABLE IF NOT EXISTS chat_messages (
    id              BIGINT PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
    sender_id       BIGINT NOT NULL REFERENCES users(id),
    kind            VARCHAR(16) NOT NULL CHECK (kind IN ('text', 'file', 'system')),
    body            TEXT,
    file_id         BIGINT REFERENCES workspace_files(id) ON DELETE SET NULL,
    client_msg_id   VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CHECK (kind <> 'text' OR body IS NOT NULL),
    CHECK (kind <> 'file' OR file_id IS NOT NULL)
);

-- 重试幂等：同会话内 clientMsgId 唯一（NULL 不参与）。键集分页用 (conversation_id, id DESC)。
CREATE UNIQUE INDEX IF NOT EXISTS ux_chat_msg_client ON chat_messages (conversation_id, client_msg_id)
    WHERE client_msg_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_chat_msg_keyset ON chat_messages (conversation_id, id DESC);

-- 会话 updated_at 由触发器维护（与 V1 的 set_updated_at() 共用）。
CREATE TRIGGER trg_chat_conversations_set_updated_at
    BEFORE UPDATE ON chat_conversations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
