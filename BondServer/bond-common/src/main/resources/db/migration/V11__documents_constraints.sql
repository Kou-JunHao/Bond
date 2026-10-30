-- V11: 文档表外键与索引补齐（task-50 评审项增量）
--
-- 背景：V10__documents.sql 已于 2026-10-23 随 task-50 发布进入生产
--       （flyway_schema_history: version=10 / checksum=708854379）。
--       已上生产的迁移文件不得再修改，否则下次启动 Flyway 校验失败、
--       bond-transfer 起不来。所以 V10 保持发布时原样，评审要求的外键与
--       索引调整改由本迁移增量补齐（最终 schema 与"直接改 V10"等价）。
--
-- 口径与 V1__baseline.sql 一致：引用 users 的列一律显式外键；
-- 文档/版本归其所有者，owner 随用户删除级联；编辑者删除只置 NULL。

-- 1) documents 的两个用户引用
ALTER TABLE documents
    ADD CONSTRAINT fk_documents_owner
        FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_documents_last_editor
        FOREIGN KEY (last_editor_id) REFERENCES users (id) ON DELETE SET NULL;

-- 2) document_versions.editor_id：要支持 ON DELETE SET NULL 必须先放开 NOT NULL
ALTER TABLE document_versions ALTER COLUMN editor_id DROP NOT NULL;
ALTER TABLE document_versions
    ADD CONSTRAINT fk_document_versions_editor
        FOREIGN KEY (editor_id) REFERENCES users (id) ON DELETE SET NULL;

-- 3) 路径索引改部分索引（回收站行不占索引，与 V6 的文件表口径一致）
DROP INDEX IF EXISTS idx_documents_path;
CREATE INDEX idx_documents_path
    ON documents (workspace_id, parent_path, title) WHERE deleted_at IS NULL;

-- 4) 回收站列表按「工作区 + 已删」查，补对应部分索引
CREATE INDEX idx_documents_workspace_trash
    ON documents (workspace_id, deleted_at DESC) WHERE deleted_at IS NOT NULL;

-- 5) idx_document_versions_doc 与 uk_document_versions(document_id, version) 重复：
--    btree 可反向扫描覆盖 (document_id, version DESC)，去掉省一份写放大。
DROP INDEX IF EXISTS idx_document_versions_doc;
