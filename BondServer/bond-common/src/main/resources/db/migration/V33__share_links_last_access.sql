-- task-163：分享链接补「最近一次成功下载时间」，供客户端展示与运营排查。
--
-- 说明：
--   * 只新增列，不改既有语义；status 仍为 0=正常 / 1=已撤销 / 2=已过期（定时标记），
--     但「是否过期」以 expires_at 时间判定为准（PATCH 改期可直接让链接重新生效）。
--   * 写入时机：完整下载开始 +1 次数的同一 SQL 内写；纯 Range 续传只 touch 不改次数。
ALTER TABLE share_links ADD COLUMN last_access_at TIMESTAMPTZ NULL;

COMMENT ON COLUMN share_links.last_access_at IS '最近一次成功开始下载的时间（完整下载或 Range 访问；不参与计费）';
