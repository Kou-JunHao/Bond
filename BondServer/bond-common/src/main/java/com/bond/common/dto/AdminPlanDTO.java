package com.bond.common.dto;

import java.util.Map;

/**
 * 管理端档位视图（task-112）：GET/PUT /api/admin/plans 的响应元素。
 *
 * <p>{@code limits} 是 plans.limits 的原始键值（snake_case 键、JSON 数值类型），
 * 不在此 DTO 里做扁平化或字符串化；{@code code/name/priceCents/period/isActive}
 * 为 camelCase 字段。停用档位（isActive=false）同样返回，运营需要能看到。</p>
 */
public record AdminPlanDTO(
        String code,
        String name,
        Integer priceCents,
        String period,
        Boolean isActive,
        Map<String, Object> limits) {
}
