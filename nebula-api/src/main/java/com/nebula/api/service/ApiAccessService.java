package com.nebula.api.service;

import com.nebula.common.config.TierConfig;
import com.nebula.common.config.TierConfig.TierLimits;
import com.nebula.common.entity.ApiKey;
import com.nebula.common.entity.Customer;
import com.nebula.common.entity.Customer.SubscriptionTier;
import com.nebula.common.exception.TierAccessException;
import com.nebula.common.exception.UnauthorizedException;
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
        "/v1/auth/login",
        "/v1/auth/register",
        "/v1/auth/google",
        "/v1/auth/refresh",
        "/v1/webhooks/stripe",
        "/v1/admin",
        "/swagger-ui",
        "/v3/api-docs"
    );

    private static final Map<String, SubscriptionTier> ENDPOINT_MIN_TIERS = Map.of(
        "/v1/markets/*/snapshots", SubscriptionTier.STARTER
    );

    public void checkAccess(Customer customer, ApiKey apiKey, String endpoint, String method) {
        if (isPublicEndpoint(endpoint)) {
            return;
        }

        if (requiresApiKey(endpoint)) {
            if (apiKey == null) {
                throw new UnauthorizedException("API key required. Include your API key in the X-API-Key header.");
            }
            if (customer == null) {
                throw new UnauthorizedException("Invalid API key");
            }
        }

        if (customer != null) {
            checkRateLimit(customer);
            checkEndpointAccess(customer, endpoint);
        }
    }

    private boolean requiresApiKey(String endpoint) {
        return endpoint.startsWith("/v1/markets");
    }

    public void validateDataAccess(Customer customer, Instant requestedDate) {
        if (customer == null || requestedDate == null) {
            return;
        }

        TierLimits limits = TierConfig.getLimits(customer.getTier());
        if (!limits.canAccessDate(requestedDate)) {
            int retentionDays = limits.dataRetentionDays();
            String upgradeTier = getNextTierForDataAccess(customer.getTier());
            throw new TierAccessException(
                String.format("Your %s plan only allows access to the last %d days of data. Upgrade to %s for extended history.",
                    customer.getTier().name(), retentionDays, upgradeTier),
                upgradeTier,
                customer.getTier().name()
            );
        }
    }

    public void validateResolution(Customer customer, int requestedMinutes) {
        if (customer == null) {
            return;
        }

        TierLimits limits = TierConfig.getLimits(customer.getTier());
        if (!limits.canUseResolution(requestedMinutes)) {
            int minResolution = limits.minResolutionMinutes();
            String upgradeTier = getNextTierForResolution(customer.getTier());
            throw new TierAccessException(
                String.format("Your %s plan requires minimum %d minute resolution. Upgrade to %s for higher resolution data.",
                    customer.getTier().name(), minResolution, upgradeTier),
                upgradeTier,
                customer.getTier().name()
            );
        }
    }

    public TierLimits getLimits(Customer customer) {
        return TierConfig.getLimits(customer != null ? customer.getTier() : SubscriptionTier.FREE);
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
            case FREE -> "STARTER";
            case STARTER, PRO -> "PRO";
            case ENTERPRISE -> "ENTERPRISE";
        };
    }

    private String getNextTierForResolution(SubscriptionTier currentTier) {
        return switch (currentTier) {
            case FREE -> "STARTER";
            case STARTER -> "PRO";
            case PRO, ENTERPRISE -> "ENTERPRISE";
        };
    }
}
