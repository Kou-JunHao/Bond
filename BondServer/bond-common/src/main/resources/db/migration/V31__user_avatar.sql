-- task-152：用户头像上传能力。
--
-- 只补一列时间戳：users.avatar_url 早已存在（V1），本轮新增上传/删除/自托管访问端点，
-- avatar_url 存本服务路径 `/api/users/{id}/avatar?v={unix秒}`，v 与 avatar_updated_at 匹配才可访问，
-- 用于防猜路径与防 CDN/浏览器缓存串号。不再新增 avatar 列，也不加 display_name（已有 nickname）。

ALTER TABLE users ADD COLUMN avatar_updated_at TIMESTAMPTZ NULL;

COMMENT ON COLUMN users.avatar_updated_at IS '头像最后更新时间（task-152）；avatar_url 中的 v 参数与之匹配才返回 200';
