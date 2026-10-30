package com.bond.transfer.ops;

import com.bond.common.dto.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运维任务观测接口（task-74 C）。
 *
 * <p>路径在 {@code /api/admin/**} 下，沿用既有 {@code AdminInterceptor} 鉴权与
 * {@code AdminAudit}（只读接口不写审计）；不需要新增网关路由。</p>
 */
@RestController
@RequestMapping("/api/admin/ops")
@RequiredArgsConstructor
public class OpsController {

    private final OpsJobRegistry opsJobRegistry;

    /** 最近一次运行时间 / 处理数 / 错误数；未跑过的任务不出现。 */
    @GetMapping("/jobs")
    public Result<List<OpsJobRegistry.JobSnapshot>> jobs() {
        return Result.ok(opsJobRegistry.snapshot());
    }
}
