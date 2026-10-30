-- V13: 传输中心「接收方下载进度」。
-- 迁移纪律（engineering-pitfalls #17）：V10/V11/V12 已上生产，禁止回改；本文件是新增 V13。
--
-- 选型说明：
--   * downloaded_chunks 放在 transfer_tasks 上，做成计数列（INT NOT NULL DEFAULT 0），
--     由下载接口用原子 UPDATE ... SET downloaded_chunks = downloaded_chunks + 1 自增，
--     避免「全量扫 transfer_chunks 计算进度」的读放大；
--   * transfer_chunks.downloaded_at 用 TIMESTAMPTZ（实体 OffsetDateTime，与 V12 起新增列一致），
--     以「NULL → now()」的单向状态迁移做前端重试/断点续传的幂等键，不额外建事件表。
ALTER TABLE transfer_tasks
    ADD COLUMN downloaded_chunks INT NOT NULL DEFAULT 0;

ALTER TABLE transfer_chunks
    ADD COLUMN downloaded_at TIMESTAMPTZ NULL;

COMMENT ON COLUMN transfer_tasks.downloaded_chunks IS '接收方已下载分片数（原子自增，仅 receiver 下载时计数）';
COMMENT ON COLUMN transfer_chunks.downloaded_at IS '接收方首次成功取走该分片的时间（NULL=未下载，非 NULL 后不再重写，幂等）';
