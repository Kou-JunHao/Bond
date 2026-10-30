package com.bond.transfer.service;

import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.entitlement.EntitlementMeService;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.RedeemCode;
import com.bond.transfer.mapper.EntitlementMapper;
import com.bond.transfer.mapper.RedeemCodeMapper;
import com.bond.transfer.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 用户兑换码兑换（本期唯一的"付费"入口；不接支付）。
 *
 * <p>幂等：redeem_codes 的原子状态更新 + orders.provider_txn_id 唯一约束双保险，
 * 同一码只能成功兑换一次。</p>
 */
@Service
@RequiredArgsConstructor
public class RedeemService {

    private static final Logger log = LoggerFactory.getLogger(RedeemService.class);

    private final EntitlementMapper entitlementMapper;
    private final RedeemCodeMapper redeemCodeMapper;
    private final UserMapper userMapper;
    private final EntitlementResolver entitlementResolver;
    private final EntitlementMeService entitlementMeService;
    private final QuotaService quotaService;

    @Transactional
    public Map<String, Object> redeem(Long userId, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new BusinessException(400, "兑换码不能为空");
        }
        String code = rawCode.trim();
        // 原子占用：仅 pending 且未过期能成功
        if (entitlementMapper.markRedeemCodeUsed(code, userId) == 0) {
            throw new BusinessException(400, "兑换码无效、已使用或已过期");
        }
        RedeemCode row = redeemCodeMapper.selectOne(
                new LambdaQueryWrapper<RedeemCode>().eq(RedeemCode::getCode, code));
        if (row == null) {
            throw new BusinessException(400, "兑换码无效");
        }
        // 订单作为幂等/审计记录（唯一键 provider_txn_id=code）
        entitlementMapper.insertRedeemOrder(userId, row.getPlanCode(), code);

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime base = user.getPlanExpiresAt() != null && user.getPlanExpiresAt().isAfter(now)
                ? user.getPlanExpiresAt() : now;
        OffsetDateTime newExpiry = base.plusDays(row.getDurationDays());
        user.setPlanCode(row.getPlanCode());
        user.setPlanExpiresAt(newExpiry);
        userMapper.updateById(user);

        entitlementMapper.expireActiveSubscriptions(userId);
        entitlementMapper.insertSubscription(userId, row.getPlanCode(), "active", now, newExpiry,
                "redeem", code);
        quotaService.refreshLimitsForUser(userId);
        entitlementResolver.invalidate(userId);
        // 兑换后档位立刻变化，权益接口缓存必须同步失效
        entitlementMeService.invalidate(userId);

        log.info("Redeem succeeded: userId={}, plan={}, expiresAt={}", userId, row.getPlanCode(), newExpiry);
        return Map.of(
                "planCode", row.getPlanCode(),
                "expiresAt", newExpiry.toString(),
                "durationDays", row.getDurationDays());
    }
}
