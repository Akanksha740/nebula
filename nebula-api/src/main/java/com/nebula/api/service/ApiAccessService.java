package com.nebula.api.service;

import com.nebula.common.config.TierConfig;
import com.nebula.common.config.TierConfig.TierLimits;
import com.nebula.common.entity.ApiKey;
import com.nebula.common.entity.Customer;
import com.nebula.common.entity.Customer.SubscriptionTier;
import com.nebula.common.exception.TierAccessException;
import com.nebula.common.exception.UnauthorizedException;
import com.nebula.common.entity.Coin;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ApiAccessService {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessService.class);

    private final RateLimitService rateLimitService;

    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
        "/health",
        "/v1/health",
        "/v1/auth/login",
        "/v1/auth/register",
        "/v1/auth/google",
        "/v1/auth/refresh",
        "/v1/auth/verify-email",
        "/v1/auth/resend-verification",
        "/v1/auth/forgot-password",
        "/v1/auth/reset-password",
        "/v1/auth/logout",
        "/v1/webhook/dodo",
        "/v1/webhook/nowpayments",
        "/v1/admin",
        "/swagger-ui",
        "/v3/api-docs"
    );

    private static final Map<String, SubscriptionTier> ENDPOINT_MIN_TIERS = Map.of(
        "/v1/markets/*/snapshots", SubscriptionTier.STARTER,
        "/v1/markets/by-market-id/*/snapshots", SubscriptionTier.STARTER
    );

    public void checkAccess(Customer customer, ApiKey apiKey, String endpoint, String method) {
        if (isPublicEndpoint(endpoint)) {
            return;
        }

        if (requiresApiKey(endpoint)) {
            // Allow JWT-authenticated users (dashboard) without API key
            if (customer != null && apiKey == null) {
                // JWT user — skip API key check
            } else if (apiKey == null) {
                throw new UnauthorizedException("API key required. Include your API key in the X-API-Key header.");
            } else if (customer == null) {
                throw new UnauthorizedException("Invalid API key");
            }
        }

        if (customer != null) {
            if (isRateLimitedEndpoint(endpoint)) {
                checkRateLimit(customer);
            }
            checkEndpointAccess(customer, endpoint);
        }
    }

    private boolean requiresApiKey(String endpoint) {
        return endpoint.startsWith("/v1/markets");
    }

    private boolean isRateLimitedEndpoint(String endpoint) {
        return endpoint.startsWith("/v1/markets");
    }

    /**
     * Checks if the customer's tier allows access to the given coin.
     * BTC is available to all tiers. ETH requires PRO or higher.
     */
    public void checkCoinAccess(Customer customer, Coin coin) {
        if (coin == Coin.BTC) {
            return;
        }
        if (customer == null) {
            throw new TierAccessException(
                    String.format("%s market data requires a PRO or ENTERPRISE subscription.", coin.name()),
                    "PRO", "NONE");
        }
        if (!hasTierAccess(customer.getTier(), SubscriptionTier.PRO)) {
            throw new TierAccessException(
                    String.format("%s market data requires a PRO or ENTERPRISE subscription. Upgrade to access %s markets.",
                            coin.name(), coin.name()),
                    "PRO",
                    customer.getTier().name());
        }
    }

    public TierLimits getLimits(Customer customer) {
        return TierConfig.getLimits(customer != null ? customer.getTier() : SubscriptionTier.STARTER);
    }

    public long getRemainingRequests(Customer customer) {
        return rateLimitService.getRemainingRequests(customer);
    }

    public long getDailyLimit(Customer customer) {
        return TierConfig.getLimits(customer.getTier()).dailyRequestLimit();
    }

    private void checkRateLimit(Customer customer) {
        rateLimitService.checkRateLimit(customer);
    }

    private void checkEndpointAccess(Customer customer, String endpoint) {
        for (Map.Entry<String, SubscriptionTier> entry : ENDPOINT_MIN_TIERS.entrySet()) {
            if (matchesPattern(endpoint, entry.getKey())) {
                SubscriptionTier requiredTier = entry.getValue();
                if (!hasTierAccess(customer.getTier(), requiredTier)) {
                    throw new TierAccessException(
                        String.format("Endpoint requires %s tier or higher", requiredTier.name()),
                        requiredTier.name(),
                        customer.getTier().name()
                    );
                }
            }
        }
    }

    private boolean isPublicEndpoint(String endpoint) {
        return PUBLIC_ENDPOINTS.stream()
            .anyMatch(p -> endpoint.equals(p) || endpoint.startsWith(p));
    }

    private boolean matchesPattern(String endpoint, String pattern) {
        String regex = pattern.replace("*", "[^/]+");
        return endpoint.matches(regex);
    }

    private boolean hasTierAccess(SubscriptionTier current, SubscriptionTier required) {
        return current.ordinal() >= required.ordinal();
    }

    private String getNextTierForDataAccess(SubscriptionTier currentTier) {
        return switch (currentTier) {
            case STARTER -> "PRO";
            case PRO -> "ENTERPRISE";
            case PRO_TRIAL -> "PRO";
            case ENTERPRISE -> "ENTERPRISE";
        };
    }

    private String getNextTierForResolution(SubscriptionTier currentTier) {
        return switch (currentTier) {
            case STARTER -> "PRO";
            case PRO_TRIAL -> "PRO";
            case PRO, ENTERPRISE -> "ENTERPRISE";
        };
    }
}
