package com.nebula.api.service;

import com.nebula.api.repository.CustomerRepository;
import com.nebula.common.entity.Customer;
import com.nebula.common.exception.NebulaException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private final CustomerRepository customerRepository;
    private final StringRedisTemplate redisTemplate;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${dodo.api.key:}")
    private String apiKey;

    @Value("${dodo.api.base-url:https://test.dodopayments.com}")
    private String apiBaseUrl;

    @Value("${dodo.products.pro:}")
    private String proProductId;

    @Value("${dodo.webhook.secret:}")
    private String webhookSecret;

    @Value("${nebula.app.base-url:http://localhost:3000}")
    private String appBaseUrl;

    @PostConstruct
    void logConfig() {
        boolean live = apiBaseUrl.contains("live");
        log.info("Billing init: mode={}, product_pro={}, api_key_set={}",
                live ? "LIVE" : "TEST", proProductId, apiKey != null && !apiKey.isBlank());
    }

    /**
     * Creates a Dodo subscription for the requested tier and returns the payment link URL.
     */
    @SuppressWarnings("unchecked")
    public String getCheckoutUrl(Customer customer, Customer.SubscriptionTier tier) {
        String productId = getProductIdForTier(tier);
        if (productId == null || productId.isBlank()) {
            throw new NebulaException("Product not configured for tier: " + tier.name(), "BILLING_NOT_CONFIGURED", 503);
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new NebulaException("Payment system not configured", "BILLING_NOT_CONFIGURED", 503);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "product_id", productId,
                "quantity", 1,
                "customer", Map.of(
                        "email", customer.getEmail(),
                        "name", customer.getCompanyName() != null ? customer.getCompanyName() : customer.getEmail()
                ),
                "billing", Map.of("country", "US"),
                "payment_link", true,
                "return_url", appBaseUrl + "/dashboard?upgraded=true",
                "metadata", Map.of(
                        "nebula_customer_id", customer.getId().toString(),
                        "tier", tier.name()
                )
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    apiBaseUrl + "/subscriptions", request, Map.class);

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null || !responseBody.containsKey("payment_link")) {
                log.error("Dodo API returned no payment_link: {}", responseBody);
                throw new NebulaException("Failed to create checkout session", "BILLING_ERROR", 502);
            }

            String paymentLink = (String) responseBody.get("payment_link");
            String subscriptionId = (String) responseBody.get("subscription_id");
            log.info("Created Dodo subscription {} for customer {} (tier: {})",
                    subscriptionId, customer.getEmail(), tier);

            return paymentLink;
        } catch (NebulaException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error creating Dodo subscription: {}", e.getMessage(), e);
            throw new NebulaException("Payment service unavailable", "BILLING_ERROR", 502);
        }
    }

    /**
     * Called from webhook after Dodo confirms subscription is active.
     * Looks up customer by ID first, falls back to email.
     */
    @Transactional
    public void handleSubscriptionActive(String customerId, String customerEmail, String tier, String subscriptionId) {
        Customer customer = findCustomer(customerId, customerEmail);

        Customer.SubscriptionTier newTier;
        try {
            newTier = Customer.SubscriptionTier.valueOf(tier.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new NebulaException("Invalid tier: " + tier, "INVALID_TIER", 400);
        }

        Customer.SubscriptionTier previousTier = customer.getTier();
        customer.setTier(newTier);
        customer.setPaymentSubscriptionId(subscriptionId);
        customerRepository.save(customer);

        clearRateLimitCache(customer.getId());

        log.info("Upgraded customer {} from {} to {} (subscription: {})",
                customer.getEmail(), previousTier, newTier, subscriptionId);
    }

    /**
     * Called from webhook when a subscription is cancelled.
     * Looks up customer by ID first, falls back to email.
     */
    @Transactional
    public void handleSubscriptionCancelled(String customerId, String customerEmail) {
        Customer customer = findCustomer(customerId, customerEmail);

        customer.setTier(Customer.SubscriptionTier.STARTER);
        customer.setPaymentSubscriptionId(null);
        customerRepository.save(customer);

        clearRateLimitCache(customer.getId());

        log.info("Downgraded customer {} to STARTER tier (subscription cancelled)", customer.getEmail());
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    /**
     * Finds customer by ID first, falls back to email lookup.
     */
    private Customer findCustomer(String customerId, String customerEmail) {
        // Try by ID first
        if (customerId != null && !customerId.isBlank()) {
            try {
                var found = customerRepository.findById(java.util.UUID.fromString(customerId));
                if (found.isPresent()) return found.get();
                log.warn("Customer not found by ID {}, trying email fallback", customerId);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid customer ID format: {}", customerId);
            }
        }

        // Fallback to email
        if (customerEmail != null && !customerEmail.isBlank()) {
            return customerRepository.findByEmail(customerEmail)
                    .orElseThrow(() -> new NebulaException(
                            "Customer not found by ID=" + customerId + " or email=" + customerEmail,
                            "CUSTOMER_NOT_FOUND", 404));
        }

        throw new NebulaException("Customer not found: " + customerId, "CUSTOMER_NOT_FOUND", 404);
    }

    private String getProductIdForTier(Customer.SubscriptionTier tier) {
        return switch (tier) {
            case PRO -> proProductId;
            default -> throw new NebulaException("Upgrade not available for tier: " + tier.name(), "INVALID_TIER", 400);
        };
    }

    private void clearRateLimitCache(java.util.UUID customerId) {
        try {
            String pattern = "rate_limit:" + customerId + ":*";
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("Cleared {} rate-limit cache keys for customer {}", keys.size(), customerId);
            }
        } catch (Exception e) {
            log.warn("Failed to clear rate-limit cache for customer {}: {}", customerId, e.getMessage());
        }
    }
}
