package com.bond.transfer.controller;

import com.bond.common.dto.AdminPlanDTO;
import com.bond.common.dto.Result;
import com.bond.transfer.entitlement.PlanAdminService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 档位参数运营后台（task-112）：查询三档位 + partial update limits。
 *
 * <p>管理员守卫由 {@code AdminInterceptor}（/api/admin/**）统一负责，
 * 身份来自网关 HMAC 签名后的 X-User-Id；本控制器不做二次鉴权。</p>
 *
 * <p>PUT body 为**扁平** limits 键（如 {@code {"trash_retention_days":1}}），
 * 不要套 {@code {"limits":{...}}} 外壳。{@code null} 表示删除该键。</p>
 */
@RestController
@RequestMapping("/api/admin/plans")
@RequiredArgsConstructor
public class AdminPlanController {

    private final PlanAdminService planAdminService;

    @GetMapping
    public Result<List<AdminPlanDTO>> list() {
        return Result.ok(planAdminService.list());
    }

    @PutMapping("/{code}")
    public Result<AdminPlanDTO> update(@PathVariable String code,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       @RequestHeader("X-User-Id") Long actorId,
                                       HttpServletRequest request) {
        return Result.ok(planAdminService.update(code, body, actorId, clientIp(request)));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
