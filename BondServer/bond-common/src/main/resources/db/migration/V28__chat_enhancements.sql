-- V28（task-136）：聊天增强 —— 图片/文件消息、撤回、已读回执细化、置顶/免打扰、消息搜索、未读聚合。
--
-- 设计说明：
--   * kind 白名单从 (text,file,system) 扩到 (text,file,image,system)；image 与 file 一样引用
--     workspace_files.id（复用网盘对象，不新建存储），由 ChatService 校验会话内可访问。
--   * 撤回 = 软状态：recalled=true、recalled_at=now()、body/file_id 清空，占位消息保留。
--     因此“text 必须有 body / file、image 必须有 file_id”两条 CHECK 都要允许 recalled 行豁免。
--   * 置顶/免打扰是**成员维度**（每个用户自己的会话设置），不放在 chat_conversations 上，
--     否则 1:1 会话里一方置顶会影响另一方；V12 已有 chat_members.muted，本迁移补 pinned。
--   * last_read_at 仅在显式已读上报时刷新（read-state 展示用），历史行保持 NULL。
--
-- 注意：只加列 + 重建 CHECK，不改既有数据；新增列均带默认值或可空，在线执行无长锁。

ALTER TABLE chat_messages ADD COLUMN recalled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_messages ADD COLUMN recalled_at TIMESTAMPTZ NULL;

ALTER TABLE chat_members ADD COLUMN last_read_at TIMESTAMPTZ NULL;
ALTER TABLE chat_members ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;

-- 重建 chat_messages 的 CHECK：旧约束没有显式命名（PostgreSQL 自动命名），先全部删掉再按新语义建。
DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'chat_messages'::regclass AND contype = 'c'
    LOOP
        EXECUTE format('ALTER TABLE chat_messages DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE chat_messages
    ADD CONSTRAINT ck_chat_messages_kind CHECK (kind IN ('text', 'file', 'image', 'system'));
ALTER TABLE chat_messages
    ADD CONSTRAINT ck_chat_messages_text_body CHECK (kind <> 'text' OR body IS NOT NULL OR recalled);
ALTER TABLE chat_messages
    ADD CONSTRAINT ck_chat_messages_file_ref CHECK (kind NOT IN ('file', 'image') OR file_id IS NOT NULL OR recalled);

-- 消息搜索：会话内按创建时间倒序取前缀命中（q%），走 keyset 无 offset；本索引支持会话内倒序扫描。
CREATE INDEX IF NOT EXISTS ix_chat_msg_search ON chat_messages (conversation_id, id DESC)
    WHERE deleted_at IS NULL AND recalled = FALSE;

-- 未读聚合：按用户取成员行（含 pinned）。
CREATE INDEX IF NOT EXISTS ix_chat_members_user_pinned ON chat_members (user_id, pinned);

COMMENT ON COLUMN chat_messages.recalled IS '是否已撤回（占位消息保留；正文/文件引用已清空）';
COMMENT ON COLUMN chat_messages.recalled_at IS '撤回时间（TIMESTAMPTZ）';
COMMENT ON COLUMN chat_members.last_read_at IS '最近一次显式已读上报时间（read-state 展示；NULL=从未上报）';
COMMENT ON COLUMN chat_members.pinned IS '该用户是否将此会话置顶（成员维度，最多 20 个）';
