package com.bond.transfer.mapper;

import com.bond.common.entity.EmailTemplate;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 邮件模板 Mapper（task-63）：bond-transfer 管理接口用，带完整增删改查。
 *
 * <p>与 bond-auth 的同名 Mapper 分开，是因为两个服务都有独立的 MyBatis 上下文；
 * SQL 保持单一来源（表结构见 V16），避免某一侧漏掉字段。</p>
 *
 * <p>task-71：{@code variables} 是 JSONB 列，MyBatis-Plus {@code updateById/insert} 会把
 * Java String 当 varchar 绑定，PG 报 {@code column "variables" is of type jsonb but expression
 * is of type character varying}。因此写入一律走本接口的手写 SQL，显式 {@code #{variables}::jsonb}
 * （与 {@code UploadSessionMapper} / {@code AuditLogMapper} 同一先例）。</p>
 */
@Mapper
public interface EmailTemplateMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<EmailTemplate> {

    @Select("SELECT code, name, subject, body_html, body_text, variables::text AS variables, "
            + "enabled, is_builtin, updated_by, updated_at "
            + "FROM email_templates WHERE code = #{code}")
    EmailTemplate selectByCode(String code);

    @Select("SELECT code, name, subject, body_html, body_text, variables::text AS variables, "
            + "enabled, is_builtin, updated_by, updated_at "
            + "FROM email_templates ORDER BY is_builtin DESC, code")
    List<EmailTemplate> selectAll();

    /** 更新模板（variables 显式转 jsonb）。 */
    @Update("""
            UPDATE email_templates
               SET name = #{name},
                   subject = #{subject},
                   body_html = #{bodyHtml},
                   body_text = #{bodyText},
                   variables = #{variables}::jsonb,
                   enabled = #{enabled},
                   is_builtin = #{isBuiltin},
                   updated_by = #{updatedBy},
                   updated_at = now()
             WHERE code = #{code}
            """)
    int updateTemplate(EmailTemplate template);

    /** 重建被删的内置模板（variables 显式转 jsonb）。 */
    @Insert("""
            INSERT INTO email_templates
                (code, name, subject, body_html, body_text, variables, enabled, is_builtin, updated_by, updated_at)
            VALUES
                (#{code}, #{name}, #{subject}, #{bodyHtml}, #{bodyText}, #{variables}::jsonb,
                 #{enabled}, #{isBuiltin}, #{updatedBy}, now())
            """)
    int insertTemplate(EmailTemplate template);

    /** 删除自定义模板（task-76；内置模板由服务层拦截，不允许走这里）。 */
    @Delete("DELETE FROM email_templates WHERE code = #{code}")
    int deleteByCode(String code);
}
