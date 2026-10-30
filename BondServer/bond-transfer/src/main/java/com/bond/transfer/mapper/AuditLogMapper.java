package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.AuditLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 管理操作审计日志数据访问（任务单 #19，V15）。
 *
 * <p>写入走自定义 SQL：{@code detail} 是 JSONB 列，必须显式 {@code ::jsonb} 转换，
 * 直接 BaseMapper.insert 会因 varchar 无法隐式转 jsonb 而报错。</p>
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    @Insert("""
            INSERT INTO audit_logs (id, actor_id, actor_name, action, target_type, target_id, detail, ip, created_at)
            VALUES (#{id}, #{actorId}, #{actorName}, #{action}, #{targetType}, #{targetId}, #{detail}::jsonb, #{ip}, now())
            """)
    int insertLog(AuditLog log);

    /** 动作枚举下拉（管理台筛选用）。 */
    @Select("SELECT DISTINCT action FROM audit_logs ORDER BY action")
    List<String> selectActions();
}
