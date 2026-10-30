package com.bond.transfer.entitlement;

import lombok.Data;

import java.time.OffsetDateTime;

/** 订阅查询行（mappers/SubscriptionMapper）。 */
@Data
public class SubscriptionRow {
    private String planCode;
    private OffsetDateTime periodEnd;
    private String status;
}
