package com.bond.transfer.entitlement;

import com.bond.transfer.mapper.EntitlementMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 档位展示名目录：启动时把 plans.code → plans.name 一次性载入内存，之后按固定周期刷新。
 *
 * <p>/api/entitlements/me 每秒可能被轮询，若每次请求都查 plans 表等于把选档位的开销
 * 乘到所有用户上。这里用一张极小的只读目录把该查询从请求路径上摘掉。</p>
 *
 * <p>刷新：启动 + 每 5 分钟 + 管理端改档后主动 {@link #refresh()}（plan 名称变更不频繁，
 * 但管理员改名后应能很快生效）。刷新失败保留旧目录并打 WARN，不影响请求。</p>
 */
@Component
@RequiredArgsConstructor
public class PlanCatalog {

    private static final Logger log = LoggerFactory.getLogger(PlanCatalog.class);

    private final EntitlementMapper entitlementMapper;
    private final ConcurrentHashMap<String, String> names = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refresh();
    }

    /** 重新加载 code → name；失败时保留旧快照（启动时失败则目录为空，调用方回退 planCode）。 */
    @Scheduled(fixedDelay = 300_000L)
    public void refresh() {
        try {
            List<Map<String, Object>> rows = entitlementMapper.selectPlanNames();
            if (rows == null) {
                return;
            }
            ConcurrentHashMap<String, String> fresh = new ConcurrentHashMap<>();
            for (Map<String, Object> row : rows) {
                Object code = row.get("code");
                Object name = row.get("name");
                if (code != null) {
                    fresh.put(code.toString(), name == null ? code.toString() : name.toString());
                }
            }
            names.clear();
            names.putAll(fresh);
            log.info("PlanCatalog loaded {} plan name(s)", names.size());
        } catch (RuntimeException e) {
            log.warn("PlanCatalog refresh failed, keep previous snapshot: {}", e.getMessage());
        }
    }

    /** code 的展示名；未知档位回退 code 本身（与旧实现的 NULL 回退语义一致）。 */
    public String name(String planCode) {
        if (planCode == null) {
            return null;
        }
        String name = names.get(planCode);
        return name == null ? planCode : name;
    }
}
