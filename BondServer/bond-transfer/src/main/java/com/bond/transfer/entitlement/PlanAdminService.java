package com.bond.transfer.entitlement;

import com.bond.common.dto.AdminPlanDTO;
import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.EntitlementMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.service.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 档位参数运营后台（task-112）：{@code GET/PUT /api/admin/plans}。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>查询返回三个档位全部（含停用），limits 保留 JSON 原始数值类型；</li>
 *   <li>写入是 **partial update**：body 为扁平的 limits 键（不套 {@code limits} 外壳），
 *       只改提供的键；{@code null} 表示删除该键；</li>
 *   <li>校验：12 个白名单键 + 整数类型 + 边界；任何非法在写库前失败（整体事务）；</li>
 *   <li>写成功后立即 {@link EntitlementResolver#invalidatePlan(String)}，下一次删除/配额判断
 *       即用新值，不用等 TTL；同时写 {@code admin_operation_logs}（action=update-plan，
 *       detail 带 before/after diff）。</li>
 * </ul>
 *
 * <p>JSON 键与 plans.limits 一致（snake_case）；接口字段为 camelCase。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PlanAdminService {

    /** 回收站保留天数键（task-110/112），单独标记便于测试与文档引用。 */
    public static final String KEY_TRASH_RETENTION_DAYS = "trash_retention_days";
    private static final int MAX_TRASH_RETENTION_DAYS = 3650;
    private static final String ACTION_UPDATE_PLAN = "update-plan";
    private static final String TARGET_TYPE_PLAN = "plan";

    private static final Map<String, KeyRule> RULES = buildRules();

    private final EntitlementMapper entitlementMapper;
    private final EntitlementResolver entitlementResolver;
    private final AuditService auditService;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    private static Map<String, KeyRule> buildRules() {
        Map<String, KeyRule> rules = new LinkedHashMap<>();
        // 存储/流量/文件大小/数量类：0 没有业务含义，必须 > 0
        rules.put("storage_bytes", new KeyRule("大于 0 的整数（字节）", 1, Long.MAX_VALUE));
        rules.put("relay_bytes_month", new KeyRule("大于 0 的整数（字节）", 1, Long.MAX_VALUE));
        rules.put("max_file_size", new KeyRule("大于 0 的整数（字节）", 1, Long.MAX_VALUE));
        rules.put("max_workspaces", new KeyRule("大于 0 的整数", 1, Integer.MAX_VALUE));
        rules.put("max_workspace_members", new KeyRule("大于 0 的整数", 1, Integer.MAX_VALUE));
        rules.put("concurrent_transfers", new KeyRule("大于 0 的整数", 1, Integer.MAX_VALUE));
        // 以下键 0 有明确「不限」语义：0=不限速 / 永久有效 / 不限次数 / 无宽限
        rules.put("relay_down_bytes_per_sec", new KeyRule("非负整数，0=不限速", 0, Long.MAX_VALUE));
        rules.put("share_expires_days", new KeyRule("非负整数，0=永久有效", 0, Integer.MAX_VALUE));
        rules.put("grace_days", new KeyRule("非负整数", 0, 365));
        rules.put("share_max_downloads", new KeyRule("非负整数，0=不限次数", 0, Integer.MAX_VALUE));
        rules.put("share_max_links", new KeyRule("非负整数，0=不限", 0, Integer.MAX_VALUE));
        rules.put(KEY_TRASH_RETENTION_DAYS,
                new KeyRule("0-" + MAX_TRASH_RETENTION_DAYS + " 的整数，0=不限期", 0, MAX_TRASH_RETENTION_DAYS));
        return Collections.unmodifiableMap(rules);
    }

    /** 三个档位全部（含 isActive=false），按 code 升序。 */
    public List<AdminPlanDTO> list() {
        return entitlementMapper.selectAllPlans().stream()
                .map(row -> toDto(row, parseLimits(asString(row.get("limits")))))
                .toList();
    }

    /**
     * partial update 某档位的 limits，并立即失效该档位缓存。
     *
     * @param code    档位 code（free/plus/team，忽略大小写与首尾空格）
     * @param patch   扁平 limits 键：{@code {"trash_retention_days": 1}}；{@code null} 值表示删除该键
     * @param actorId 管理员用户 id（网关签名 X-User-Id）
     * @param ip      调用方 IP（审计用）
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminPlanDTO update(String code, Map<String, Object> patch, Long actorId, String ip) {
        String planCode = normalizeCode(code);
        Map<String, Object> row = entitlementMapper.selectPlan(planCode);
        if (row == null) {
            throw new BusinessException(404, "档位不存在：" + planCode);
        }

        Map<String, Object> before = parseLimits(asString(row.get("limits")));
        Map<String, Object> after = new LinkedHashMap<>(before);
        Map<String, Object> diffBefore = new LinkedHashMap<>();
        Map<String, Object> diffAfter = new LinkedHashMap<>();

        if (patch != null) {
            for (Map.Entry<String, Object> entry : patch.entrySet()) {
                String key = entry.getKey();
                KeyRule rule = RULES.get(key);
                if (rule == null) {
                    throw new BusinessException(400, "不支持的 limits 键：" + key);
                }
                if (entry.getValue() == null) {
                    if (before.containsKey(key)) {
                        after.remove(key);
                        diffBefore.put(key, before.get(key));
                        diffAfter.put(key, null);
                    }
                    continue;
                }
                long value = requireInteger(key, rule, entry.getValue());
                after.put(key, value);
                if (!sameValue(before.get(key), value)) {
                    diffBefore.put(key, before.get(key));
                    diffAfter.put(key, value);
                }
            }
        }

        if (diffAfter.isEmpty()) {
            // 幂等：空 patch / 值未变化，不写库也不审计（仍是 200 返回当前档位）
            return toDto(row, after);
        }

        entitlementMapper.updatePlanLimits(planCode, writeJson(after));
        // 不依赖 TTL：立刻清掉该档位用户的缓存，下一次 resolve 就会重新读 plans
        entitlementResolver.invalidatePlan(planCode);
        auditService.record(actorId, displayName(actorId), ACTION_UPDATE_PLAN, TARGET_TYPE_PLAN, null,
                auditDetail(planCode, diffBefore, diffAfter), ip);
        log.info("plan limits updated: code={}, diff={}", planCode,
                writeJson(Map.of("before", diffBefore, "after", diffAfter)));
        return toDto(row, after);
    }

    private static long requireInteger(String key, KeyRule rule, Object raw) {
        if (raw instanceof Integer || raw instanceof Long) {
            long value = ((Number) raw).longValue();
            if (value < rule.min() || value > rule.max()) {
                throw new BusinessException(400,
                        "limits 键 " + key + " 越界：期望" + rule.expected() + "，实际 " + value);
            }
            return value;
        }
        throw new BusinessException(400,
                "limits 键 " + key + " 类型非法：期望" + rule.expected() + "，实际 " + describe(raw));
    }

    /**
     * 数值语义比较：Jackson 解析 JSON 时按大小给出 Integer/Long，不能直接用 {@code Objects.equals}，
     * 否则「值未变化」的 patch 会被误判成 change（导致多写库 + 多审计）。
     */
    private static boolean sameValue(Object before, long after) {
        if (before instanceof Number number) {
            return number.longValue() == after;
        }
        return false;
    }

    private static String describe(Object raw) {
        String type = raw == null ? "null" : raw.getClass().getSimpleName();
        return raw + "（" + type + "）";
    }

    private static String normalizeCode(String code) {
        String value = code == null ? "" : code.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty()) {
            throw new BusinessException(400, "档位 code 不能为空");
        }
        return value;
    }

    private AdminPlanDTO toDto(Map<String, Object> row, Map<String, Object> limits) {
        Integer priceCents = row.get("priceCents") instanceof Number number ? number.intValue() : null;
        Boolean isActive = row.get("isActive") instanceof Boolean flag ? flag : null;
        return new AdminPlanDTO(
                asString(row.get("code")),
                asString(row.get("name")),
                priceCents,
                asString(row.get("period")),
                isActive,
                limits);
    }

    private Map<String, Object> parseLimits(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("plans.limits 不是合法 JSON", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 limits 失败", e);
        }
    }

    private String auditDetail(String code, Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("before", before);
        detail.put("after", after);
        return writeJson(detail);
    }

    private String displayName(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            User user = userMapper.selectById(userId);
            if (user == null) {
                return null;
            }
            return user.getNickname() != null && !user.getNickname().isBlank()
                    ? user.getNickname() : user.getUsername();
        } catch (RuntimeException e) {
            log.warn("resolve admin display name failed: userId={}, error={}", userId, e.toString());
            return null;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** 单个 limits 键的校验规则（整数型；未来若有 boolean 键可扩展）。 */
    private record KeyRule(String expected, long min, long max) {
    }
}
