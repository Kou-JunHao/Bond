package com.bond.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 当前登录用户的权益与本月用量（{@code GET /api/entitlements/me}，task-29）。
 *
 * <p>数据来源与执行侧<b>同源</b>：</p>
 * <ul>
 *   <li>{@link #limits} 来自 {@code EntitlementResolver}（套餐默认值 ← 用户级覆盖），
 *       展示的额度就是后端实际执行/校验的额度；</li>
 *   <li>{@link #usage} 中的 {@code relayBytesDown} 来自 {@code usage_counters} 当前周期，
 *       {@code storageBytes}/{@code fileCount} 与 {@code /api/files/quota} 同源（个人空间）。</li>
 * </ul>
 *
 * <p>团队工作区各自的配额不在这里（由 {@code /api/workspaces} 返回，职责分离）。</p>
 */
@Data
public class EntitlementMeDTO {

    /** 生效档位：free / plus / team（档位过期超过宽限期后由 resolver 回落为 free）。 */
    private String planCode;
    /** 档位展示名（plans.name；查不到时回退为 planCode）。 */
    private String planName;
    /** active=当前生效；grace=已到期但仍在宽限期内（保留档位额度）。 */
    private String status;
    /** 计费周期 YYYY-MM（服务器时区）。 */
    private String period;
    /** 档位到期时间；free/永久档为 null。 */
    private OffsetDateTime expiresAt;

    private Limits limits;
    private Usage usage;

    /**
     * 本月下行流量是否已用尽（用尽后服务端降速，不拒绝下载）。
     *
     * <p>字段名故意不叫 {@code isThrottled}：Jackson 对 {@code isXxx()} getter 会推导出
     * {@code xxx} 属性，若字段也叫 {@code isThrottled} 会同时输出 {@code throttled} 与
     * {@code isThrottled} 两个键。这里用普通字段 + 显式 getter，保证 JSON 只有
     * {@code isThrottled} / {@code isStorageFull}。</p>
     */
    @Getter(AccessLevel.NONE)
    private boolean throttled;
    /** 个人空间是否已满（达到上限后上传会被 413 拒绝）。 */
    @Getter(AccessLevel.NONE)
    private boolean storageFull;

    @JsonProperty("isThrottled")
    public boolean isThrottled() {
        return throttled;
    }

    @JsonProperty("isStorageFull")
    public boolean isStorageFull() {
        return storageFull;
    }

    /**
     * 生效限额。单位：字节 / 个 / 字节每秒 / 天。
     *
     * <p>{@code relayBytesMonth=0} 或 {@code relayDownBytesPerSec=0} 表示不限。</p>
     */
    @Data
    public static class Limits {
        /** 每月高速流量额度（只计下行）；0=不限。 */
        private long relayBytesMonth;
        /** 个人空间额度。 */
        private long storageBytes;
        /** 单文件上限。 */
        private long maxFileSize;
        /** 可创建的团队工作区数。 */
        private int maxWorkspaces;
        /** 每个工作区成员上限。 */
        private int maxWorkspaceMembers;
        /** 并发传输数。 */
        private int concurrentTransfers;
        /** 正常下行限速（字节/秒）；0=不限速。 */
        private long relayDownBytesPerSec;
        /** 档位到期宽限天数。 */
        private int graceDays;
        /** 分享链接默认/最长有效期（天；0=不限期）。 */
        private int shareExpiresDays;
        /** 永久分享链接默认下载次数上限（创建时可覆盖）；0=不限。 */
        private int shareMaxDownloads;
        /** 单用户活跃分享链接数上限。 */
        private int shareMaxLinks;
        /** 回收站保留天数（free 7 / plus 30 / team 90；0=不限期），客户端据此展示「按当前档位保留 N 天」。 */
        private int trashRetentionDays;
    }

    /**
     * 本月用量（个人空间口径）。剩余量在额度为 0（不限）时返回 0，
     * 客户端应优先看 {@code limits.*==0} 判定“不限”。
     */
    @Data
    public static class Usage {
        /** 本月已下行的字节数。 */
        private long relayBytesDown;
        /** 个人空间已用字节（逻辑占用，按 file_size 累加）。 */
        private long storageBytes;
        /** 个人空间非目录文件数。 */
        private long fileCount;
        /** 本月流量剩余（>=0）。 */
        private long relayBytesRemaining;
        /** 个人空间剩余（>=0）。 */
        private long storageBytesRemaining;
    }
}
