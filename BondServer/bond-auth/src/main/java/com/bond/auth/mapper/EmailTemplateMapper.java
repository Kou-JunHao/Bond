package com.bond.auth.mapper;

import com.bond.common.entity.EmailTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 邮件模板读取（task-63）：bond-auth 发验证码时按用途取模板（DB 优先，缺省回退内置）。
 *
 * <p>只做只读查询；模板的增删改在 bond-transfer 的管理接口里（那边有自己的同名 Mapper）。</p>
 */
@Mapper
public interface EmailTemplateMapper {

    @Select("SELECT code, name, subject, body_html, body_text, variables::text AS variables, "
            + "enabled, is_builtin, updated_by, updated_at "
            + "FROM email_templates WHERE code = #{code}")
    EmailTemplate selectByCode(@Param("code") String code);

    @Select("SELECT code, name, subject, body_html, body_text, variables::text AS variables, "
            + "enabled, is_builtin, updated_by, updated_at "
            + "FROM email_templates ORDER BY is_builtin DESC, code")
    List<EmailTemplate> selectAll();
}
