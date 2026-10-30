package com.bond.transfer.preview;

/**
 * 解析 HTTP Range 头（单区间）。视频拖动进度只发单个 Range，多区间不支持时回退整文件。
 */
public final class RangeParser {

    public record Spec(long offset, long length, boolean partial, boolean unsatisfiable) {
    }

    private RangeParser() {
    }

    public static Spec parse(String rangeHeader, long totalSize) {
        if (totalSize < 0) {
            totalSize = 0;
        }
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return new Spec(0, totalSize, false, false);
        }
        String value = rangeHeader.trim();
        if (!value.toLowerCase(java.util.Locale.ROOT).startsWith("bytes=") || value.contains(",")) {
            return new Spec(0, totalSize, false, false);
        }
        String spec = value.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return new Spec(0, totalSize, false, false);
        }
        String startPart = spec.substring(0, dash).trim();
        String endPart = spec.substring(dash + 1).trim();
        try {
            if (startPart.isEmpty()) {
                // suffix: bytes=-N → 最后 N 字节
                long suffix = Long.parseLong(endPart);
                if (suffix <= 0) {
                    return new Spec(0, 0, false, true);
                }
                long length = Math.min(suffix, totalSize);
                return new Spec(Math.max(0, totalSize - length), length, true, false);
            }
            long start = Long.parseLong(startPart);
            if (start < 0 || start >= totalSize) {
                return new Spec(0, 0, false, true);
            }
            long end = endPart.isEmpty() ? totalSize - 1 : Long.parseLong(endPart);
            if (end < start) {
                return new Spec(0, 0, false, true);
            }
            end = Math.min(end, totalSize - 1);
            return new Spec(start, end - start + 1, true, false);
        } catch (NumberFormatException e) {
            return new Spec(0, totalSize, false, false);
        }
    }
}
