package com.bond.transfer.controller;

import com.bond.common.dto.EntitlementMeDTO;
import com.bond.common.dto.Result;
import com.bond.transfer.entitlement.EntitlementMeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户权益/用量（客户端「用量卡片 + 限额提示」的唯一数据源，task-29）。
 *
 * <p>只读接口；鉴权由网关完成——{@code /api/entitlements/**} 不在白名单内，
 * 网关 JWT 校验通过后注入 {@code X-User-Id}，与 {@code /api/files/**} 一致。</p>
 */
@RestController
@RequestMapping("/api/entitlements")
@RequiredArgsConstructor
public class EntitlementController {

    private final EntitlementMeService entitlementMeService;

    /** 当前档位 + 本月用量 + 限额 + 限速/空间已满标记。 */
    @GetMapping("/me")
    public Result<EntitlementMeDTO> me(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(entitlementMeService.me(userId));
    }
}
