package com.bond.transfer.entitlement;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 兑换码（V3）。兑换成功后写 orders(provider='redeem', provider_txn_id=code)。 */
@Data
@TableName("redeem_codes")
public class RedeemCode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String planCode;
    private Integer durationDays;
    private OffsetDateTime expiresAt;
    private String status;
    private Long usedBy;
    private OffsetDateTime usedAt;
    private Long createdBy;
    private OffsetDateTime createdAt;
}
