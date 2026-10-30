-- V10: 在线文档 MVP（task-50）
-- 设计：BondServer/docs/docs-feature-design.md
-- 说明：正文权威存储为 content_md（Markdown）；content_json 预留块模型（MVP 恒 NULL）。
--       版本历史 document_versions 由应用写入，版本号与 documents.version 一致、只增不复用。

CREATE TABLE documents (
    id               BIGINT        PRIMARY KEY,
    workspace_id     BIGINT        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    owner_id         BIGINT        NOT NULL,
    title            VARCHAR(512)  NOT NULL,
    content_md       TEXT          NOT NULL DEFAULT '',
    content_json     JSONB,
    content_type     VARCHAR(32)   NOT NULL DEFAULT 'markdown',
    version          BIGINT        NOT NULL DEFAULT 1,
    size_bytes       BIGINT        NOT NULL DEFAULT 0,
    parent_path      VARCHAR(1024) NOT NULL DEFAULT '/',
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,
    trash_expires_at TIMESTAMPTZ,
    last_editor_id   BIGINT
);

COMMENT ON TABLE documents IS '在线文档（MVP：Markdown 正文，非实时协作）';
COMMENT ON COLUMN documents.content_json IS '预留块模型 JSON，MVP 恒 NULL';
COMMENT ON COLUMN documents.size_bytes IS 'content_md 的 UTF-8 字节数（配额/正文上限校验用）';

CREATE INDEX idx_documents_workspace ON documents (workspace_id, updated_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_documents_path ON documents (workspace_id, parent_path, title);
CREATE INDEX idx_documents_trash_expires ON documents (trash_expires_at) WHERE deleted_at IS NOT NULL;

CREATE TABLE document_versions (
    id          BIGINT      PRIMARY KEY,
    document_id BIGINT      NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    version     BIGINT      NOT NULL,
    content_md  TEXT        NOT NULL,
    size_bytes  BIGINT      NOT NULL,
    editor_id   BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_document_versions UNIQUE (document_id, version)
);

CREATE INDEX idx_document_versions_doc ON document_versions (document_id, version DESC);
