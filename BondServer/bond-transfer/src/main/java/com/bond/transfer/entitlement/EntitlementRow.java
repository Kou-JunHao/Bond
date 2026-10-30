package com.bond.transfer.entitlement;

import lombok.Data;

/** 用户级权益覆盖行（mappers/UserEntitlementMapper）。 */
@Data
public class EntitlementRow {
    private String key;
    private Long value;
}
