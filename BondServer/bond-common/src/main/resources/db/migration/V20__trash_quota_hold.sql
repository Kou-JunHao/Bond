-- V20：回收站占配额（计费正确性，task-88 方案 B）。
--
-- 背景：V6 起「删除 = 软删」时立即释放 workspaces.used_size，但对象仍在 RustFS 里保留到
-- 过期清理；这等于让用户白嫖 30 天保留期（可用存量最多到 2× 档位且不付钱）。
--
-- 本迁移做两件事：
--   1) 一次性把「现存软删行」占用的空间补回 used_size（升级前它们已被释放，属于历史漏计）。
--   2) 不改表结构：保留期仍由 workspace_files.trash_expires_at 决定，索引沿用 V6 的
--      idx_workspace_files_trash_expires（部分索引，deleted_at IS NOT NULL）。
--
-- ⚠️ 一次性回填：仅允许执行一次（Flyway 保证）。禁止在后续迁移/脚本中重复执行，
--    否则 used_size 会被重复累加。回填后 used_size 可能 > max_size（例如用户降档后），
--    这是允许的：仅影响新上传/新占空间的上限校验，不因此拒服务。
UPDATE workspaces w
SET used_size = used_size + COALESCE((
    SELECT SUM(f.file_size)
    FROM workspace_files f
    WHERE f.workspace_id = w.id
      AND f.deleted_at IS NOT NULL
      AND f.is_directory = FALSE
), 0);
