package com.nebula.common.config;

import com.nebula.common.entity.Customer.SubscriptionTier;
import lombok.Getter;

import java.util.Map;

@Getter
public class TierConfig {

    private static final Map<SubscriptionTier, TierLimits> TIER_LIMITS = Map.of(
        // Starter ($0) — 60 req/min, 1,000 req/day, 1 API key, BTC only: 5m/15m last 50 markets, 1h/4h last 24, 24h last 5
        SubscriptionTier.STARTER, new TierLimits(60, 1_000, 1, 1, 50, 50, 24, 24, 5),
        // Pro ($11/mo) — 300 req/min, 50,000 req/day, 3 API keys, unlimited market access
        SubscriptionTier.PRO, new TierLimits(300, 50_000, 3, 1, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE),
        // Pro Trial (7-day free) — same limits as Pro
        SubscriptionTier.PRO_TRIAL, new TierLimits(300, 50_000, 3, 1, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE),
        // Enterprise — 33 API keys, unlimited everything else
        SubscriptionTier.ENTERPRISE, new TierLimits(Integer.MAX_VALUE, Integer.MAX_VALUE, 33, 1, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
    );

    public static TierLimits getLimits(SubscriptionTier tier) {
        return TIER_LIMITS.getOrDefault(tier, TIER_LIMITS.get(SubscriptionTier.STARTER));
    }

    public record TierLimits(
        int minuteRequestLimit,
        int dailyRequestLimit,
        int maxApiKeys,
        int minResolutionMinutes,
        int marketLimit5m,
        int marketLimit15m,
        int marketLimit1h,
        int marketLimit4h,
        int marketLimit24h
    ) {
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
    }
}
