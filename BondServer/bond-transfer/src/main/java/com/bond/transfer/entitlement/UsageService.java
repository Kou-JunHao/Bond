package com.bond.transfer.entitlement;

import com.bond.transfer.mapper.EntitlementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * 中继流量计量与限速。
 *
 * <p><b>口径（Lead 已确认）：只计下行（下载）</b>。上传不计流量、不限速；
 * 纯局域网直传不经过服务器，因此其已用流量恒为 0。</p>
 *
 * <p>计量用 PG 原子 upsert（EntitlementMapper.addDownloadBytes），并发安全。
 * 流量用尽后不拒绝请求，而是<b>降速不断线</b>到 {@link #DEGRADED_DOWN_BYTES_PER_SEC}。</p>
 */
@Service
@RequiredArgsConstructor
public class UsageService {

    /** 超额后的降级下行速率（1 MB/s）。 */
    public static final long DEGRADED_DOWN_BYTES_PER_SEC = 1024L * 1024L;

    private final EntitlementMapper entitlementMapper;
    private final EntitlementResolver entitlementResolver;

    /** 当前计费周期（服务器时区）：YYYY-MM。 */
    public String currentPeriod() {
        return periodOf(OffsetDateTime.now());
    }

    public static String periodOf(OffsetDateTime time) {
        return YearMonth.from(time.atZoneSameInstant(ZoneId.systemDefault())).toString();
    }

    /** 记录下行字节（唯一计量入口；上传/秒传/LAN 直传不调用）。 */
    public void recordDownload(Long userId, long bytes) {
        if (userId == null || bytes <= 0) {
            return;
        }
        entitlementMapper.addDownloadBytes(userId, currentPeriod(), bytes);
    }

    public long currentMonthDown(Long userId) {
        if (userId == null) {
            return 0L;
        }
        Long value = entitlementMapper.selectDownloadBytes(userId, currentPeriod());
        return value == null ? 0L : value;
    }

    /** 月度高速流量是否已用尽（用尽后降速，不拒绝下载）。 */
    public boolean isTrafficExhausted(Long userId) {
        Entitlements entitlements = entitlementResolver.resolve(userId);
        long limit = entitlements.limits().relayBytesMonth();
        return limit > 0 && currentMonthDown(userId) >= limit;
    }

    /**
     * 下载入口统一策略：按流量/档位选择限速 → 返回「读到多少记多少」的计量流。
     *
     * <p><b>先限速、后计量</b>：返回的 {@link MeteredInputStream} 在流结束（close）时把
     * 实际送出的字节数用一条 {@code INSERT ... ON CONFLICT DO UPDATE} 原子累加到
     * {@code usage_counters.relay_bytes_down}，并发安全且不依赖调用方声明大小。</p>
     *
     * <p>TODO(计费策略): 当前只做「下行落点」，不做按流量扣减/封顶。
     * TODO: 局域网直连、秒传（hash 命中，不经过服务器）暂不存在，将来接入时不要调用本方法。</p>
     */
    public InputStream meterAndThrottleDownload(Long userId, InputStream original) {
        Entitlements entitlements = entitlementResolver.resolve(userId);
        long limit = entitlements.limits().relayBytesMonth();
        boolean exhausted = limit > 0 && currentMonthDown(userId) >= limit;
        long rate = exhausted ? DEGRADED_DOWN_BYTES_PER_SEC : entitlements.limits().relayDownBytesPerSec();
        InputStream throttled = rate <= 0 ? original : new ThrottledInputStream(original, rate);
        return new MeteredInputStream(throttled, this, userId);
    }
}
