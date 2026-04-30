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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class NowPaymentsService {

    private static final Logger log = LoggerFactory.getLogger(NowPaymentsService.class);
    private static final String NOWPAYMENTS_API_URL = "https://api.nowpayments.io/v1";

    private final CustomerRepository customerRepository;
    private final StringRedisTemplate redisTemplate;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${nowpayments.api-key:}")
    private String apiKey;

    @Value("${nowpayments.email:}")
    private String accountEmail;

    @Value("${nowpayments.password:}")
    private String accountPassword;

    @Value("${nowpayments.ipn-secret:}")
    private String ipnSecret;

    private volatile String jwtToken;
    private volatile Instant jwtTokenExpiry;

    @Value("${nowpayments.pro-amount:22}")
    private String proAmount;

    @Value("${nowpayments.pro-currency:usd}")
    private String proCurrency;

    @Value("${nowpayments.webhook-url:}")
    private String webhookUrl;

    @Value("${nowpayments.pro-plan-id:}")
    private String proPlanId;

    @Value("${nowpayments.pro-interval-days:60}")
    private int proIntervalDays;

    @Value("${nebula.app.base-url:http://localhost:3000}")
    private String appBaseUrl;

    @PostConstruct
    void logConfig() {
        log.info("NowPayments init: api_key_set={}, ipn_secret_set={}, plan_id_set={}, auth_set={}",
                apiKey != null && !apiKey.isBlank(),
                ipnSecret != null && !ipnSecret.isBlank(),
                proPlanId != null && !proPlanId.isBlank(),
                accountEmail != null && !accountEmail.isBlank());
    }

    /**
     * Creates a NOWPayments recurring subscription for the customer.
     *
     * Flow:
     *   1. Ensure a subscription plan exists (create one if plan ID not configured)
     *   2. POST /v1/subscriptions with plan_id + customer email
     *   3. NOWPayments emails the customer a payment link
     *   4. Store the subscription ID on the customer
     *
     * Returns the subscription ID (payment link is emailed by NOWPayments).
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public String createSubscription(Customer customer, Customer.SubscriptionTier tier) {
        if (tier != Customer.SubscriptionTier.PRO) {
            throw new NebulaException("Crypto subscription only available for Pro tier", "INVALID_TIER", 400);
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new NebulaException("Crypto payment system not configured", "BILLING_NOT_CONFIGURED", 503);
        }

        // If customer already has an active crypto subscription AND is PRO, don't create another
        if (customer.getTier() == Customer.SubscriptionTier.PRO
                && customer.getCryptoSubscriptionId() != null && !customer.getCryptoSubscriptionId().isBlank()) {
            throw new NebulaException("You already have an active crypto subscription", "SUBSCRIPTION_EXISTS", 400);
        }
        // Clear stale subscription ID from previous failed attempts
        if (customer.getCryptoSubscriptionId() != null && customer.getTier() != Customer.SubscriptionTier.PRO) {
            customer.setCryptoSubscriptionId(null);
            customerRepository.save(customer);
        }

        String planId = getOrCreatePlanId();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subscription_plan_id", Long.parseLong(planId));
        body.put("email", customer.getEmail());

        try {
            String jsonBody = toJson(body);
            ResponseEntity<Map> response;
            try {
                HttpEntity<String> request = new HttpEntity<>(jsonBody, buildAuthHeaders());
                response = restTemplate.postForEntity(
                        NOWPAYMENTS_API_URL + "/subscriptions", request, Map.class);
            } catch (org.springframework.web.client.HttpClientErrorException.Forbidden e) {
                // Token expired — get a fresh one and retry once
                log.warn("NOWPayments token expired, refreshing and retrying");
                invalidateJwtToken();
                HttpEntity<String> retryRequest = new HttpEntity<>(jsonBody, buildAuthHeaders());
                response = restTemplate.postForEntity(
                        NOWPAYMENTS_API_URL + "/subscriptions", retryRequest, Map.class);
            } catch (org.springframework.web.client.HttpServerErrorException e) {
                String body500 = e.getResponseBodyAsString();
                if (body500 != null && body500.contains("already subscribed")) {
                    // Email already subscribed from a previous attempt — delete old and recreate
                    log.warn("Email already subscribed to plan, deleting old subscription and recreating");
                    deleteExistingSubscription(customer.getEmail());
                    // Retry creating subscription after deleting old one
                    HttpEntity<String> retryRequest = new HttpEntity<>(jsonBody, buildAuthHeaders());
                    response = restTemplate.postForEntity(
                            NOWPAYMENTS_API_URL + "/subscriptions", retryRequest, Map.class);
                } else {
                    throw e;
                }
            }

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null) {
                log.error("NOWPayments subscription API returned null body");
                throw new NebulaException("Failed to create crypto subscription", "BILLING_ERROR", 502);
            }

            // Response format: { "result": [ { "id": ..., "status": "WAITING_PAY", ... } ] }
            String subscriptionId = null;
            if (responseBody.containsKey("result") && responseBody.get("result") instanceof List) {
                List<Map<String, Object>> results = (List<Map<String, Object>>) responseBody.get("result");
                if (!results.isEmpty() && results.get(0).containsKey("id")) {
                    subscriptionId = results.get(0).get("id").toString();
                }
            } else if (responseBody.containsKey("id")) {
                subscriptionId = responseBody.get("id").toString();
            }

            if (subscriptionId == null) {
                log.error("NOWPayments subscription API returned no id: {}", responseBody);
                throw new NebulaException("Failed to create crypto subscription", "BILLING_ERROR", 502);
            }

            // Store subscription ID on the customer
            customer.setCryptoSubscriptionId(subscriptionId);
            customerRepository.save(customer);

            log.info("Created NOWPayments subscription {} (plan: {}) for customer {} (tier: {})",
                    subscriptionId, planId, customer.getEmail(), tier);

            return subscriptionId;
        } catch (NebulaException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error creating NOWPayments subscription: {}", e.getMessage(), e);
            throw new NebulaException("Crypto payment service unavailable", "BILLING_ERROR", 502);
        }
    }

    /**
     * Called from webhook after NOWPayments confirms a subscription payment.
     * Activates/extends PRO tier for the customer.
     *
     * For recurring payments, each successful payment extends the subscription
     * expiry by the configured interval (default 30 days).
     */
    @Transactional
    public void handlePaymentCompleted(String customerId, String tierName) {
        Customer customer;
        try {
            customer = customerRepository.findById(UUID.fromString(customerId))
                    .orElseThrow(() -> new NebulaException("Customer not found: " + customerId, "CUSTOMER_NOT_FOUND", 404));
        } catch (IllegalArgumentException e) {
            throw new NebulaException("Invalid customer ID: " + customerId, "INVALID_CUSTOMER_ID", 400);
        }

        activateSubscription(customer, tierName);
    }

    /**
     * Called from webhook when we identify the customer by email (subscription payments).
     */
    @Transactional
    public void handleSubscriptionPaymentCompleted(String customerEmail) {
        Customer customer = customerRepository.findByEmail(customerEmail)
                .orElseThrow(() -> new NebulaException("Customer not found: " + customerEmail, "CUSTOMER_NOT_FOUND", 404));

        activateSubscription(customer, "PRO");
    }

    private void activateSubscription(Customer customer, String tierName) {
        Customer.SubscriptionTier newTier;
        try {
            newTier = Customer.SubscriptionTier.valueOf(tierName);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid tier in NOWPayments webhook: {}, defaulting to PRO", tierName);
            newTier = Customer.SubscriptionTier.PRO;
        }

        Customer.SubscriptionTier previousTier = customer.getTier();
        customer.setTier(newTier);

        // Extend subscription expiry from now (or from current expiry if still active)
        Instant baseTime = Instant.now();
        if (customer.getCryptoSubscriptionExpiresAt() != null
                && customer.getCryptoSubscriptionExpiresAt().isAfter(baseTime)) {
            baseTime = customer.getCryptoSubscriptionExpiresAt();
        }
        customer.setCryptoSubscriptionExpiresAt(baseTime.plus(proIntervalDays, ChronoUnit.DAYS));

        if (previousTier == Customer.SubscriptionTier.PRO_TRIAL) {
            customer.setProTrialExpiresAt(null);
        }
        customerRepository.save(customer);

        clearRateLimitCache(customer.getId());

        log.info("NOWPayments: activated/renewed subscription for customer {} ({}→{}), expires={}",
                customer.getEmail(), previousTier, newTier, customer.getCryptoSubscriptionExpiresAt());
    }

    /**
     * Cancels the customer's NOWPayments recurring subscription.
     * Calls DELETE /v1/subscriptions/:id to stop future payment emails.
     * The customer retains PRO access until cryptoSubscriptionExpiresAt.
     */
    public void cancelSubscription(Customer customer) {
        if (customer.getTier() != Customer.SubscriptionTier.PRO) {
            throw new NebulaException("No active subscription to cancel", "NO_SUBSCRIPTION", 400);
        }

        String subscriptionId = customer.getCryptoSubscriptionId();
        if (subscriptionId == null || subscriptionId.isBlank()) {
            throw new NebulaException("No crypto subscription found. Contact support.", "NO_SUBSCRIPTION_ID", 400);
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new NebulaException("Crypto payment system not configured", "BILLING_NOT_CONFIGURED", 503);
        }

        try {
            HttpEntity<Void> request = new HttpEntity<>(buildAuthHeaders());

            restTemplate.exchange(
                    NOWPAYMENTS_API_URL + "/subscriptions/" + subscriptionId,
                    HttpMethod.DELETE,
                    request,
                    Map.class);

            log.info("Cancelled NOWPayments subscription {} for customer {} (access until {})",
                    subscriptionId, customer.getEmail(), customer.getCryptoSubscriptionExpiresAt());
        } catch (Exception e) {
            log.error("Error cancelling NOWPayments subscription {} for customer {}: {}",
                    subscriptionId, customer.getEmail(), e.getMessage(), e);
            throw new NebulaException("Failed to cancel crypto subscription. Please try again or contact support.",
                    "CANCELLATION_FAILED", 502);
        }
    }

    /**
     * Scheduled job: downgrades customers whose crypto subscription has expired.
     * Runs every hour, similar to ProTrialService.expireTrials().
     */
    @Scheduled(fixedRate = 3600000) // every hour
    @Transactional
    public void expireCryptoSubscriptions() {
        List<Customer> expired = customerRepository.findExpiredCryptoSubscriptions(Instant.now());
        for (Customer customer : expired) {
            customer.setTier(Customer.SubscriptionTier.STARTER);
            customer.setCryptoSubscriptionId(null);
            customer.setCryptoSubscriptionExpiresAt(null);
            customerRepository.save(customer);
            clearRateLimitCache(customer.getId());
            log.info("Crypto subscription expired: downgraded customer {} to STARTER", customer.getEmail());
        }
        if (!expired.isEmpty()) {
            log.info("Expired {} crypto subscriptions", expired.size());
        }
    }

    /**
     * Returns the configured plan ID, or creates a new subscription plan if not configured.
     */
    @SuppressWarnings("unchecked")
    private String getOrCreatePlanId() {
        if (proPlanId != null && !proPlanId.isBlank()) {
            return proPlanId;
        }

        // Create a new subscription plan
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Nebula Pro Plan");
        body.put("interval_day", proIntervalDays);
        body.put("amount", Double.parseDouble(proAmount));
        body.put("currency", proCurrency);
        body.put("ipn_callback_url", webhookUrl);
        body.put("success_url", appBaseUrl + "/dashboard?upgraded=true");
        body.put("cancel_url", appBaseUrl + "/pricing");
        body.put("partially_paid_url", appBaseUrl + "/pricing?status=partial");

        try {
            String jsonBody = toJson(body);
            HttpEntity<String> request = new HttpEntity<>(jsonBody, buildAuthHeaders());

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    NOWPAYMENTS_API_URL + "/subscriptions/plans", request, Map.class);

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null) {
                throw new NebulaException("Failed to create subscription plan", "BILLING_ERROR", 502);
            }

            // Response wraps result in a "result" field
            Map<String, Object> result = responseBody.containsKey("result")
                    ? (Map<String, Object>) responseBody.get("result")
                    : responseBody;

            if (!result.containsKey("id")) {
                log.error("NOWPayments plan creation returned no id: {}", responseBody);
                throw new NebulaException("Failed to create subscription plan", "BILLING_ERROR", 502);
            }

            String planId = result.get("id").toString();
            log.info("Created NOWPayments subscription plan: id={}, interval={}d, amount={} {}",
                    planId, proIntervalDays, proAmount, proCurrency);

            // Cache the plan ID for future use (store in config would be better)
            proPlanId = planId;
            return planId;
        } catch (NebulaException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error creating NOWPayments subscription plan: {}", e.getMessage(), e);
            throw new NebulaException("Crypto payment service unavailable", "BILLING_ERROR", 502);
        }
    }

    /**
     * Finds and deletes all existing subscriptions for a customer email on the given plan.
     * GET /v1/subscriptions?subscription_plan_id=X → find by email → DELETE /v1/subscriptions/:id
     */
    @SuppressWarnings("unchecked")
    private void deleteExistingSubscription(String email) {
        try {
            HttpEntity<Void> request = new HttpEntity<>(buildAuthHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    NOWPAYMENTS_API_URL + "/subscriptions?subscription_plan_id=" + proPlanId + "&limit=100",
                    HttpMethod.GET, request, Map.class);

            Map<String, Object> body = response.getBody();
            if (body == null || !body.containsKey("result")) return;

            List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("result");
            for (Map<String, Object> sub : results) {
                Object subscriber = sub.get("subscriber");
                if (subscriber instanceof Map) {
                    String subEmail = (String) ((Map<String, Object>) subscriber).get("email");
                    if (email.equalsIgnoreCase(subEmail) && sub.containsKey("id")) {
                        String subId = sub.get("id").toString();
                        HttpEntity<Void> delRequest = new HttpEntity<>(buildAuthHeaders());
                        restTemplate.exchange(
                                NOWPAYMENTS_API_URL + "/subscriptions/" + subId,
                                HttpMethod.DELETE, delRequest, Map.class);
                        log.info("Deleted old NOWPayments subscription {} for {}", subId, email);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error deleting existing subscription for {}: {}", email, e.getMessage());
        }
    }

    /**
     * Obtains a JWT token via POST /v1/auth. Cached for 4 minutes (tokens expire in 5 min).
     */
    @SuppressWarnings("unchecked")
    private String getJwtToken() {
        if (jwtToken != null && jwtTokenExpiry != null
                && Instant.now().isBefore(jwtTokenExpiry)) {
            return jwtToken;
        }

        Map<String, String> body = Map.of("email", accountEmail, "password", accountPassword);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            HttpEntity<String> request = new HttpEntity<>(
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body), headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    NOWPAYMENTS_API_URL + "/auth", request, Map.class);

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null || !responseBody.containsKey("token")) {
                throw new NebulaException("NOWPayments auth returned no token", "BILLING_ERROR", 502);
            }

            jwtToken = (String) responseBody.get("token");
            jwtTokenExpiry = Instant.now().plus(4, ChronoUnit.MINUTES); // tokens expire in 5 min
            log.info("Obtained NOWPayments JWT token");
            return jwtToken;
        } catch (NebulaException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error authenticating with NOWPayments: {}", e.getMessage(), e);
            throw new NebulaException("Crypto payment auth failed", "BILLING_ERROR", 502);
        }
    }

    /**
     * Invalidates the cached JWT token so the next call gets a fresh one.
     */
    private void invalidateJwtToken() {
        jwtToken = null;
        jwtTokenExpiry = null;
    }

    private HttpHeaders buildAuthHeaders() {
        String token = getJwtToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.setBearerAuth(token);
        return headers;
    }

    /**
     * Verifies the NOWPayments IPN webhook signature.
     */
    public boolean verifyIpnSignature(String sortedJson, String receivedSignature) {
        if (receivedSignature == null || receivedSignature.isBlank()) return false;
        if (ipnSecret == null || ipnSecret.isBlank()) {
            log.warn("IPN secret not configured — skipping signature verification");
            return true;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(ipnSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] hash = mac.doFinal(sortedJson.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            String expectedSignature = sb.toString();

            return expectedSignature.equalsIgnoreCase(receivedSignature);
        } catch (Exception e) {
            log.error("Error verifying NOWPayments IPN signature: {}", e.getMessage());
            return false;
        }
    }

    private String toJson(Map<String, ?> body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        } catch (Exception e) {
            throw new NebulaException("Failed to serialize request body", "BILLING_ERROR", 500);
        }
    }

    private void clearRateLimitCache(UUID customerId) {
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
