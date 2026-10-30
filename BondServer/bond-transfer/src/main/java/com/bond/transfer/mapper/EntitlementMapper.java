package com.bond.transfer.mapper;

import com.bond.transfer.entitlement.EntitlementRow;
import com.bond.transfer.entitlement.SubscriptionRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 权益层相关 mapper（V3 表）。刻意不建实体：只做窄查询/原子写，避免 JSONB/TIMESTAMPTZ 映射复杂度。
 */
@Mapper
public interface EntitlementMapper {

    /** plans.limits 的 JSON 文本；不存在/停用返回 null。 */
    @Select("SELECT limits::text FROM plans WHERE code = #{code} AND is_active = TRUE")
    String selectPlanLimits(@Param("code") String code);

    /** 所有启用档位的 code→name（PlanCatalog 启动时一次性加载，避免每次请求查 plans）。 */
    @Select("SELECT code, name FROM plans WHERE is_active = TRUE")
    List<Map<String, Object>> selectPlanNames();

    /** 管理端：三个档位全部（含 is_active=false），limits 以 JSON 文本返回（task-112）。 */
    @Select("SELECT code, name, price_cents AS \"priceCents\", period, "
            + "is_active AS \"isActive\", limits::text AS limits "
            + "FROM plans ORDER BY code")
    List<Map<String, Object>> selectAllPlans();

    /** 管理端：单个档位（含停用），不存在返回 null。 */
    @Select("SELECT code, name, price_cents AS \"priceCents\", period, "
            + "is_active AS \"isActive\", limits::text AS limits "
            + "FROM plans WHERE code = #{code}")
    Map<String, Object> selectPlan(@Param("code") String code);

    /** 管理端：覆盖写 plans.limits（plans 表无 updated_at 列，无需维护时间戳）。 */
    @Update("UPDATE plans SET limits = #{limits}::jsonb WHERE code = #{code}")
    int updatePlanLimits(@Param("code") String code, @Param("limits") String limitsJson);

    /** 最近一条有效（含 grace 窗口）订阅；grace 判断在 Java 侧按档位 grace_days 做。 */
    @Select("SELECT plan_code, period_end, status FROM subscriptions "
            + "WHERE user_id = #{userId} AND status IN ('active','grace') "
            + "ORDER BY period_end DESC LIMIT 1")
    SubscriptionRow selectLatestSubscription(@Param("userId") Long userId);

    /** 用户级覆盖中当前有效的行。 */
    @Select("SELECT \"key\" AS key, value FROM user_entitlements "
            + "WHERE user_id = #{userId} AND (expires_at IS NULL OR expires_at > now())")
    List<EntitlementRow> selectActiveOverrides(@Param("userId") Long userId);

    /** 运营加量/开白（按 (user_id, key) 幂等 upsert）。 */
    @Insert("INSERT INTO user_entitlements (user_id, \"key\", value, expires_at, note) "
            + "VALUES (#{userId}, #{key}, #{value}, #{expiresAt}, #{note}) "
            + "ON CONFLICT (user_id, \"key\") DO UPDATE SET value = EXCLUDED.value, "
            + "expires_at = EXCLUDED.expires_at, note = EXCLUDED.note")
    int upsertOverride(@Param("userId") Long userId,
                       @Param("key") String key,
                       @Param("value") long value,
                       @Param("expiresAt") OffsetDateTime expiresAt,
                       @Param("note") String note);

    /** 写入订阅（EXCLUDE 约束保证同一用户时间区间不重叠）。 */
    @Insert("INSERT INTO subscriptions (user_id, plan_code, status, period_start, period_end, source, external_id) "
            + "VALUES (#{userId}, #{planCode}, #{status}, #{periodStart}, #{periodEnd}, #{source}, #{externalId})")
    int insertSubscription(@Param("userId") Long userId,
                           @Param("planCode") String planCode,
                           @Param("status") String status,
                           @Param("periodStart") OffsetDateTime periodStart,
                           @Param("periodEnd") OffsetDateTime periodEnd,
                           @Param("source") String source,
                           @Param("externalId") String externalId);

    /**
     * 新订阅生效前，把旧的 active/grace 订阅置为 expired，并把区间截断到 now()。
     *
     * <p>task-109：V22 起 no_overlap 只约束 active 订阅，但截断 period_end 能让历史数据
     * 语义更干净（到期即止），也兼容 V22 生效前的迁移窗口。</p>
     */
    @Update("UPDATE subscriptions SET status = 'expired', "
            + "period_end = LEAST(period_end, now()) "
            + "WHERE user_id = #{userId} AND status IN ('active','grace')")
    int expireActiveSubscriptions(@Param("userId") Long userId);

    /**
     * task-109：显式写 users.plan_code / plan_expires_at。
     *
     * <p>必须显式 UPDATE：MyBatis-Plus {@code updateById} 默认 FieldStrategy.NOT_NULL，
     * 调回 free 时 {@code setPlanExpiresAt(null)} 会被跳过，导致「免费版仍带到期时间」。
     * {@code jdbcType} 显式声明以允许 NULL 绑定。</p>
     */
    @Update("UPDATE users SET plan_code = #{planCode}, "
            + "plan_expires_at = #{expiresAt,jdbcType=TIMESTAMP_WITH_TIMEZONE}, "
            + "updated_at = now() WHERE id = #{userId}")
    int updateUserPlan(@Param("userId") Long userId,
                       @Param("planCode") String planCode,
                       @Param("expiresAt") OffsetDateTime expiresAt);

    /** 计量原子累加（**只写下行**；上传不计量，relay_bytes_up 保持 0）。 */
    @Insert("INSERT INTO usage_counters (user_id, period, relay_bytes_down, updated_at) "
            + "VALUES (#{userId}, #{period}, #{bytes}, now()) "
            + "ON CONFLICT (user_id, period) DO UPDATE SET "
            + "relay_bytes_down = usage_counters.relay_bytes_down + EXCLUDED.relay_bytes_down, updated_at = now()")
    int addDownloadBytes(@Param("userId") Long userId,
                         @Param("period") String period,
                         @Param("bytes") long bytes);

    @Select("SELECT COALESCE(relay_bytes_down, 0) FROM usage_counters WHERE user_id = #{userId} AND period = #{period}")
    Long selectDownloadBytes(@Param("userId") Long userId, @Param("period") String period);

    /** 兑换成功写订单：provider_txn_id = code，唯一约束保证幂等。 */
    @Insert("INSERT INTO orders (user_id, plan_code, amount_cents, provider, provider_txn_id, status) "
            + "VALUES (#{userId}, #{planCode}, 0, 'redeem', #{txnId}, 'paid')")
    int insertRedeemOrder(@Param("userId") Long userId,
                          @Param("planCode") String planCode,
                          @Param("txnId") String txnId);

    /** 兑换码原子占用：仅 pending 且未过期可成功，影响行数 0 即已被用/失效。 */
    @Update("UPDATE redeem_codes SET status = 'used', used_by = #{userId}, used_at = now() "
            + "WHERE code = #{code} AND status = 'pending' AND (expires_at IS NULL OR expires_at > now())")
    int markRedeemCodeUsed(@Param("code") String code, @Param("userId") Long userId);

    /** 管理端操作审计。 */
    @Insert("INSERT INTO admin_operation_logs (admin_user_id, action, target_user_id, detail) "
            + "VALUES (#{adminUserId}, #{action}, #{targetUserId}, #{detail})")
    int insertOperationLog(@Param("adminUserId") Long adminUserId,
                           @Param("action") String action,
                           @Param("targetUserId") Long targetUserId,
                           @Param("detail") String detail);
}
