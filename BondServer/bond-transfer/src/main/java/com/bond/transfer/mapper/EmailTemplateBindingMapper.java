package com.bond.transfer.mapper;

import com.bond.common.entity.EmailTemplateBinding;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 邮件用途绑定 Mapper（task-76，V17）。
 *
 * <p>bond-transfer 管理接口负责读写；bond-auth 有同结构的只读 Mapper（两个服务独立 MyBatis 上下文）。</p>
 */
@Mapper
public interface EmailTemplateBindingMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<EmailTemplateBinding> {

    @Select("SELECT purpose, template_code, updated_by, updated_at "
            + "FROM email_template_bindings WHERE purpose = #{purpose}")
    EmailTemplateBinding selectByPurpose(String purpose);

    @Select("SELECT purpose, template_code, updated_by, updated_at FROM email_template_bindings")
    List<EmailTemplateBinding> selectAllBindings();

    /** 某模板被哪些用途绑定（删除模板前检查，task-76）。 */
    @Select("SELECT purpose FROM email_template_bindings WHERE template_code = #{code} ORDER BY purpose")
    List<String> selectPurposesByTemplate(String code);

    /** 绑定（同用途覆盖）；管理员每次保存都 upsert。 */
    @Insert("""
            INSERT INTO email_template_bindings (purpose, template_code, updated_by, updated_at)
            VALUES (#{purpose}, #{templateCode}, #{updatedBy}, now())
            ON CONFLICT (purpose) DO UPDATE
               SET template_code = EXCLUDED.template_code,
                   updated_by = EXCLUDED.updated_by,
                   updated_at = now()
            """)
    int upsert(EmailTemplateBinding binding);

    /** 解除绑定（用途回到内置默认）。 */
    @Delete("DELETE FROM email_template_bindings WHERE purpose = #{purpose}")
    int deleteByPurpose(String purpose);
}
