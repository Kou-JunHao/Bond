package com.bond.transfer.entitlement;

import com.bond.transfer.mapper.EntitlementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-25 C：下载入口统一包装「限速 + 计量」，按实际送出字节在 close 时落账。
 */
@ExtendWith(MockitoExtension.class)
class UsageServiceMeteringTest {

    @Mock
    private EntitlementMapper entitlementMapper;

    @Mock
    private EntitlementResolver entitlementResolver;

    private UsageService usageService;

    @BeforeEach
    void setUp() {
        usageService = new UsageService(entitlementMapper, entitlementResolver);
    }

    private static Entitlements plan(long relayBytesMonth, long downBytesPerSec) {
        return new Entitlements("free", new PlanLimits(
                relayBytesMonth,
                5L * 1024 * 1024 * 1024,
                2L * 1024 * 1024 * 1024,
                2, 1, 3,
                downBytesPerSec,
                7, 3, 30, 5, 30), null);
    }

    private static byte[] data(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    @Test
    @DisplayName("未超额：包装后读到多少记多少（按实际读出，不是声明大小）")
    void underQuotaRecordsActualBytes() throws Exception {
        when(entitlementResolver.resolve(7L)).thenReturn(plan(10_000L, 0L));
        when(entitlementMapper.selectDownloadBytes(eq(7L), eq(usageService.currentPeriod())))
                .thenReturn(0L);

        InputStream in = usageService.meterAndThrottleDownload(7L, new ByteArrayInputStream(data(42)));
        byte[] buffer = new byte[10];
        in.read(buffer);
        in.read(buffer);
        in.close();

        verify(entitlementMapper).addDownloadBytes(7L, usageService.currentPeriod(), 20L);
    }

    @Test
    @DisplayName("超额：降速不断线，仍完整送出且按实际字节落账")
    void exhaustedQuotaDegradesButStillMeters() throws Exception {
        when(entitlementResolver.resolve(7L)).thenReturn(plan(1_000L, 0L));
        when(entitlementMapper.selectDownloadBytes(eq(7L), eq(usageService.currentPeriod())))
                .thenReturn(5_000L); // 已超 1000B 上限

        InputStream in = usageService.meterAndThrottleDownload(7L, new ByteArrayInputStream(data(64)));
        assertTrue(in instanceof MeteredInputStream);
        int read = 0;
        byte[] buffer = new byte[64];
        int n;
        while ((n = in.read(buffer, read, buffer.length - read)) != -1) {
            read += n;
        }
        in.close();

        assertEquals(64, read);
        verify(entitlementMapper).addDownloadBytes(7L, usageService.currentPeriod(), 64L);
    }

    @Test
    @DisplayName("未读到字节的 close 不落账（空下载不产生计量行）")
    void emptyDownloadRecordsNothing() throws Exception {
        when(entitlementResolver.resolve(7L)).thenReturn(plan(10_000L, 0L));

        InputStream in = usageService.meterAndThrottleDownload(7L, new ByteArrayInputStream(new byte[0]));
        in.close();

        verify(entitlementMapper, org.mockito.Mockito.never())
                .addDownloadBytes(anyLong(), org.mockito.ArgumentMatchers.anyString(), anyLong());
    }
}
