-- V22：把 subscriptions 的 no_overlap 排除约束改成「只约束 active 订阅」。
--
-- 背景（task-109）：V3 里的 no_overlap 没有 status 过滤，
--   EXCLUDE USING gist (user_id WITH =, tstzrange(period_start, period_end) WITH &&)
-- 会把 expired 的历史订阅也算作"占用区间"，导致同一用户第二次调档时
-- INSERT 新的 active 订阅触发 exclusion constraint 冲突（接口 500）。
--
-- 改动：drop 旧约束，重建为部分排除约束 WHERE (status = 'active')。
--   历史行（expired/canceled/past_due 等）不再参与重叠判定。
-- 幂等：先探测约束定义是否已带 status 过滤；是则跳过，可安全重跑。
-- btree_gist 提供 user_id 的 = 操作符给 GiST 排除约束使用。

CREATE EXTENSION IF NOT EXISTS btree_gist;

DO $$
DECLARE
    already_active_only boolean;
BEGIN
    SELECT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE t.relname = 'subscriptions'
          AND c.conname = 'no_overlap'
          AND c.contype = 'x'
          AND pg_get_constraintdef(c.oid) LIKE '%status%'
    ) INTO already_active_only;

    IF NOT already_active_only THEN
        ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS no_overlap;
        ALTER TABLE subscriptions ADD CONSTRAINT no_overlap
            EXCLUDE USING gist (
                user_id WITH =,
                tstzrange(period_start, period_end) WITH &&
            ) WHERE (status = 'active');
    END IF;
END $$;

COMMENT ON CONSTRAINT no_overlap ON subscriptions
    IS '仅 active 订阅的区间不得重叠；expired/canceled 等历史订阅不参与判定（V22/task-109）';
