package com.nebula.common.config;

import com.nebula.common.entity.Customer.SubscriptionTier;
import lombok.Getter;

import java.util.Map;

@Getter
public class TierConfig {

    private static final Map<SubscriptionTier, TierLimits> TIER_LIMITS = Map.of(
        // Starter ($0) — 60 req/min, 1,000 req/day, 1 API key
        SubscriptionTier.STARTER, new TierLimits(60, 1_000, 1, 30, 1, 50, 50, 24, 24, 5, 50, 50, 24, 24, 5),
        // Pro ($11/mo) — 300 req/min, 50,000 req/day, 3 API keys, unlimited market access
        SubscriptionTier.PRO, new TierLimits(300, 50_000, 3, 365, 1, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE),
        // Enterprise — 33 API keys, unlimited everything else
        SubscriptionTier.ENTERPRISE, new TierLimits(Integer.MAX_VALUE, Integer.MAX_VALUE, 33, 365, 1, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
    );

    public static TierLimits getLimits(SubscriptionTier tier) {
        return TIER_LIMITS.getOrDefault(tier, TIER_LIMITS.get(SubscriptionTier.STARTER));
    }

    public record TierLimits(
        int minuteRequestLimit,
        int dailyRequestLimit,
        int maxApiKeys,
        int dataRetentionDays,
        int minResolutionMinutes,
        int marketLimit5m,
        int marketLimit15m,
        int marketLimit1h,
        int marketLimit4h,
        int marketLimit24h,
        int snapshotLimit5m,
        int snapshotLimit15m,
        int snapshotLimit1h,
        int snapshotLimit4h,
        int snapshotLimit24h
    ) {
        public boolean canAccessDate(java.time.Instant date) {
            java.time.Instant cutoff = java.time.Instant.now()
                .minus(java.time.Duration.ofDays(dataRetentionDays));
            return date.isAfter(cutoff);
        }

        public boolean canUseResolution(int requestedMinutes) {
            return requestedMinutes >= minResolutionMinutes;
        }

        /**
         * Returns the max number of markets a user can access for a given market type.
         */
        public int getMarketLimit(String marketType) {
            if (marketType == null) return marketLimit5m;
            return switch (marketType.toLowerCase()) {
                case "5m" -> marketLimit5m;
                case "15m" -> marketLimit15m;
                case "1h" -> marketLimit1h;
                case "4h" -> marketLimit4h;
                case "24h" -> marketLimit24h;
                default -> marketLimit5m;
            };
        }

        /**
         * Returns the max number of snapshots a user can access for a given market type.
         */
        public int getSnapshotLimit(String marketType) {
            if (marketType == null) return snapshotLimit5m;
            return switch (marketType.toLowerCase()) {
                case "5m" -> snapshotLimit5m;
                case "15m" -> snapshotLimit15m;
                case "1h" -> snapshotLimit1h;
                case "4h" -> snapshotLimit4h;
                case "24h" -> snapshotLimit24h;
                default -> snapshotLimit5m;
            };
        }
    }
}
