package com.bond.transfer.entitlement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 档位权益（plans.limits JSONB 的 Java 形态）。
 *
 * <p>只包含本期真正参与判定的权益；文档/分享等占位键保留在 JSON 里但不解析。</p>
 *
 * @param relayBytesMonth        每月高速流量（**只算下行**；0 表示不限）
 * @param storageBytes           空间额度（个人空间 / 团队工作区各按此分配）
 * @param maxFileSize            单文件上限
 * @param concurrentTransfers    并发传输数
 * @param maxWorkspaces          可创建团队工作区数
 * @param maxWorkspaceMembers    每工作区成员上限（由 owner 档位决定）
 * @param relayDownBytesPerSec   正常下行限速（字节/秒；0 表示不限速）
 * @param shareExpiresDays       分享链接默认/最长有效期天数（0 表示付费档可永久，免费档回退 7 天）
 * @param graceDays              档位到期宽限天数
 * @param shareMaxDownloads      永久链接默认下载次数上限（创建时可覆盖，管理端可调）
 * @param shareMaxLinks          单用户活跃分享链接数上限
 * @param trashRetentionDays     回收站保留天数（free 7 / plus 30 / team 90；0 = 不限期；
 *                               缺键或非法值回落 {@value #DEFAULT_TRASH_RETENTION_DAYS} 天并 WARN）
 */
public record PlanLimits(
        long relayBytesMonth,
        long storageBytes,
        long maxFileSize,
        int concurrentTransfers,
        int maxWorkspaces,
        int maxWorkspaceMembers,
        long relayDownBytesPerSec,
        int shareExpiresDays,
        int graceDays,
        int shareMaxDownloads,
        int shareMaxLinks,
        int trashRetentionDays) {

    private static final Logger log = LoggerFactory.getLogger(PlanLimits.class);

    /** 回收站保留天数缺键/非法时的回落值（free 档语义）。 */
    public static final int DEFAULT_TRASH_RETENTION_DAYS = 7;

    /** 兜底：plans 表缺失/损坏时按免费档执行，保证服务可用。 */
    public static final PlanLimits FREE_DEFAULTS = new PlanLimits(
            10L * 1024 * 1024 * 1024,
            5L * 1024 * 1024 * 1024,
            2L * 1024 * 1024 * 1024,
            2, 1, 3,
            5L * 1024 * 1024,
            7, 3,
            30, 5,
            DEFAULT_TRASH_RETENTION_DAYS);

    /** 解析 plans.limits JSONB；任何缺键回退默认值。 */
    public static PlanLimits fromJson(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return FREE_DEFAULTS;
        }
        try {
            JsonNode node = mapper.readTree(json);
            return new PlanLimits(
                    longField(node, "relay_bytes_month", FREE_DEFAULTS.relayBytesMonth),
                    longField(node, "storage_bytes", FREE_DEFAULTS.storageBytes),
                    longField(node, "max_file_size", FREE_DEFAULTS.maxFileSize),
                    intField(node, "concurrent_transfers", FREE_DEFAULTS.concurrentTransfers),
                    intField(node, "max_workspaces", FREE_DEFAULTS.maxWorkspaces),
                    intField(node, "max_workspace_members", FREE_DEFAULTS.maxWorkspaceMembers),
                    longField(node, "relay_down_bytes_per_sec", FREE_DEFAULTS.relayDownBytesPerSec),
                    intField(node, "share_expires_days", FREE_DEFAULTS.shareExpiresDays),
                    intField(node, "grace_days", FREE_DEFAULTS.graceDays),
                    intField(node, "share_max_downloads", FREE_DEFAULTS.shareMaxDownloads),
                    intField(node, "share_max_links", FREE_DEFAULTS.shareMaxLinks),
                    trashRetentionDaysField(node));
        } catch (IOException e) {
            throw new UncheckedIOException("invalid plans.limits json", e);
        }
    }

    /** 应用单条用户级覆盖（user_entitlements.key 与 limits 键同名）。 */
    public PlanLimits applyOverride(String key, long value) {
        if (key == null) {
            return this;
        }
        return switch (key) {
            case "relay_bytes_month" -> new PlanLimits(value, storageBytes, maxFileSize,
                    concurrentTransfers, maxWorkspaces, maxWorkspaceMembers, relayDownBytesPerSec,
                    shareExpiresDays, graceDays, shareMaxDownloads, shareMaxLinks, trashRetentionDays);
            case "storage_bytes" -> new PlanLimits(relayBytesMonth, value, maxFileSize,
                    concurrentTransfers, maxWorkspaces, maxWorkspaceMembers, relayDownBytesPerSec,
                    shareExpiresDays, graceDays, shareMaxDownloads, shareMaxLinks, trashRetentionDays);
            case "max_file_size" -> new PlanLimits(relayBytesMonth, storageBytes, value,
                    concurrentTransfers, maxWorkspaces, maxWorkspaceMembers, relayDownBytesPerSec,
                    shareExpiresDays, graceDays, shareMaxDownloads, shareMaxLinks, trashRetentionDays);
            case "concurrent_transfers" -> new PlanLimits(relayBytesMonth, storageBytes, maxFileSize,
                    (int) value, maxWorkspaces, maxWorkspaceMembers, relayDownBytesPerSec,
                    shareExpiresDays, graceDays, shareMaxDownloads, shareMaxLinks, trashRetentionDays);
            case "max_workspaces" -> new PlanLimits(relayBytesMonth, storageBytes, maxFileSize,
                    concurrentTransfers, (int) value, maxWorkspaceMembers, relayDownBytesPerSec,
                    shareExpiresDays, graceDays, shareMaxDownloads, shareMaxLinks, trashRetentionDays);
            case "max_workspace_members" -> new PlanLimits(relayBytesMonth, storageBytes, maxFileSize,
                    concurrentTransfers, maxWorkspaces, (int) value, relayDownBytesPerSec,
                    shareExpiresDays, graceDays, shareMaxDownloads, shareMaxLinks, trashRetentionDays);
            case "relay_down_bytes_per_sec" -> new PlanLimits(relayBytesMonth, storageBytes, maxFileSize,
                    concurrentTransfers, maxWorkspaces, maxWorkspaceMembers, value,
                    shareExpiresDays, graceDays, shareMaxDownloads, shareMaxLinks, trashRetentionDays);
            case "share_expires_days" -> new PlanLimits(relayBytesMonth, storageBytes, maxFileSize,
                    concurrentTransfers, maxWorkspaces, maxWorkspaceMembers, relayDownBytesPerSec,
                    (int) value, graceDays, shareMaxDownloads, shareMaxLinks, trashRetentionDays);
            case "grace_days" -> new PlanLimits(relayBytesMonth, storageBytes, maxFileSize,
                    concurrentTransfers, maxWorkspaces, maxWorkspaceMembers, relayDownBytesPerSec,
                    shareExpiresDays, (int) value, shareMaxDownloads, shareMaxLinks, trashRetentionDays);
            case "share_max_downloads" -> new PlanLimits(relayBytesMonth, storageBytes, maxFileSize,
                    concurrentTransfers, maxWorkspaces, maxWorkspaceMembers, relayDownBytesPerSec,
                    shareExpiresDays, graceDays, (int) value, shareMaxLinks, trashRetentionDays);
            case "share_max_links" -> new PlanLimits(relayBytesMonth, storageBytes, maxFileSize,
                    concurrentTransfers, maxWorkspaces, maxWorkspaceMembers, relayDownBytesPerSec,
                    shareExpiresDays, graceDays, shareMaxDownloads, (int) value, trashRetentionDays);
            case "trash_retention_days" -> new PlanLimits(relayBytesMonth, storageBytes, maxFileSize,
                    concurrentTransfers, maxWorkspaces, maxWorkspaceMembers, relayDownBytesPerSec,
                    shareExpiresDays, graceDays, shareMaxDownloads, shareMaxLinks,
                    (int) Math.max(0L, value));
            default -> this;
        };
    }

    private static long longField(JsonNode node, String name, long defaultValue) {
        JsonNode value = node.get(name);
        return value == null || !value.isNumber() ? defaultValue : value.asLong();
    }

    private static int intField(JsonNode node, String name, int defaultValue) {
        JsonNode value = node.get(name);
        return value == null || !value.isNumber() ? defaultValue : value.asInt();
    }

    /**
     * 回收站保留天数（task-110）：缺键 / 非整数 / 负数 → 回落 7 天并 WARN；0 合法（不限期）。
     */
    private static int trashRetentionDaysField(JsonNode node) {
        JsonNode value = node.get("trash_retention_days");
        if (value == null) {
            log.warn("plans.limits 缺少 trash_retention_days，按默认 {} 天处理", DEFAULT_TRASH_RETENTION_DAYS);
            return DEFAULT_TRASH_RETENTION_DAYS;
        }
        if (!value.isIntegralNumber()) {
            log.warn("plans.limits.trash_retention_days 非法（{}），按默认 {} 天处理",
                    value, DEFAULT_TRASH_RETENTION_DAYS);
            return DEFAULT_TRASH_RETENTION_DAYS;
        }
        int days = value.asInt();
        if (days < 0) {
            log.warn("plans.limits.trash_retention_days 为负数（{}），按默认 {} 天处理",
                    days, DEFAULT_TRASH_RETENTION_DAYS);
            return DEFAULT_TRASH_RETENTION_DAYS;
        }
        return days;
    }
}
