-- V34（task-183B）：工作区文件级 ACL（把某个成员限制到「只看/只写指定目录或文件」）。
--
-- 目标：owner/admin 可把成员压到 restricted；restricted 成员只能看到/操作被授权的
-- 目录前缀（含子路径）或单个文件；owner/admin 永远等价于 all。
--
-- 设计要点（详见 docs/workspace-acl-design.md）：
--   * 成员级开关放在 workspace_members.access_mode：'all'（默认，保持现有成员体验不变）
--     或 'restricted'；只有 role = 0(owner) / 1(admin) 时该列被忽略（永远 all）。
--   * workspace_acl 一行 = 一条授权：
--       scope_type='folder' → scope_path 是目录逻辑路径（'/' 开头、无尾斜杠、'/' 表示整空间），
--                             该目录及其全部子路径都在授权范围内；
--       scope_type='file'   → file_id 指向具体文件行，scope_path 冗余保存其逻辑路径（便于统一匹配）。
--   * can_write 蕴含 can_read（应用层写入时同时置位，避免「能写不能读」的怪语义）。
--   * 未授权一律 403（写）/404（读，不泄露存在性），见 WorkspaceAclService。
--   * 删除文件行 / 删除工作区 / 删除用户时授权随之级联清理。

CREATE TABLE workspace_acl (
    id           BIGINT PRIMARY KEY,
    workspace_id BIGINT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope_type   VARCHAR(16) NOT NULL,
    scope_path   VARCHAR(1024) NOT NULL,
    file_id      BIGINT,
    can_read     BOOLEAN NOT NULL DEFAULT TRUE,
    can_write    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   BIGINT,
    CONSTRAINT ck_workspace_acl_scope_type CHECK (scope_type IN ('folder', 'file')),
    CONSTRAINT ck_workspace_acl_scope_shape CHECK (
        (scope_type = 'folder' AND file_id IS NULL AND scope_path <> '')
        OR (scope_type = 'file' AND file_id IS NOT NULL AND scope_path <> '')
    )
);

-- 显式外键：文件被彻底删除时该文件的 file 级授权一并清掉（不留悬空 id）
ALTER TABLE workspace_acl
    ADD CONSTRAINT fk_workspace_acl_file FOREIGN KEY (file_id)
        REFERENCES workspace_files(id) ON DELETE CASCADE;

-- 同一成员在同一路径上只允许一条授权（整包替换语义下天然成立，这里用索引兜底并发）。
-- 注意：用 md5(scope_path) 而不是 scope_path 本身做唯一键 —— 路径最多 1024 字符，
-- 中文路径按 3 字节/字符计算会超过 btree 索引行上限（约 2704 字节），md5 固定 32 字符稳定安全。
CREATE UNIQUE INDEX ux_workspace_acl_user_scope
    ON workspace_acl (workspace_id, user_id, scope_type, md5(scope_path));
-- 强制点每次请求按 (workspace_id, user_id) 取全部授权，走这条索引
CREATE INDEX idx_workspace_acl_workspace_user
    ON workspace_acl (workspace_id, user_id);

ALTER TABLE workspace_members ADD COLUMN access_mode VARCHAR(16) NOT NULL DEFAULT 'all';

ALTER TABLE workspace_members
    ADD CONSTRAINT ck_workspace_members_access_mode CHECK (access_mode IN ('all', 'restricted'));

COMMENT ON TABLE workspace_acl IS '工作区文件级 ACL 授权（restricted 成员的有效范围；owner/admin 忽略）';
COMMENT ON COLUMN workspace_acl.scope_type IS 'folder=目录前缀授权（含子路径）；file=单文件授权';
COMMENT ON COLUMN workspace_acl.scope_path IS '规范化逻辑路径：/ 开头、无尾斜杠；/ 表示整空间（仅 folder）';
COMMENT ON COLUMN workspace_acl.file_id IS 'scope_type=file 时的文件行 id；folder 时为 NULL';
COMMENT ON COLUMN workspace_acl.can_write IS '写权限（重命名/删除/移动/上传到该目录）；TRUE 时 can_read 必须为 TRUE';
COMMENT ON COLUMN workspace_members.access_mode IS 'all=不受限（默认）；restricted=仅按 workspace_acl 授权范围访问；owner/admin 永远 all';
