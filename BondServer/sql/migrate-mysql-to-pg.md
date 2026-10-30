# MySQL 8 → PostgreSQL 16/17 数据迁移手册

> 本文件是 task-6（PG 迁移）的配套人工操作清单。本机没有 Docker/PG，**以下步骤未在本机执行过**，
> 上线前必须在测试环境完整演练一遍。

## 0. 现状与重要前提

- 从本版本起 schema 的唯一事实来源是 Flyway 迁移：
  `BondServer/bond-common/src/main/resources/db/migration/V1__baseline.sql`
  （旧 `BondServer/sql/init.sql` 已移除；MySQL 版建表脚本仍保留在 `deploy-package/sql/init.sql`、
  `deploy-package/sql/init-source.sql` 以及历史交付 tar 包中，仅作回滚参考）。
- **生产 MySQL 库与旧 `init.sql` 可能不一致**（生产库很可能已手工补过列/索引）。
  迁移前必须先用 `information_schema` 做列级对比，**不要直接信任旧 init.sql**。
- V1 已补齐两个历史缺失列：`transfer_tasks.transfer_type`、`workspace_files.file_hash`。
  如果生产 MySQL 已有这两列，性质相同只需搬数据；如果没有，按默认值补：
  `transfer_type = 'inbox'`、`file_hash = NULL`。

## 1. 迁移前核对（MySQL 侧，只读）

```sql
-- 1.1 全库列清单，与 V1__baseline.sql 逐表逐列对比
SELECT table_name, column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'bond'
ORDER BY table_name, ordinal_position;

-- 1.2 关键列是否存在（transfer_type / file_hash）
SELECT table_name, column_name
FROM information_schema.columns
WHERE table_schema = 'bond'
  AND table_name IN ('transfer_tasks', 'workspace_files')
  AND column_name IN ('transfer_type', 'file_hash');

-- 1.3 各表行数（迁移后逐表核对）
SELECT 'users', COUNT(*) FROM users
UNION ALL SELECT 'devices', COUNT(*) FROM devices
UNION ALL SELECT 'transfer_tasks', COUNT(*) FROM transfer_tasks
UNION ALL SELECT 'transfer_chunks', COUNT(*) FROM transfer_chunks
UNION ALL SELECT 'workspaces', COUNT(*) FROM workspaces
UNION ALL SELECT 'workspace_members', COUNT(*) FROM workspace_members
UNION ALL SELECT 'workspace_files', COUNT(*) FROM workspace_files
UNION ALL SELECT 'friendships', COUNT(*) FROM friendships
UNION ALL SELECT 'workspace_invitations', COUNT(*) FROM workspace_invitations
UNION ALL SELECT 'system_config', COUNT(*) FROM system_config;

-- 1.4 迁移会触发新唯一约束，先查重复数据（有结果必须先人工清理）
--     1) 用户名大小写重复：V1 有 ux_users_username_lower
SELECT lower(username), COUNT(*) FROM users GROUP BY lower(username) HAVING COUNT(*) > 1;
--     2) 同工作区同目录重名文件：V1 有部分唯一索引 ux_workspace_files_file_name
SELECT workspace_id, parent_path, file_name, COUNT(*) FROM workspace_files
WHERE is_directory = FALSE
GROUP BY workspace_id, parent_path, file_name HAVING COUNT(*) > 1;
--     3) 重复邀请 token：V1 有 ux_workspace_invitations_token
SELECT invite_token, COUNT(*) FROM workspace_invitations
WHERE invite_token IS NOT NULL GROUP BY invite_token HAVING COUNT(*) > 1;
--     4) 外键孤儿：V1 新增了 transfer_tasks 的 sender/receiver/device/workspace 外键
SELECT COUNT(*) FROM transfer_tasks t LEFT JOIN users u ON u.id = t.sender_id WHERE u.id IS NULL;
SELECT COUNT(*) FROM transfer_tasks t LEFT JOIN users u ON u.id = t.receiver_id WHERE u.id IS NULL;
```

## 2. 目标 PostgreSQL 初始化

```bash
# 2.1 建库（UTF8；不要在 SQL 里 CREATE DATABASE，需要连到 postgres 库执行）
psql -h <pg-host> -U postgres -c "CREATE DATABASE bond WITH ENCODING 'UTF8' TEMPLATE template0;"

# 2.2 让 Flyway 建 schema（二选一）
#   A. 先启动一次 bond-auth（连到空库），Flyway 自动执行 V1 并写入 flyway_schema_history；
#   B. 或手工执行：
psql -h <pg-host> -U postgres -d bond -f bond-common/src/main/resources/db/migration/V1__baseline.sql
#      注意：手工执行后需要让 Flyway 认账（baseline），见第 6 节。
```

schema 已启用 `baseline-on-migrate=true`：空库会正常跑 V1；**非空库（已有表但没有
flyway_schema_history）会被 baseline 到版本 1 并跳过 V1**，适用于「先导数据/先建表」的库。

## 3. 数据导出（MySQL 侧）

推荐按表导出 TSV（避免 SQL 转义与布尔值歧义），再 `\copy` 导入。
MySQL 批量模式把 NULL 输出为字符串 `NULL`，需要显式转换：

```bash
# 3.1 逐表导出（示例：users），NULL 统一转 \N，布尔列转 t/f
mysql --default-character-set=utf8mb4 -B -N -e "
  SELECT
    IFNULL(CAST(id AS CHAR),'\\\\N'),
    IFNULL(username,'\\\\N'),
    IFNULL(password_hash,'\\\\N'),
    IFNULL(nickname,'\\\\N'),
    IFNULL(avatar_url,'\\\\N'),
    IFNULL(CAST(status AS CHAR),'\\\\N'),
    IF(COALESCE(is_admin,0)=1,'t','f'),
    IFNULL(DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s'),'\\\\N'),
    IFNULL(DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s'),'\\\\N')
  FROM users" bond > users.tsv

# 3.2 导入 PG（服务端 COPY，注意列顺序与 NULL '\N'）
psql -h <pg-host> -U postgres -d bond -c "
  \copy users (id, username, password_hash, nickname, avatar_url, status, is_admin, created_at, updated_at)
  FROM 'users.tsv' WITH (FORMAT text, NULL '\\N')"
```

要点：
- **布尔列**（users.is_admin、devices.is_online、workspace_files.is_directory）必须输出
  `t/f`（或 `true/false`），不能是 MySQL 的 `0/1`，否则 COPY 报错。
- `TINYINT` 状态列（status/role）保持数字即可，V1 里是 `SMALLINT`。
- `DATETIME` 输出 `YYYY-MM-DD HH:MM:SS` 文本，PG `TIMESTAMP` 可直接接受。
- `TEXT` 列若含换行/制表符，不要用 TSV，改用 `mysqldump --no-create-info --complete-insert`
  生成 INSERT 再清洗（见 3.3）。
- 空字符串 `''` 与 NULL 语义不同：`system_config` 里 `nat.stun_server`、`system.public_url`
  原本就是空串，不要转成 NULL。

3.3 备选：mysqldump INSERT 方式

```bash
mysqldump --no-create-info --complete-insert --skip-extended-insert \
  --default-character-set=utf8mb4 -u root -p bond > bond_data.sql
# 清洗：去掉反引号、去掉 MySQL 专有 SET 语句，然后 psql -f
sed -E 's/`//g; /^SET /d; /^\/\*!/d' bond_data.sql > bond_data.pg.sql
psql -h <pg-host> -U postgres -d bond -v ON_ERROR_STOP=1 -f bond_data.pg.sql
```
> 注意：此方式仍需处理布尔 0/1 → false/true，且 MySQL 的 `\'` 转义在 PG
> 下依赖 `standard_conforming_strings`；数据量大或内容复杂时优先用 TSV + `\copy`。

## 4. 导入顺序与约束

- 无外键的顺序：`users` → `devices` → `workspaces` → `workspace_members` → `workspace_files`
  → `transfer_tasks` → `transfer_chunks` → `friendships` → `workspace_invitations` → `system_config`。
- 如果顺序难保证，可临时关闭外键校验（需超级用户）：
  `SET session_replication_role = replica;` 导入后恢复 `origin`，并跑第 5 节孤儿检查。
- `system_config` 的 33 条默认值已包含在 V1 中；从 MySQL 导入时用 UPSERT，避免主键冲突：
  ```sql
  INSERT INTO system_config (config_key, config_value, config_type, description, updated_by, updated_at)
  VALUES (...) ON CONFLICT (config_key) DO UPDATE
    SET config_value = EXCLUDED.config_value,
        config_type = EXCLUDED.config_type,
        description = EXCLUDED.description,
        updated_by = EXCLUDED.updated_by,
        updated_at = EXCLUDED.updated_at;
  ```
- BIGINT 主键是雪花 ID，**没有 sequence**，不需要 `setval`。

## 5. 人工验证清单（在 PG 上逐条执行，全部通过才算迁移成功）

```sql
-- 5.1 业务表数量 = 10（不含 flyway_schema_history）
SELECT count(*) FROM information_schema.tables
WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name <> 'flyway_schema_history';

-- 5.2 外键数量 = 18（V1 中 devices 1 + workspaces 1 + workspace_members 2 + workspace_files 2
--     + transfer_tasks 5 + transfer_chunks 1 + friendships 2 + workspace_invitations 3
--     + system_config 1）
SELECT count(*) FROM pg_constraint c
JOIN pg_namespace n ON n.oid=c.connamespace WHERE c.contype='f' AND n.nspname='public';

-- 5.3 关键补齐列存在
SELECT table_name, column_name FROM information_schema.columns
WHERE table_schema='public'
  AND ((table_name='transfer_tasks' AND column_name='transfer_type')
    OR (table_name='workspace_files' AND column_name='file_hash'));

-- 5.4 updated_at 触发器 = 5 个
SELECT event_object_table, trigger_name FROM information_schema.triggers
WHERE trigger_schema='public' ORDER BY 1;

-- 5.5 唯一约束/唯一索引齐全（应看到 users username、username_lower、rank 唯一、chunks、files、invite token）
SELECT indexname, indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY tablename, indexname;

-- 5.6 每表行数与 MySQL 侧（1.3）逐表一致
SELECT 'users' t, count(*) FROM users UNION ALL SELECT 'devices', count(*) FROM devices
UNION ALL SELECT 'transfer_tasks', count(*) FROM transfer_tasks
UNION ALL SELECT 'transfer_chunks', count(*) FROM transfer_chunks
UNION ALL SELECT 'workspaces', count(*) FROM workspaces
UNION ALL SELECT 'workspace_members', count(*) FROM workspace_members
UNION ALL SELECT 'workspace_files', count(*) FROM workspace_files
UNION ALL SELECT 'friendships', count(*) FROM friendships
UNION ALL SELECT 'workspace_invitations', count(*) FROM workspace_invitations
UNION ALL SELECT 'system_config', count(*) FROM system_config;

-- 5.7 外键删除规则（confdeltype: c=CASCADE, n=SET NULL, r=RESTRICT）
SELECT conname, confdeltype FROM pg_constraint WHERE contype='f' ORDER BY conname;

-- 5.8 孤儿数据必须为 0
SELECT count(*) FROM transfer_chunks c LEFT JOIN transfer_tasks t ON t.id=c.task_id WHERE t.id IS NULL;
SELECT count(*) FROM workspace_files f LEFT JOIN workspaces w ON w.id=f.workspace_id WHERE w.id IS NULL;
SELECT count(*) FROM workspace_members m LEFT JOIN users u ON u.id=m.user_id WHERE u.id IS NULL;

-- 5.9 Flyway 状态：V1 已应用、success=t
SELECT installed_rank, version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;

-- 5.10 updated_at 触发器生效验证（在测试库执行，事务内回滚）
BEGIN;
UPDATE users SET nickname = nickname WHERE id = (SELECT id FROM users LIMIT 1) RETURNING id, updated_at;
ROLLBACK;
```

> 注意：`ON DELETE` 规则与旧 MySQL 行为不同（旧库多数外键是 RESTRICT，V1 改为 CASCADE/SET NULL）。
> 5.7 核对后可接受「删用户会级联删传输/工作区」这一新语义。

## 6. 手工执行过 V1 的库如何让 Flyway 认账

若第 2.2 节选了 B（手工 psql 执行 V1），应用首次启动会因非空库触发 baseline-on-migrate，
默认 baseline 到版本 1 并跳过 V1 —— 与手工执行结果一致，无需额外操作。
若使用独立 `flyway` CLI：`flyway -baselineOnMigrate=true -baselineVersion=1 migrate`。

## 7. 回滚

- 部署层回滚见 `BondServer/deploy-server.sh` 中 postgres 服务上方的 MySQL 注释块。
- 应用层回滚需要同时回退：pom（postgresql → mysql-connector-j，Flyway 停用或换 flyway-mysql）、
  `application-*.yml` 的 url/driver/username、以及 Flyway 迁移目录。
- 数据回滚只能靠迁移前对 MySQL 做全量备份（`mysqldump --single-transaction`）+ 切换期间写停机。
