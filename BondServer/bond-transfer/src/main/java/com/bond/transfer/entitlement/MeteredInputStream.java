package com.bond.transfer.entitlement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 下行流量计量流：按**实际从底层读出的字节数**计数，在 close 时一次性原子累加。
 *
 * <p>为什么在 close 才写：{@code /content} 是流式响应，只有响应真正结束（或被客户端中断）时
 * 才知道实际送出了多少字节；提前按声明大小计数会高估。客户端中途断开时，Spring 在 finally
 * 里关闭资源，因此这里仍能按已读字节落账。</p>
 *
 * <p>计量失败不影响响应（close 阶段不能让 HTTP 连接报错），只记 WARN。</p>
 */
public class MeteredInputStream extends FilterInputStream {

    private static final Logger log = LoggerFactory.getLogger(MeteredInputStream.class);

    private final UsageService usageService;
    private final Long userId;
    private long countedBytes;
    private boolean recorded;

    public MeteredInputStream(InputStream in, UsageService usageService, Long userId) {
        super(in);
        this.usageService = usageService;
        this.userId = userId;
    }

    public long getCountedBytes() {
        return countedBytes;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value != -1) {
            countedBytes++;
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read > 0) {
            countedBytes += read;
        }
        return read;
    }

    @Override
    public void close() throws IOException {
        try {
            recordOnce();
        } finally {
            super.close();
        }
    }

    private void recordOnce() {
        if (recorded) {
            return;
        }
        recorded = true;
        if (countedBytes <= 0) {
            return;
        }
        try {
            usageService.recordDownload(userId, countedBytes);
        } catch (RuntimeException e) {
            // 计量落点失败不能影响已完成的下载；日志保留可观测性
            log.warn("downlink metering failed: user={}, bytes={}", userId, countedBytes, e);
        }
    }
}
