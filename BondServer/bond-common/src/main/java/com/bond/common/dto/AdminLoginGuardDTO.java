package com.bond.common.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理端登录防护策略（task-144，`GET/PUT /api/admin/login-guard`）。
 *
 * <p>PUT 请求体为扁平字段：{@code enabled / failWindowSeconds / failThresholdIp /
 * failThresholdUser / banSeconds / ipAllowlist(数组或逗号字符串)}，所有字段可选（partial update）。</p>
 */
@Data
public class AdminLoginGuardDTO {

    private boolean enabled;
    private int failWindowSeconds;
    private int failThresholdIp;
    private int failThresholdUser;
    private int banSeconds;
    private List<String> ipAllowlist = new ArrayList<>();
}
