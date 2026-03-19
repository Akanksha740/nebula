package com.nebula.common.exception;

import lombok.Getter;

@Getter
public class RateLimitExceededException extends NebulaException {
    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super(
            String.format("Rate limit exceeded. Retry after %d seconds", retryAfterSeconds),
            "RATE_LIMIT_EXCEEDED",
            429
        );
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message, "RATE_LIMIT_EXCEEDED", 429);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
