-- V29: 在线文档 v2 —— 富文本 HTML 存储（task-137）
--
-- 说明：
-- 1) content_type 列已存在（V10，VARCHAR(32) DEFAULT 'markdown'），本迁移不重建；
--    新增取值 'html' 表示正文权威存储在 content_html（content_md 置空字符串）。
-- 2) content_html 存放**已通过服务端白名单净化**的 HTML（`ContentSanitizer`），
--    客户端提交的原文不落库；历史 markdown 文档该列为 NULL。
-- 3) 版本表同步存储 HTML，保证恢复历史版本时正文与类型一致。

ALTER TABLE documents ADD COLUMN content_html TEXT;

ALTER TABLE document_versions ADD COLUMN content_html TEXT;

COMMENT ON COLUMN documents.content_html IS '富文本 HTML（已白名单净化；content_type=html 时为权威正文）';
COMMENT ON COLUMN document_versions.content_html IS '历史版本 HTML（已白名单净化；markdown 文档为 NULL）';
