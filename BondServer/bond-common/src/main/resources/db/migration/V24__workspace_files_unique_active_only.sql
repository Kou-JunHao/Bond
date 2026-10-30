-- V24：workspace_files 的唯一索引只约束「未删除行」
--
-- 背景（task-118）：
--   V1 创建的两个部分唯一索引只排除了 is_directory，不排除软删行；
--   V6 引入 deleted_at 软删后，回收站里的同名文件/目录仍然占着
--   (workspace_id, parent_path, file_name) 的唯一键，用户清理回收站前无法重建同名条目，
--   与「生产建目录接口」的产品语义冲突（同名仅在回收站里时应允许创建）。
--
-- 改法：
--   drop 旧索引，按「is_directory + deleted_at IS NULL」重建。
--   回收站恢复流程（TrashService.uniqueRestoredName / activeNameExists）已自带重命名兜底，
--   因此放宽索引不会产生同名未删除行。

DROP INDEX IF EXISTS ux_workspace_files_file_name;
CREATE UNIQUE INDEX ux_workspace_files_file_name
    ON workspace_files (workspace_id, parent_path, file_name)
    WHERE is_directory = FALSE AND deleted_at IS NULL;

DROP INDEX IF EXISTS ux_workspace_files_dir_name;
CREATE UNIQUE INDEX ux_workspace_files_dir_name
    ON workspace_files (workspace_id, parent_path, file_name)
    WHERE is_directory = TRUE AND deleted_at IS NULL;
