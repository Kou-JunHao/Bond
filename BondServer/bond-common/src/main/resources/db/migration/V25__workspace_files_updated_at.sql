-- V25：workspace_files 增加 updated_at（task-117 文件搜索的排序与 keyset 分页依赖）
--
-- 背景：workspace_files 自 V1 起只有 created_at，没有 updated_at；而文件搜索要求
--   「名称前缀命中优先 → updated_at DESC」且 cursor 用 (updated_at, id) 复合键，不能用 offset。
-- 处理：
--   1) 加列并回填 created_at（历史行在索引重建前先有值）；
--   2) 后续由 V1 的 set_updated_at() 触发器维护（改名/移动/软删/恢复都会刷新）；
--   3) 新增搜索用的部分索引（只索引未删除行），避免全表扫描。
ALTER TABLE workspace_files ADD COLUMN updated_at TIMESTAMPTZ;
UPDATE workspace_files SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE workspace_files ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE workspace_files ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_workspace_files_search
    ON workspace_files (workspace_id, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;

DROP TRIGGER IF EXISTS trg_workspace_files_updated_at ON workspace_files;
CREATE TRIGGER trg_workspace_files_updated_at
    BEFORE UPDATE ON workspace_files
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
