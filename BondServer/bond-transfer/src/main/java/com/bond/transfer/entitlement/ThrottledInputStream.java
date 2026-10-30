package com.bond.transfer.entitlement;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 按平均速率限速的输入流（降速不断线）。
 *
 * <p>超出月度流量后用它把下行降到 1MB/s；正常档位按档位限速；0 表示不限速（直接返回原流）。</p>
 */
public class ThrottledInputStream extends FilterInputStream {

    private final long bytesPerSecond;
    private long startNanos = -1L;
    private long bytesRead = 0L;

    public ThrottledInputStream(InputStream in, long bytesPerSecond) {
        super(in);
        this.bytesPerSecond = bytesPerSecond;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value != -1) {
            throttle(1);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read > 0) {
            throttle(read);
        }
        return read;
    }

    private void throttle(long justRead) throws IOException {
        if (bytesPerSecond <= 0) {
            return;
        }
        if (startNanos < 0) {
            startNanos = System.nanoTime();
        }
        bytesRead += justRead;
        long expectedNanos = bytesRead * 1_000_000_000L / bytesPerSecond;
        long elapsedNanos = System.nanoTime() - startNanos;
        long sleepNanos = expectedNanos - elapsedNanos;
        if (sleepNanos > 0) {
            try {
                Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while throttling download", e);
            }
        }
    }
}
