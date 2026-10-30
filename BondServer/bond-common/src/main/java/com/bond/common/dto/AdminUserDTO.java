package com.bond.common.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class AdminUserDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String username;
    private String nickname;
    private String avatarUrl;
    private Integer status;
    private Boolean isAdmin;
    /** 邮箱（V14，task-57）：未绑定时为 null。 */
    private String email;
    /** 是否已绑定邮箱（= email != null）。 */
    private Boolean emailVerified;
    /** 生效档位 code（users.plan_code，空则 "free"）；task-102。 */
    private String planCode;
    /** 生效档位展示名（plans.name，经 PlanCatalog 本地缓存；查不到回退 code）。 */
    private String planName;
    /** 档位到期时间（epoch millis，可为 null：free 或无到期）。 */
    private Long planExpiresAt;
    private Long createdAt;
    private long deviceCount;
    private long transferCount;
}
