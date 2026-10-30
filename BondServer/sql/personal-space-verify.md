# 个人空间（personal workspace）上线人工验证清单

> 本机没有 PG/Docker，以下运行时步骤必须在测试环境或发布后按顺序执行。
> 个人空间 = `workspaces` 表 `type='personal'` 的一行，复用 `workspace_files` / 配额 / MinIO 逻辑。

## 1. 迁移确认（发布后第一次启动）

```sql
-- 1.1 V2 已应用
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
-- 期望至少：1 baseline success=t，2 personal space success=t

-- 1.2 workspaces.type 列存在，默认值 team
SELECT column_name, data_type, column_default, is_nullable
FROM information_schema.columns
WHERE table_schema='public' AND table_name='workspaces' AND column_name='type';

-- 1.3 部分唯一索引存在
SELECT indexname, indexdef FROM pg_indexes
WHERE schemaname='public' AND tablename='workspaces' AND indexname='ux_workspaces_personal_owner';
-- 期望：... UNIQUE ... (owner_id) WHERE (type = 'personal'::text)

-- 1.4 存量用户回填后：每个用户恰好一个 personal 空间，且没有重复
SELECT count(*) FROM (
  SELECT owner_id FROM workspaces WHERE type='personal' GROUP BY owner_id HAVING count(*) > 1
) dup;  -- 期望 0

SELECT
  (SELECT count(*) FROM users)                     AS users,
  (SELECT count(*) FROM workspaces WHERE type='personal') AS personal_spaces;
-- 期望 personal_spaces >= users（回填后应相等；被删用户除外）

-- 1.5 团队工作区数量不变（V2 不影响已有行，全部默认 type='team'）
SELECT type, count(*) FROM workspaces GROUP BY type;
```

## 2. 新用户注册链路

1. `POST /api/auth/register`（username/password/nickname）→ 200，返回 `data.id` 为字符串。
2. 查库确认个人空间与 OWNER 成员行：
   ```sql
   SELECT id, name, owner_id, type, max_size, used_size
   FROM workspaces WHERE owner_id = <新用户id> AND type='personal';
   -- 期望 1 行，max_size=5368709120（5GB 默认）

   SELECT wm.workspace_id, wm.user_id, wm.role
   FROM workspace_members wm JOIN workspaces w ON w.id=wm.workspace_id
   WHERE w.owner_id=<新用户id> AND w.type='personal';
   -- 期望 1 行，role=0(OWNER)
   ```
3. 同一用户重复调用任何 `/api/files/**` 不应重复创建：`POST /api/auth/register` 用重复用户名应 400，不会产生第二个 personal 行。

## 3. 文件页 API（携带 `Authorization: Bearer <token>`）

| 步骤 | 请求 | 期望 |
| --- | --- | --- |
| 3.1 | `GET /api/files` | 200，`data=[]`（新用户空列表） |
| 3.2 | `POST /api/files/upload`（multipart：file，可选 parentPath/fileHash） | 200，data.id 为字符串，workspaceId 指向 personal 空间 |
| 3.3 | `GET /api/files` | 列表包含 3.2 的文件，fileSize/createdAt 为数字，id/workspaceId/uploadedBy 为字符串 |
| 3.4 | `POST /api/files/rename` body `{"fileId":"...","newName":"new.txt"}` | 200，data.fileName=new.txt |
| 3.5 | `GET /api/files/{fileId}/download` | 200，二进制内容与原文件一致 |
| 3.6 | `DELETE /api/files/{fileId}` | 200；再 GET 列表为空；库中 `workspaces.used_size` 已扣回 |
| 3.7 | 分片：`POST /api/files/chunks/init` → `POST /api/files/chunks/{uploadId}/{chunkIndex}`（multipart file）→ `POST /api/files/chunks/{uploadId}/complete` | 200；complete 返回文件 DTO；`POST /api/files/chunks/{uploadId}/status` 可查已传分片 |
| 3.8 | 秒传：先上传文件，再用 `POST /api/files/check-hash` 同 hash+size | 返回已有文件 DTO |

## 4. 列表隔离（关键回归项）

1. `GET /api/workspaces` → **不得包含** type='personal' 的个人空间。
2. 新建团队工作区后 `GET /api/workspaces` 只返回团队工作区。
3. 直接 `DELETE /api/workspaces/{personalId}`（personalId 可从 `/api/files` 响应的 workspaceId 取得）→ 期望 400 `personal space cannot be deleted`，且个人空间与文件仍在。

## 5. 网关路由

```bash
# 不带 token 访问个人空间接口必须 401
curl -i http://<gateway>/api/files
# 期望 HTTP/1.1 401（/api/files/** 不在 AuthFilter 白名单）
```

## 6. 配额

- 个人空间与团队工作区配额相互独立：向个人空间上传文件只增加 personal 行的 `used_size`；
  团队工作区 `used_size` 不变。
- 超额仍由现有 `WorkspaceService.validateUploadLimits` 拒绝（返回 400 workspace storage not enough）；
  原子扣减/档位额度由 task-7（权益层）接管，本期未改。
