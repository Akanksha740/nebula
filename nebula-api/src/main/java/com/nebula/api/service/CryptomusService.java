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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CryptomusService {

    private static final Logger log = LoggerFactory.getLogger(CryptomusService.class);
    private static final String CRYPTOMUS_API_URL = "https://api.cryptomus.com/v1";

    private final CustomerRepository customerRepository;
    private final StringRedisTemplate redisTemplate;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${cryptomus.merchant-id:}")
    private String merchantId;

    @Value("${cryptomus.api-key:}")
    private String apiKey;

    @Value("${cryptomus.webhook-url:}")
    private String webhookUrl;

    @Value("${cryptomus.pro-amount:11}")
    private String proAmount;

    @Value("${cryptomus.pro-currency:USD}")
    private String proCurrency;

    @Value("${nebula.app.base-url:http://localhost:3000}")
    private String appBaseUrl;

    @PostConstruct
    void logConfig() {
        log.info("Cryptomus init: merchant_id_set={}, api_key_set={}",
                merchantId != null && !merchantId.isBlank(),
                apiKey != null && !apiKey.isBlank());
    }

    /**
     * Creates a Cryptomus invoice for the requested tier and returns the payment page URL.
     * See: https://doc.cryptomus.com/merchant-api/payments/creating-invoice
     */
    @SuppressWarnings("unchecked")
    public String getCheckoutUrl(Customer customer, Customer.SubscriptionTier tier) {
        if (tier != Customer.SubscriptionTier.PRO) {
            throw new NebulaException("Crypto payment only available for Pro tier", "INVALID_TIER", 400);
        }
        if (merchantId == null || merchantId.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new NebulaException("Crypto payment system not configured", "BILLING_NOT_CONFIGURED", 503);
        }

        // order_id must be unique per merchant, max 128 chars
        String orderId = "nebula_" + customer.getId() + "_" + System.currentTimeMillis();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", proAmount);
        body.put("currency", proCurrency);
        body.put("order_id", orderId);
        body.put("url_return", appBaseUrl + "/dashboard");
        body.put("url_success", appBaseUrl + "/dashboard?upgraded=true");
        body.put("url_callback", webhookUrl);
        body.put("is_payment_multiple", false);
        body.put("lifetime", 3600); // 300–43200 seconds
        body.put("additional_data", customer.getId().toString() + "|" + tier.name());

        try {
            String jsonBody = toJson(body);
            String sign = generateSign(jsonBody);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("merchant", merchantId);
            headers.set("sign", sign);

            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    CRYPTOMUS_API_URL + "/payment", request, Map.class);

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null) {
                log.error("Cryptomus API returned null response");
                throw new NebulaException("Failed to create crypto checkout", "BILLING_ERROR", 502);
            }

            // Check for API error state
            Object state = responseBody.get("state");
            if (state != null && !state.equals(0)) {
                log.error("Cryptomus API error: state={}, message={}", state, responseBody.get("message"));
                throw new NebulaException("Crypto payment error: " + responseBody.get("message"), "BILLING_ERROR", 502);
            }

            Map<String, Object> result = (Map<String, Object>) responseBody.get("result");
            if (result == null || !result.containsKey("url")) {
                log.error("Cryptomus API returned no payment URL: {}", responseBody);
                throw new NebulaException("Failed to create crypto checkout", "BILLING_ERROR", 502);
            }

            String paymentUrl = (String) result.get("url");
            String paymentUuid = (String) result.get("uuid");
            log.info("Created Cryptomus invoice {} (order: {}) for customer {} (tier: {})",
                    paymentUuid, orderId, customer.getEmail(), tier);

            return paymentUrl;
        } catch (NebulaException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error creating Cryptomus invoice: {}", e.getMessage(), e);
            throw new NebulaException("Crypto payment service unavailable", "BILLING_ERROR", 502);
        }
    }

    /**
     * Called from webhook after Cryptomus confirms payment (status = paid | paid_over).
     * Activates PRO tier for the customer.
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

        Customer.SubscriptionTier newTier;
        try {
            newTier = Customer.SubscriptionTier.valueOf(tierName);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid tier in Cryptomus webhook: {}, defaulting to PRO", tierName);
            newTier = Customer.SubscriptionTier.PRO;
        }

        Customer.SubscriptionTier previousTier = customer.getTier();
        customer.setTier(newTier);
        // Clear trial expiry so the scheduler doesn't downgrade a paid user
        if (previousTier == Customer.SubscriptionTier.PRO_TRIAL) {
            customer.setProTrialExpiresAt(null);
        }
        customerRepository.save(customer);

        clearRateLimitCache(customer.getId());

        log.info("Crypto payment: upgraded customer {} from {} to {}",
                customer.getEmail(), previousTier, newTier);
    }

    /**
     * Verifies the Cryptomus webhook signature.
     *
     * Per docs (https://doc.cryptomus.com/merchant-api/payments/webhook):
     *   1. Extract 'sign' from payload, remove it
     *   2. JSON-encode the remaining data
     *   3. sign = MD5( base64(json) + apiPaymentKey )
     *   4. Compare with received sign
     *
     * Note: Cryptomus uses PHP json_encode which escapes forward slashes (/ → \/).
     * Java's Jackson does NOT escape slashes by default, so we must do it manually
     * to match the sign Cryptomus computed.
     *
     * @param jsonWithoutSign  JSON string of the webhook body with 'sign' removed
     * @param receivedSign     The 'sign' value extracted from the webhook body
     */
    public boolean verifyWebhookSign(String jsonWithoutSign, String receivedSign) {
        if (receivedSign == null || receivedSign.isBlank()) return false;
        try {
            // Escape forward slashes to match PHP's json_encode behavior
            String phpCompatibleJson = jsonWithoutSign.replace("/", "\\/");
            String data = Base64.getEncoder().encodeToString(phpCompatibleJson.getBytes(StandardCharsets.UTF_8));
            String expectedSign = md5(data + apiKey);
            return MessageDigest.isEqual(
                    expectedSign.getBytes(StandardCharsets.UTF_8),
                    receivedSign.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Error verifying Cryptomus webhook sign: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Generates the sign header for Cryptomus API requests.
     * Sign = MD5( base64(jsonBody) + apiKey )
     *
     * We escape forward slashes to match PHP's json_encode behavior,
     * which Cryptomus uses server-side for verification.
     */
    private String generateSign(String jsonBody) {
        try {
            String phpCompatibleJson = jsonBody.replace("/", "\\/");
            String data = Base64.getEncoder().encodeToString(phpCompatibleJson.getBytes(StandardCharsets.UTF_8));
            return md5(data + apiKey);
        } catch (Exception e) {
            throw new NebulaException("Failed to generate payment signature", "BILLING_ERROR", 500);
        }
    }

    /**
     * Serializes a map to JSON using Jackson.
     */
    private String toJson(Map<String, Object> body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        } catch (Exception e) {
            throw new NebulaException("Failed to serialize request body", "BILLING_ERROR", 500);
        }
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 not available", e);
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
