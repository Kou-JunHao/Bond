-- V17: 邮件「用途 → 模板」绑定（task-76 方案 B）
--
-- 只存绑定关系；模板本体仍在 email_templates（V16）。
-- 未绑定、绑定指向已删除/禁用模板时，发送侧回退该用途的内置默认模板（见 EmailPurposes）。
-- 用途集合固定在后端枚举（register_code / password_reset / change_email / welcome /
-- admin_reset_password / admin_operation_notice），前端不能自由新增用途。
CREATE TABLE IF NOT EXISTS email_template_bindings (
    purpose       VARCHAR(64) PRIMARY KEY,
    template_code VARCHAR(64) NOT NULL,
    updated_by    BIGINT REFERENCES users(id) ON DELETE SET NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE email_template_bindings IS '邮件用途与模板绑定（task-76，V17）';
COMMENT ON COLUMN email_template_bindings.purpose IS '固定用途编码，见 com.bond.common.email.EmailPurpose';
COMMENT ON COLUMN email_template_bindings.template_code IS 'email_templates.code（内置或自定义）';

CREATE TRIGGER trg_email_template_bindings_set_updated_at
    BEFORE UPDATE ON email_template_bindings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
