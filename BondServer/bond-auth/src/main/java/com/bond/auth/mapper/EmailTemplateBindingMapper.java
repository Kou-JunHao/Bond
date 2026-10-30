package com.bond.auth.mapper;

import com.bond.common.entity.EmailTemplateBinding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 邮件用途绑定只读 Mapper（task-76，V17）：bond-auth 发信（验证码/欢迎）时按用途解析模板。
 *
 * <p>写入只发生在 bond-transfer 管理台；本 Mapper 只读，避免两个服务双写。</p>
 */
@Mapper
public interface EmailTemplateBindingMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<EmailTemplateBinding> {

    @Select("SELECT purpose, template_code, updated_by, updated_at "
            + "FROM email_template_bindings WHERE purpose = #{purpose}")
    EmailTemplateBinding selectByPurpose(String purpose);
}
