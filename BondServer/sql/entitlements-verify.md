# 权益层（task-7）人工验证清单

> 本机无 PG / 无 RustFS，以下运行时步骤必须在发布后按顺序执行。
> 口径：**只计下行（下载）**；上传不计流量、不限速；纯 LAN 用户已用流量恒为 0。

## 1. 迁移确认

```sql
-- 1.1 Flyway V3 已应用
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
-- 期望 1/2/3 全部 success=t

-- 1.2 三档 seed 存在
SELECT code, limits->>'storage_bytes' AS storage, limits->>'relay_bytes_month' AS traffic FROM plans ORDER BY price_cents;

-- 1.3 关键约束/扩展
SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist';               -- 期望 1
SELECT conname FROM pg_constraint WHERE conname = 'no_overlap';               -- 期望 1 行
SELECT column_name FROM information_schema.columns
 WHERE table_name='users' AND column_name IN ('plan_code','plan_expires_at'); -- 期望 2 行

-- 1.4 现有用户默认 free
SELECT plan_code, count(*) FROM users GROUP BY plan_code;                     -- 期望 free = 全部
```

## 2. 注册 / 个人空间配额（free = 5GB）

1. 注册新用户 → `workspaces` 中 personal 行 `max_size = 5368709120`（free 档）。
2. 用 admin token：`POST /api/admin/users/{id}/plan` `{"planCode":"plus","months":1}`
   → 该用户 personal 工作区 `max_size` 应变为 214748364800，且 `subscriptions` 增加一条 active。
3. 降级：`{"planCode":"free"}` → `max_size` 回到 5GB；若已用空间 > 5GB，**文件不删除**，新上传应返回 400（只读）。

## 3. 计量（关键回归）

1. **纯上传不计流量**：上传 100MB 到个人空间/工作区 → `SELECT relay_bytes_down FROM usage_counters WHERE user_id=? AND period=to_char(now(),'YYYY-MM')` 应仍为 0（或该行不存在）。
2. **下载计下行**：下载上述 100MB → relay_bytes_down 增加约 104857600。
3. **断点续传**：只下 2 个分片 → 只增加这两个分片的字节数（重复下载同一分片会再次计，符合"实际出口流量"）。
4. **工作区下载计下载者**：A 创建团队工作区，B 上传、C 下载 → 只增加 C 的 usage；A/B 不变。
5. **LAN 直传**：客户端局域网直传不经过 `/api/transfer/chunks/**`，因此不产生 usage 行（验收铁律：LAN 用户 500GB 仍为 0）。

## 4. 限额/降级语义

1. 把某用户 `user_entitlements` 加量（`POST /api/admin/grant`，key=`relay_bytes_month`, value=1073741824）→ 立即生效（缓存 60s 内失效，接口会主动 invalidate）。
2. 构造用量 > 限额（测试库直接 UPDATE usage_counters）→ 下载仍成功，但应明显变慢（1MB/s）；**不得返回错误、不得断开**。
3. 空间用尽 → 上传/新文件 400 "workspace storage not enough"；已有文件可列表、可下载、可删除。

## 5. 兑换码

1. `POST /api/admin/redeem-codes` `{"planCode":"plus","durationDays":30,"count":3}` → 返回 3 个 `BOND-...` 码。
2. 用户 `POST /api/redeem` `{"code":"..."}` → 200，返回 planCode/expiresAt；`orders` 新增 `provider='redeem', provider_txn_id=code`。
3. 同码再次兑换 → 400（幂等）；`redeem_codes.status='used'`。

## 6. 并发原子性（可用两个并发请求验证）

1. 工作区剩余空间 10MB，同时发起两个 8MB 上传 → 只能成功一个（`UPDATE ... WHERE used_size + ? <= max_size` 影响行数为 1 的才成功）。
2. 并发下载累加 `usage_counters` → 最终值等于各次字节数之和，无丢失更新（`ON CONFLICT ... + EXCLUDED`）。

## 7. 管理端审计

```sql
SELECT action, target_user_id, detail, created_at FROM admin_operation_logs ORDER BY created_at DESC LIMIT 20;
-- 期望能看到 set-plan / grant-entitlement / create-redeem-codes
```
