package com.bond.transfer.entitlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bond.transfer.mapper.EntitlementMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * task-25 C：下行计量流必须「读到多少记多少」，在 close 时一次性落账。
 */
class MeteredInputStreamTest {

    private static byte[] data(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    @Test
    @DisplayName("close 时按实际读出的字节数记录（不是声明大小）")
    void recordsBytesActuallyDelivered() throws IOException {
        UsageService usage = mock(UsageService.class);
        MeteredInputStream in = new MeteredInputStream(
                new ByteArrayInputStream(data(100)), usage, 7L);

        byte[] buffer = new byte[30];
        in.read(buffer, 0, 30);
        in.read(buffer, 0, 20);
        in.read();
        assertEquals(51L, in.getCountedBytes());
        in.close();

        verify(usage, times(1)).recordDownload(7L, 51L);
    }

    @Test
    @DisplayName("客户端中途断开（只读了一部分就 close）按已送出量计")
    void prematureCloseRecordsDeliveredOnly() throws IOException {
        UsageService usage = mock(UsageService.class);
        MeteredInputStream in = new MeteredInputStream(
                new ByteArrayInputStream(data(100)), usage, 9L);

        in.read(new byte[10], 0, 10);
        in.close();

        verify(usage).recordDownload(9L, 10L);
    }

    @Test
    @DisplayName("未读任何字节就 close：不产生计量写入")
    void closeWithoutReadRecordsNothing() throws IOException {
        UsageService usage = mock(UsageService.class);
        MeteredInputStream in = new MeteredInputStream(
                new ByteArrayInputStream(data(10)), usage, 7L);

        in.close();

        verify(usage, never()).recordDownload(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("读到 EOF 后 close 仍记录已送出字节")
    void endOfStreamRecordsDelivered() throws IOException {
        UsageService usage = mock(UsageService.class);
        MeteredInputStream in = new MeteredInputStream(
                new ByteArrayInputStream(data(64)), usage, 7L);

        while (in.read() != -1) {
            // drain
        }
        in.close();

        verify(usage).recordDownload(7L, 64L);
    }

    @Test
    @DisplayName("skip 掉的字节不算下行送出量")
    void skippedBytesAreNotCounted() throws IOException {
        UsageService usage = mock(UsageService.class);
        MeteredInputStream in = new MeteredInputStream(
                new ByteArrayInputStream(data(100)), usage, 7L);

        in.skip(50);
        in.read();
        in.close();

        verify(usage).recordDownload(7L, 1L);
    }

    @Test
    @DisplayName("重复 close 只落账一次（幂等）")
    void closeIsIdempotent() throws IOException {
        UsageService usage = mock(UsageService.class);
        MeteredInputStream in = new MeteredInputStream(
                new ByteArrayInputStream(data(10)), usage, 7L);

        in.read(new byte[4], 0, 4);
        in.close();
        in.close();

        verify(usage, times(1)).recordDownload(7L, 4L);
    }

    @Test
    @DisplayName("计量写失败不影响流关闭（异常被吞掉并告警）")
    void meteringFailureDoesNotBreakClose() throws IOException {
        UsageService usage = mock(UsageService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(usage).recordDownload(7L, 5L);
        MeteredInputStream in = new MeteredInputStream(
                new ByteArrayInputStream(data(10)), usage, 7L);

        in.read(new byte[5], 0, 5);
        in.close(); // 不应抛出

        verify(usage).recordDownload(7L, 5L);
    }

    @Test
    @DisplayName("0 字节/无用户时 recordDownload 本身不写库")
    void recordDownloadSkipsZeroAndNull() {
        EntitlementMapper mapper = mock(EntitlementMapper.class);
        UsageService usage = new UsageService(mapper, mock(EntitlementResolver.class));

        usage.recordDownload(7L, 0L);
        usage.recordDownload(null, 10L);

        verify(mapper, never()).addDownloadBytes(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }
}
