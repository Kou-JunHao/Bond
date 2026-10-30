-- V8: 文件 SHA-256 去重 / 秒传索引（task-41）
--
-- 背景：V1 起 workspace_files.file_hash 列就存在，但此前从未被写入（单次上传与分片 complete
-- 都写 NULL），所以既没有去重也没有秒传。task-41 开始：
--   * 单次上传用 DigestInputStream 流式算 SHA-256 落库；
--   * 分片上传由客户端在 init/complete 时声明整文件 hash 落库（服务端不重读对象计算）。
-- 历史行的 file_hash 保持 NULL，不回填。
--
-- 秒传查找条件是 (file_hash, file_size) + 未删除，故建部分索引：
--   WHERE file_hash IS NOT NULL AND deleted_at IS NULL
CREATE INDEX ix_workspace_files_hash_size
    ON workspace_files (file_hash, file_size)
    WHERE file_hash IS NOT NULL AND deleted_at IS NULL;
