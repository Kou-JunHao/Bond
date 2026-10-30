package com.bond.transfer.controller;

import com.bond.common.dto.Result;
import com.bond.transfer.service.RedeemService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户兑换码兑换（本期唯一的付费/加量入口，不接支付渠道）。
 *
 * <p>鉴权：网关注入 X-User-Id（需要登录态）。</p>
 */
@RestController
@RequestMapping("/api/redeem")
@RequiredArgsConstructor
public class RedeemController {

    private final RedeemService redeemService;

    @PostMapping
    public Result<Map<String, Object>> redeem(@RequestHeader("X-User-Id") Long userId,
                                              @RequestBody RedeemRequest req) {
        return Result.ok(redeemService.redeem(userId, req.getCode()));
    }

    @Data
    static class RedeemRequest {
        private String code;
    }
}
