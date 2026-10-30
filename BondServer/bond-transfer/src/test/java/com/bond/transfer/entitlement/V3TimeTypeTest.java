package com.bond.transfer.entitlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 回归防护：V3 新表（TIMESTAMPTZ）对应的 Java 时间字段一律 {@link OffsetDateTime}，
 * 与 V1 老表（TIMESTAMP + LocalDateTime）约定区分开，避免 PG JDBC 映射 500。
 */
class V3TimeTypeTest {

    @Test
    @DisplayName("redeem_codes 的时间列均为 OffsetDateTime")
    void redeemCodeTimeFieldsAreOffsetDateTime() throws Exception {
        for (String name : new String[]{"expiresAt", "usedAt", "createdAt"}) {
            assertEquals(OffsetDateTime.class, RedeemCode.class.getDeclaredField(name).getType(),
                    "redeem_codes." + name + " 必须是 OffsetDateTime（TIMESTAMPTZ 映射）");
        }
    }

    @Test
    @DisplayName("subscriptions.period_end 为 OffsetDateTime")
    void subscriptionPeriodEndIsOffsetDateTime() throws Exception {
        assertEquals(OffsetDateTime.class, SubscriptionRow.class.getDeclaredField("periodEnd").getType());
    }

    @Test
    @DisplayName("Entitlements.expiresAt 为 OffsetDateTime（users.plan_expires_at）")
    void entitlementsExpiresAtIsOffsetDateTime() {
        assertEquals(OffsetDateTime.class, Entitlements.class.getRecordComponents()[2].getType());
    }
}
