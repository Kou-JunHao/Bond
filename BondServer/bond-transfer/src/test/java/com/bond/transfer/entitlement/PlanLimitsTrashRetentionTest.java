package com.bond.transfer.entitlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * task-110：{@code plans.limits.trash_retention_days} 的解析与兜底。
 *
 * <p>档位约定：free=7 / plus=30 / team=90（V23 迁移写入）；0=不限期；
 * 缺键 / 非整数 / 负数 → 回落 7 天并 WARN。</p>
 */
class PlanLimitsTrashRetentionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String json(Integer trashDays) {
        String entry = trashDays == null ? "" : ",\"trash_retention_days\":" + trashDays;
        return "{\"relay_bytes_month\":10737418240,\"storage_bytes\":5368709120,"
                + "\"max_file_size\":2147483648,\"concurrent_transfers\":2,"
                + "\"max_workspaces\":1,\"max_workspace_members\":3,"
                + "\"relay_down_bytes_per_sec\":5242880,\"share_expires_days\":7,"
                + "\"grace_days\":3,\"share_max_downloads\":30,\"share_max_links\":5"
                + entry + "}";
    }

    @Test
    @DisplayName("档位三档：free=7 / plus=30 / team=90 按 JSON 原值解析")
    void parsesTierValues() {
        assertEquals(7, PlanLimits.fromJson(json(7), MAPPER).trashRetentionDays());
        assertEquals(30, PlanLimits.fromJson(json(30), MAPPER).trashRetentionDays());
        assertEquals(90, PlanLimits.fromJson(json(90), MAPPER).trashRetentionDays());
    }

    @Test
    @DisplayName("缺键 → 回落 7 天（free 档语义），不影响其它字段")
    void missingKeyFallsBackToSeven() {
        PlanLimits limits = PlanLimits.fromJson(json(null), MAPPER);
        assertEquals(7, limits.trashRetentionDays());
        assertEquals(5L * 1024 * 1024 * 1024, limits.storageBytes());
        assertEquals(30, limits.shareMaxDownloads());
    }

    @Test
    @DisplayName("非法值（字符串/负数）→ 回落 7 天，不抛异常")
    void invalidValueFallsBackToSeven() {
        String text = json(null).replace("\"grace_days\":3", "\"grace_days\":3,\"trash_retention_days\":\"abc\"");
        assertEquals(7, PlanLimits.fromJson(text, MAPPER).trashRetentionDays());

        assertEquals(7, PlanLimits.fromJson(json(-5), MAPPER).trashRetentionDays());
    }

    @Test
    @DisplayName("0 = 不限期，合法且不被兜底改写")
    void zeroMeansUnlimited() {
        assertEquals(0, PlanLimits.fromJson(json(0), MAPPER).trashRetentionDays());
    }

    @Test
    @DisplayName("FREE_DEFAULTS 为 7 天；用户级覆盖可改、负数覆盖被夹到 0")
    void defaultsAndOverride() {
        assertEquals(7, PlanLimits.FREE_DEFAULTS.trashRetentionDays());
        PlanLimits limits = PlanLimits.fromJson(json(30), MAPPER);
        assertEquals(45, limits.applyOverride("trash_retention_days", 45).trashRetentionDays());
        assertEquals(0, limits.applyOverride("trash_retention_days", -9).trashRetentionDays());
    }
}
