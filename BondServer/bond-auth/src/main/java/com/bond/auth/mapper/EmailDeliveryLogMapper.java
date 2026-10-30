package com.bond.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.EmailDeliveryLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 邮件投递日志写入（task-80）。bond-auth 只写不查，查询在管理端（bond-transfer）。
 */
@Mapper
public interface EmailDeliveryLogMapper extends BaseMapper<EmailDeliveryLog> {
}
