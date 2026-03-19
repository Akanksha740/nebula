package com.nebula.common.config;

import com.nebula.common.entity.Customer.SubscriptionTier;
import lombok.Getter;

import java.util.Map;

@Getter
public class TierConfig {

    private static final Map<SubscriptionTier, TierLimits> TIER_LIMITS = Map.of(
        SubscriptionTier.FREE, new TierLimits(100, 7, 60),
        SubscriptionTier.STARTER, new TierLimits(10_000, 30, 15),
        SubscriptionTier.PRO, new TierLimits(100_000, 30, 1),
        SubscriptionTier.ENTERPRISE, new TierLimits(Integer.MAX_VALUE, 30, 1)
    );

    public static TierLimits getLimits(SubscriptionTier tier) {
        return TIER_LIMITS.getOrDefault(tier, TIER_LIMITS.get(SubscriptionTier.FREE));
    }

    public record TierLimits(
        int dailyRequestLimit,
        int dataRetentionDays,
        int minResolutionMinutes
    ) {
        public boolean canAccessDate(java.time.Instant date) {
            java.time.Instant cutoff = java.time.Instant.now()
                .minus(java.time.Duration.ofDays(dataRetentionDays));
            return date.isAfter(cutoff);
        }

        public boolean canUseResolution(int requestedMinutes) {
            return requestedMinutes >= minResolutionMinutes;
        }
    }
}
