-- V32: 过期数据定时清理所需的索引（task-157）。
--
-- 设计原则：
--   * 只新增索引，不改动任何既有表的列/语义；
--   * 全部使用部分索引（WHERE ...），让每日清理只扫「真正会过期的行」，
--     避免给写路径增加维护成本、也避免大表全索引；
--   * 上传会话与审计日志已有可用索引（V7 的 idx_file_upload_sessions_expires、
--     V1 的 idx_admin_op_logs_created、V30 的 ix_admin_login_logs_created），本迁移不重复建。

-- 回收站（文件/目录）：按 trash_expires_at 找到期项；只索引软删且带到期时间的行。
-- trash_expires_at IS NULL 表示「不限期」（档位 trash_retention_days=0），永不清理。
CREATE INDEX IF NOT EXISTS idx_workspace_files_trash_due
    ON workspace_files (trash_expires_at)
    WHERE deleted_at IS NOT NULL AND trash_expires_at IS NOT NULL;

-- 回收站（在线文档）：同上。
CREATE INDEX IF NOT EXISTS idx_documents_trash_due
    ON documents (trash_expires_at)
    WHERE deleted_at IS NOT NULL AND trash_expires_at IS NOT NULL;

-- 分享链接过期标记：只扫「正常且带 expires_at」的行；标记后 status=2，不再命中该索引。
CREATE INDEX IF NOT EXISTS idx_share_links_expires_due
    ON share_links (expires_at)
    WHERE status = 0 AND expires_at IS NOT NULL;

COMMENT ON INDEX idx_workspace_files_trash_due IS 'task-157：回收站到期物理删除扫描（部分索引）';
COMMENT ON INDEX idx_documents_trash_due IS 'task-157：文档回收站到期物理删除扫描（部分索引）';
COMMENT ON INDEX idx_share_links_expires_due IS 'task-157：共享链接到期标记扫描（部分索引）';
