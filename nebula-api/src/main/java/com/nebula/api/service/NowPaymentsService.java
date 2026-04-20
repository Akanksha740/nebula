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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
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

    @Value("${nowpayments.ipn-secret:}")
    private String ipnSecret;

    @Value("${nowpayments.pro-amount:11}")
    private String proAmount;

    @Value("${nowpayments.pro-currency:usd}")
    private String proCurrency;

    @Value("${nowpayments.webhook-url:}")
    private String webhookUrl;

    @Value("${nebula.app.base-url:http://localhost:3000}")
    private String appBaseUrl;

    @PostConstruct
    void logConfig() {
        log.info("NowPayments init: api_key_set={}, ipn_secret_set={}",
                apiKey != null && !apiKey.isBlank(),
                ipnSecret != null && !ipnSecret.isBlank());
    }

    /**
     * Creates a NOWPayments invoice for the requested tier and returns the payment page URL.
     * See: https://documenter.getpostman.com/view/7907941/2s93JusNJt#3998d18c-fd1f-4f44-802d-0de30e12be77
     */
    @SuppressWarnings("unchecked")
    public String getCheckoutUrl(Customer customer, Customer.SubscriptionTier tier) {
        if (tier != Customer.SubscriptionTier.PRO) {
            throw new NebulaException("Crypto payment only available for Pro tier", "INVALID_TIER", 400);
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new NebulaException("Crypto payment system not configured", "BILLING_NOT_CONFIGURED", 503);
        }

        String orderId = "nebula_" + customer.getId() + "_" + System.currentTimeMillis();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("price_amount", Double.parseDouble(proAmount));
        body.put("price_currency", proCurrency);
        body.put("order_id", orderId);
        body.put("order_description", "Nebula Pro Plan");
        body.put("ipn_callback_url", webhookUrl);
        body.put("success_url", appBaseUrl + "/dashboard?upgraded=true");
        body.put("cancel_url", appBaseUrl + "/pricing");

        try {
            String jsonBody = toJson(body);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);

            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    NOWPAYMENTS_API_URL + "/invoice", request, Map.class);

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null || !responseBody.containsKey("invoice_url")) {
                log.error("NOWPayments API returned no invoice_url: {}", responseBody);
                throw new NebulaException("Failed to create crypto checkout", "BILLING_ERROR", 502);
            }

            String invoiceUrl = (String) responseBody.get("invoice_url");
            String invoiceId = responseBody.get("id") != null ? responseBody.get("id").toString() : "unknown";
            log.info("Created NOWPayments invoice {} (order: {}) for customer {} (tier: {})",
                    invoiceId, orderId, customer.getEmail(), tier);

            return invoiceUrl;
        } catch (NebulaException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error creating NOWPayments invoice: {}", e.getMessage(), e);
            throw new NebulaException("Crypto payment service unavailable", "BILLING_ERROR", 502);
        }
    }

    /**
     * Called from webhook after NOWPayments confirms payment.
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
            log.warn("Invalid tier in NOWPayments webhook: {}, defaulting to PRO", tierName);
            newTier = Customer.SubscriptionTier.PRO;
        }

        Customer.SubscriptionTier previousTier = customer.getTier();
        customer.setTier(newTier);
        if (previousTier == Customer.SubscriptionTier.PRO_TRIAL) {
            customer.setProTrialExpiresAt(null);
        }
        customerRepository.save(customer);

        clearRateLimitCache(customer.getId());

        log.info("NOWPayments: upgraded customer {} from {} to {}",
                customer.getEmail(), previousTier, newTier);
    }

    /**
     * Verifies the NOWPayments IPN webhook signature.
     *
     * NOWPayments signs the sorted JSON body using HMAC-SHA512 with the IPN secret.
     * The signature is sent in the 'x-nowpayments-sig' header.
     *
     * Steps:
     *   1. Sort the JSON body keys alphabetically
     *   2. Compute HMAC-SHA512(sorted_json, ipn_secret)
     *   3. Compare with the received signature
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

    private String toJson(Map<String, Object> body) {
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
