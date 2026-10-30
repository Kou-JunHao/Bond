package com.bond.transfer.entitlement;

import java.time.OffsetDateTime;

/**
 * 某用户当前生效的权益：档位 + 实际限制 + 到期时间。
 */
public record Entitlements(String planCode, PlanLimits limits, OffsetDateTime expiresAt) {

    public static Entitlements free() {
        return new Entitlements("free", PlanLimits.FREE_DEFAULTS, null);
    }
}
