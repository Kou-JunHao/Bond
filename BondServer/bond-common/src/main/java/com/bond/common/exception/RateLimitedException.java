package com.bond.common.exception;

/**
 * 限流触发（429 + Retry-After）。put 在 bond-common，便于各模块共用与 GlobalExceptionHandler 统一处理。
 */
public class RateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
