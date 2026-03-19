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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final String RATE_LIMIT_KEY_PREFIX = "rate_limit:";
    
    private final StringRedisTemplate redisTemplate;

    public void checkRateLimit(Customer customer) {
        String key = buildKey(customer.getId());
        TierConfig.TierLimits limits = TierConfig.getLimits(customer.getTier());

        Long currentCount = redisTemplate.opsForValue().increment(key);
        
        if (currentCount == 1) {
            redisTemplate.expire(key, Duration.ofDays(1));
        }

        if (currentCount != null && currentCount > limits.dailyRequestLimit()) {
            long secondsUntilReset = getSecondsUntilMidnight();
            log.warn("Rate limit exceeded for customer: {}, count: {}, limit: {}", 
                    customer.getId(), currentCount, limits.dailyRequestLimit());
            throw new RateLimitExceededException(secondsUntilReset);
        }
    }

    public long getCurrentUsage(UUID customerId) {
        String key = buildKey(customerId);
        String value = redisTemplate.opsForValue().get(key);
        return value != null ? Long.parseLong(value) : 0;
    }

    public long getRemainingRequests(Customer customer) {
        TierConfig.TierLimits limits = TierConfig.getLimits(customer.getTier());
        long currentUsage = getCurrentUsage(customer.getId());
        return Math.max(0, limits.dailyRequestLimit() - currentUsage);
    }

    private String buildKey(UUID customerId) {
        String date = LocalDate.now(ZoneOffset.UTC).toString();
        return RATE_LIMIT_KEY_PREFIX + customerId + ":" + date;
    }

    private long getSecondsUntilMidnight() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(ZoneOffset.UTC);
        java.time.LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return Duration.between(now, midnight).getSeconds();
    }
}
