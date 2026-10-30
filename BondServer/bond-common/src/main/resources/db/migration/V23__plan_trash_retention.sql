-- V23: 回收站保留期按档位（task-110）。
--
-- 语义：trash_expires_at = 删除时刻 now() + 生效档位的 trash_retention_days。
-- 本迁移为**纯数据 + additive**：只给 plans.limits 补 trash_retention_days 键，
-- 不改任何表结构、不碰历史数据；生产只需随应用启动由 Flyway 自动执行。
--
-- 幂等：仅在该键缺失时写入（`NOT (limits ? 'trash_retention_days')`），
-- 重复执行不会覆盖运营后台已调整过的值。
--
-- 兜底优先级（代码侧，见 TrashRetentionPolicy javadoc）：
--   档位 limits.trash_retention_days > 全局配置 trash.retention_days > 硬编码 7 天。
-- 0 表示不限期（不限保留期，只能由运营显式配置）。

UPDATE plans
SET limits = limits || '{"trash_retention_days": 7}'::jsonb
WHERE code = 'free'
  AND NOT (limits ? 'trash_retention_days');

UPDATE plans
SET limits = limits || '{"trash_retention_days": 30}'::jsonb
WHERE code = 'plus'
  AND NOT (limits ? 'trash_retention_days');

UPDATE plans
SET limits = limits || '{"trash_retention_days": 90}'::jsonb
WHERE code = 'team'
  AND NOT (limits ? 'trash_retention_days');
