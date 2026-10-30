package com.bond.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.EmailVerificationCode;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EmailVerificationCodeMapper extends BaseMapper<EmailVerificationCode> {

    @Select("SELECT * FROM email_verification_codes "
            + "WHERE email = #{email} AND purpose = #{purpose} AND consumed_at IS NULL "
            + "ORDER BY created_at DESC LIMIT 1")
    EmailVerificationCode selectActive(@Param("email") String email, @Param("purpose") String purpose);

    @Select("SELECT * FROM email_verification_codes "
            + "WHERE user_id = #{userId} AND purpose = #{purpose} AND consumed_at IS NULL "
            + "ORDER BY created_at DESC LIMIT 1")
    EmailVerificationCode selectActiveByUser(@Param("userId") Long userId, @Param("purpose") String purpose);

    /** 同一邮箱 + 用途 60 秒内是否已发过（冷却）。 */
    @Select("SELECT count(*) FROM email_verification_codes "
            + "WHERE email = #{email} AND purpose = #{purpose} AND created_at >= now() - interval '60 seconds'")
    long countWithinCooldown(@Param("email") String email, @Param("purpose") String purpose);

    /** 同一邮箱 + 用途 当日（UTC 日界）已发送次数。 */
    @Select("SELECT count(*) FROM email_verification_codes "
            + "WHERE email = #{email} AND purpose = #{purpose} AND created_at >= date_trunc('day', now())")
    long countToday(@Param("email") String email, @Param("purpose") String purpose);

    @Insert("INSERT INTO email_verification_codes "
            + "(id, email, user_id, purpose, code_hash, expires_at, attempts, ip, created_at) "
            + "VALUES (#{id}, #{email}, #{userId}, #{purpose}, #{codeHash}, #{expiresAt}, 0, #{ip}, now())")
    int insertCode(EmailVerificationCode code);

    @Update("UPDATE email_verification_codes SET attempts = attempts + 1 WHERE id = #{id}")
    int incrementAttempts(@Param("id") Long id);

    /** 一次性消费：并发下只有一个请求能把 consumed_at 从 NULL 改成 now()。 */
    @Update("UPDATE email_verification_codes SET consumed_at = now() "
            + "WHERE id = #{id} AND consumed_at IS NULL")
    int consume(@Param("id") Long id);

    @Update("UPDATE email_verification_codes SET consumed_at = now() WHERE id = #{id}")
    int invalidate(@Param("id") Long id);
}
