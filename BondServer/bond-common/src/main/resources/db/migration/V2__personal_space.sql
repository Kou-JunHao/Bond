-- V2: 个人空间（personal workspace）
--
-- 设计：个人空间不新建文件表，而是 workspaces 表中 type='personal' 的一行，
--       复用 workspace_files / 配额 / MinIO 存储逻辑。
-- 已有工作区自然成为 type='team'（补列默认值）。
-- 存量用户的个人空间由应用启动时的 ApplicationRunner 用 SnowflakeId.nextId() 幂等回填，
-- 不在 SQL 里硬编码 id（避免与雪花 ID 空间冲突）。

ALTER TABLE workspaces ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'team';

-- 保证“每个用户恰好一个个人空间”；团队工作区（type='team'）不受此约束。
CREATE UNIQUE INDEX ux_workspaces_personal_owner ON workspaces (owner_id) WHERE type = 'personal';
