package com.bond.transfer.entitlement;

import com.bond.common.dto.EntitlementMeDTO;
import com.bond.common.dto.StorageQuotaDTO;
import com.bond.common.util.TtlCache;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 当前用户权益 + 本月用量查询（task-29，只读）。
 *
 * <p>约束：{@code limits} 必须与执行侧同源，因此这里**只调用**
 * {@link EntitlementResolver#resolveFresh(Long)}（套餐默认值 ← 用户级覆盖 ← 过期回落），
 * 绝不自行读 plans/user_entitlements 拼额度；{@code usage.storageBytes/fileCount} 复用
 * {@link WorkspaceService#getQuota}（与 {@code /api/files/quota} 同源），
 * {@code usage.relayBytesDown} 复用 {@link UsageService#currentMonthDown}（与限速判定同源）。</p>
 *
 * <p>缓存（task-30，Plan A）：响应整体缓存 {@code entitlement.me_cache_seconds} 秒（默认 15s），
 * 用 {@link TtlCache} 做单飞加载 + 硬上限，避免客户端高频轮询直接把 5 条 SQL 放大。
 * 档位/兑换变更时由 {@code EntitlementAdminService}/{@code RedeemService} 主动
 * {@link #invalidate(Long)}；下载计量不失效（≤TTL 内 relayBytesDown 为近似值）。</p>
 */
@Service
public class EntitlementMeService {

    /** 缓存上限：按用户 id 一条 DTO（几十字节），远超实际在线用户数，仅为防泄漏。 */
    private static final int CACHE_MAX_ENTRIES = 50_000;
    private static final String CACHE_TTL_CONFIG_KEY = "entitlement.me_cache_seconds";
    private static final long DEFAULT_CACHE_TTL_SECONDS = 15L;

    private final EntitlementResolver entitlementResolver;
    private final UsageService usageService;
    private final PersonalSpaceService personalSpaceService;
    private final WorkspaceService workspaceService;
    private final PlanCatalog planCatalog;
    /** null 表示缓存关闭（配置为 0）。 */
    private final TtlCache<Long, EntitlementMeDTO> cache;

    /**
     * Spring 使用的构造器。类上存在多个构造器，必须显式标注 {@code @Autowired}：
     * 否则 Spring 不会猜测，而是回退到「默认无参构造」，导致启动期
     * {@code NoSuchMethodException: <init>()}（task-30 生产事故根因，见 {@code SpringBeanConstructorTest} 防护）。
     */
    @Autowired
    public EntitlementMeService(EntitlementResolver entitlementResolver,
                                UsageService usageService,
                                PersonalSpaceService personalSpaceService,
                                WorkspaceService workspaceService,
                                PlanCatalog planCatalog,
                                ConfigService configService) {
        this.entitlementResolver = entitlementResolver;
        this.usageService = usageService;
        this.personalSpaceService = personalSpaceService;
        this.workspaceService = workspaceService;
        this.planCatalog = planCatalog;
        long ttlSeconds = configService == null
                ? DEFAULT_CACHE_TTL_SECONDS
                : configService.getLong(CACHE_TTL_CONFIG_KEY, DEFAULT_CACHE_TTL_SECONDS);
        this.cache = ttlSeconds > 0
                ? new TtlCache<>(ttlSeconds * 1000L, CACHE_MAX_ENTRIES)
                : null;
    }

    /** 测试用：直接注入带假时钟的缓存（null 表示关闭缓存）。 */
    EntitlementMeService(EntitlementResolver entitlementResolver,
                         UsageService usageService,
                         PersonalSpaceService personalSpaceService,
                         WorkspaceService workspaceService,
                         PlanCatalog planCatalog,
                         TtlCache<Long, EntitlementMeDTO> cache) {
        this.entitlementResolver = entitlementResolver;
        this.usageService = usageService;
        this.personalSpaceService = personalSpaceService;
        this.workspaceService = workspaceService;
        this.planCatalog = planCatalog;
        this.cache = cache;
    }

    /** 组装 {@code GET /api/entitlements/me} 的响应体（命中缓存时不再产生任何查询）。 */
    public EntitlementMeDTO me(Long userId) {
        if (cache == null) {
            return compute(userId);
        }
        return cache.getOrCompute(userId, () -> compute(userId));
    }

    /** 档位/覆盖/兑换变更后调用，避免客户端在 TTL 内看到旧额度。 */
    public void invalidate(Long userId) {
        if (cache != null && userId != null) {
            cache.invalidate(userId);
        }
    }

    private EntitlementMeDTO compute(Long userId) {
        // resolveFresh：跳过 resolver 的 60s 缓存，档位变更后客户端立即可见（与 /api/files/quota 一致）
        Entitlements entitlements = entitlementResolver.resolveFresh(userId);
        PlanLimits limits = entitlements.limits();

        // 个人空间配额（懒创建个人空间，与 /api/files/quota 完全同源）；limits 直接传入避免二次解析
        Long personalWorkspaceId = personalSpaceService.resolveWorkspaceId(userId);
        StorageQuotaDTO quota = workspaceService.getQuota(personalWorkspaceId, userId, entitlements);

        long relayLimit = limits.relayBytesMonth();
        long storageLimit = limits.storageBytes();
        long relayBytesDown = usageService.currentMonthDown(userId);
        long storageUsed = quota.getUsedBytes();

        EntitlementMeDTO dto = new EntitlementMeDTO();
        dto.setPlanCode(entitlements.planCode());
        dto.setPlanName(planDisplayName(entitlements.planCode()));
        dto.setStatus(resolveStatus(entitlements));
        dto.setPeriod(usageService.currentPeriod());
        dto.setExpiresAt(entitlements.expiresAt());

        EntitlementMeDTO.Limits dtoLimits = new EntitlementMeDTO.Limits();
        dtoLimits.setRelayBytesMonth(relayLimit);
        dtoLimits.setStorageBytes(storageLimit);
        dtoLimits.setMaxFileSize(limits.maxFileSize());
        dtoLimits.setMaxWorkspaces(limits.maxWorkspaces());
        dtoLimits.setMaxWorkspaceMembers(limits.maxWorkspaceMembers());
        dtoLimits.setConcurrentTransfers(limits.concurrentTransfers());
        dtoLimits.setRelayDownBytesPerSec(limits.relayDownBytesPerSec());
        dtoLimits.setGraceDays(limits.graceDays());
        dtoLimits.setShareExpiresDays(limits.shareExpiresDays());
        dtoLimits.setShareMaxDownloads(limits.shareMaxDownloads());
        dtoLimits.setShareMaxLinks(limits.shareMaxLinks());
        dtoLimits.setTrashRetentionDays(limits.trashRetentionDays());
        dto.setLimits(dtoLimits);

        EntitlementMeDTO.Usage dtoUsage = new EntitlementMeDTO.Usage();
        dtoUsage.setRelayBytesDown(relayBytesDown);
        dtoUsage.setStorageBytes(storageUsed);
        dtoUsage.setFileCount(quota.getFileCount());
        dtoUsage.setRelayBytesRemaining(remaining(relayLimit, relayBytesDown));
        dtoUsage.setStorageBytesRemaining(remaining(storageLimit, storageUsed));
        dto.setUsage(dtoUsage);

        dto.setThrottled(overLimit(relayLimit, relayBytesDown));
        dto.setStorageFull(overLimit(storageLimit, storageUsed));
        return dto;
    }

    /**
     * plans.name 展示名；查不到时回退 planCode（不硬编码中文名，避免和后台配置漂移）。
     * 名称来自 {@link PlanCatalog} 的进程内目录（启动加载 + 5 分钟刷新 + 管理端改档刷新），
     * 不再每请求查 plans 表。
     */
    private String planDisplayName(String planCode) {
        String name = planCatalog.name(planCode);
        return name == null || name.isBlank() ? planCode : name;
    }

    /**
     * active：当前生效；grace：已到期但仍在宽限期内。
     * 超过宽限期的情况 resolver 已回落 free（expiresAt=null），这里不会出现 expired。
     */
    private static String resolveStatus(Entitlements entitlements) {
        OffsetDateTime expiresAt = entitlements.expiresAt();
        if (expiresAt != null && OffsetDateTime.now().isAfter(expiresAt)) {
            return "grace";
        }
        return "active";
    }

    /** 剩余量；额度为 0（不限）时返回 0，客户端用 limits==0 判断“不限”。 */
    private static long remaining(long limit, long used) {
        return limit <= 0 ? 0L : Math.max(0L, limit - used);
    }

    /** 是否触达额度；0 表示不限，永不满。 */
    private static boolean overLimit(long limit, long used) {
        return limit > 0 && used >= limit;
    }
}
