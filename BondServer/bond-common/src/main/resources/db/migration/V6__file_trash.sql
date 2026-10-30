-- ═══════════════════════════════════════
-- V6: 文件回收站（workspace_files 原地软删）
-- ═══════════════════════════════════════
-- 语义：删除 → 只打软删标记（deleted_at），物理对象保留到「彻底删除 / 过期清理」。
-- 个人空间与工作区共用 workspace_files，无需区分。

ALTER TABLE workspace_files
    ADD COLUMN deleted_at       TIMESTAMPTZ NULL,
    ADD COLUMN deleted_by       BIGINT NULL,
    ADD COLUMN trash_expires_at TIMESTAMPTZ NULL;

-- 目录递归软删后，同一路径下必须还能重新上传同名文件：
-- V1 的两个部分唯一索引补上 `AND deleted_at IS NULL`（软删行不参与唯一性）。
DROP INDEX IF EXISTS ux_workspace_files_file_name;
DROP INDEX IF EXISTS ux_workspace_files_dir_name;

CREATE UNIQUE INDEX ux_workspace_files_file_name
    ON workspace_files (workspace_id, parent_path, file_name)
    WHERE is_directory = FALSE AND deleted_at IS NULL;
CREATE UNIQUE INDEX ux_workspace_files_dir_name
    ON workspace_files (workspace_id, parent_path, file_name)
    WHERE is_directory = TRUE AND deleted_at IS NULL;

-- 回收站列表按 (workspace_id, deleted_at) 过滤；过期清理按 trash_expires_at 扫描。
CREATE INDEX idx_workspace_files_trash ON workspace_files (workspace_id, deleted_at)
    WHERE deleted_at IS NOT NULL;
CREATE INDEX idx_workspace_files_trash_expires ON workspace_files (trash_expires_at)
    WHERE deleted_at IS NOT NULL;

-- 档位补回收站保留天数：0 表示不限期（仅付费档可配）。
UPDATE plans SET limits = limits || '{"trash_retention_days":30}'::jsonb WHERE code = 'free';
UPDATE plans SET limits = limits || '{"trash_retention_days":60}'::jsonb WHERE code = 'plus';
UPDATE plans SET limits = limits || '{"trash_retention_days":90}'::jsonb WHERE code = 'team';
