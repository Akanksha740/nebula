package com.nebula.api.service;

import com.nebula.common.config.TierConfig;
import com.nebula.common.entity.Customer;
import com.nebula.common.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final String DAILY_KEY_PREFIX = "rate_limit:";
    private static final String MINUTE_KEY_PREFIX = "rate_limit_min:";

    private final StringRedisTemplate redisTemplate;

    public void checkRateLimit(Customer customer) {
        TierConfig.TierLimits limits = TierConfig.getLimits(customer.getTier());

        // Check per-minute limit
        String minuteKey = buildMinuteKey(customer.getId());
        Long minuteCount = redisTemplate.opsForValue().increment(minuteKey);
        if (minuteCount == 1) {
            redisTemplate.expire(minuteKey, Duration.ofSeconds(60));
        }
        if (minuteCount != null && minuteCount > limits.minuteRequestLimit()) {
            log.warn("Per-minute rate limit exceeded for customer: {}, count: {}, limit: {}",
                    customer.getId(), minuteCount, limits.minuteRequestLimit());
            throw new RateLimitExceededException("Rate limit exceeded. Too many requests per minute. Retry after 60 seconds", 60);
        }

        // Check daily limit
        String dailyKey = buildDailyKey(customer.getId());
        Long dailyCount = redisTemplate.opsForValue().increment(dailyKey);
        if (dailyCount == 1) {
            redisTemplate.expire(dailyKey, Duration.ofDays(1));
        }
        if (dailyCount != null && dailyCount > limits.dailyRequestLimit()) {
            long secondsUntilReset = getSecondsUntilMidnight();
            log.warn("Daily rate limit exceeded for customer: {}, count: {}, limit: {}",
                    customer.getId(), dailyCount, limits.dailyRequestLimit());
            throw new RateLimitExceededException(secondsUntilReset);
        }
    }

    public long getCurrentUsage(UUID customerId) {
        String key = buildDailyKey(customerId);
        String value = redisTemplate.opsForValue().get(key);
        return value != null ? Long.parseLong(value) : 0;
    }

    public long getRemainingRequests(Customer customer) {
        TierConfig.TierLimits limits = TierConfig.getLimits(customer.getTier());
        long currentUsage = getCurrentUsage(customer.getId());
        return Math.max(0, limits.dailyRequestLimit() - currentUsage);
    }

    private String buildDailyKey(UUID customerId) {
        String date = LocalDate.now(ZoneOffset.UTC).toString();
        return DAILY_KEY_PREFIX + customerId + ":" + date;
    }

    private String buildMinuteKey(UUID customerId) {
        long minuteEpoch = Instant.now().getEpochSecond() / 60;
        return MINUTE_KEY_PREFIX + customerId + ":" + minuteEpoch;
    }

    private long getSecondsUntilMidnight() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(ZoneOffset.UTC);
        java.time.LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return Duration.between(now, midnight).getSeconds();
    }
}
