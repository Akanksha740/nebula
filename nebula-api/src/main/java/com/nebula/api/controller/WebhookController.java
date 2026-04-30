package com.nebula.api.controller;

import com.nebula.api.service.BillingService;
import com.nebula.api.service.NowPaymentsService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/v1/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final BillingService billingService;
    private final NowPaymentsService nowPaymentsService;

    /**
     * Dodo Payments webhook endpoint.
     *
     * Dodo sends:
     *   Headers: webhook-id, webhook-signature, webhook-timestamp
     *   Body: { "business_id", "type", "timestamp", "data": { ... } }
     *
     * Event types:
     *   - subscription.active   → upgrade customer tier
     *   - subscription.cancelled → downgrade to STARTER
     *   - payment.succeeded     → log only (subscription.active handles upgrades)
     */
    @PostMapping("/dodo")
    @SuppressWarnings("unchecked")
    public ResponseEntity<String> handleDodoWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "webhook-id", required = false) String webhookId,
            @RequestHeader(value = "webhook-signature", required = false) String webhookSignature,
            @RequestHeader(value = "webhook-timestamp", required = false) String webhookTimestamp) {

        // Verify webhook signature
        String secret = billingService.getWebhookSecret();
        if (secret != null && !secret.isBlank()) {
            if (!verifySignature(rawBody, webhookId, webhookTimestamp, webhookSignature, secret)) {
                log.warn("Invalid webhook signature for webhook-id={}", webhookId);
                return ResponseEntity.status(401).body("Invalid signature");
            }
        } else {
            log.warn("Webhook secret not configured — skipping signature verification");
        }

        Map<String, Object> payload;
        try {
            payload = new com.fasterxml.jackson.databind.ObjectMapper().readValue(rawBody, Map.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid JSON");
        }

        String eventType = (String) payload.get("type");
        Map<String, Object> data = (Map<String, Object>) payload.get("data");

        log.info("Received Dodo webhook: type={}, webhook-id={}", eventType, webhookId);

        if (data == null) {
            log.warn("Dodo webhook missing data field");
            return ResponseEntity.badRequest().body("Missing data");
        }

        try {
            switch (eventType != null ? eventType : "") {
                case "subscription.active" -> handleSubscriptionActive(data);
                case "subscription.cancelled" -> handleSubscriptionCancelled(data);
                case "payment.succeeded" -> log.info("Payment succeeded: {}", data.get("payment_id"));
                default -> log.debug("Unhandled Dodo event: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Error processing Dodo webhook (type={}, id={}): {}",
                    eventType, webhookId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Processing error");
        }

        return ResponseEntity.ok("OK");
    }

    private void handleSubscriptionActive(Map<String, Object> data) {
        Map<String, Object> metadata = getMetadata(data);
        String customerId = getStringFromMetadata(metadata, "nebula_customer_id");
        String customerEmail = getCustomerEmail(data);
        String subscriptionId = (String) data.get("subscription_id");
        String productId = (String) data.get("product_id");

        if (customerId == null && customerEmail == null) {
            log.warn("subscription.active webhook missing both nebula_customer_id and customer email: {}", data);
            return;
        }

        billingService.handleSubscriptionActive(customerId, customerEmail, productId, subscriptionId);
    }

    private void handleSubscriptionCancelled(Map<String, Object> data) {
        Map<String, Object> metadata = getMetadata(data);
        String customerId = getStringFromMetadata(metadata, "nebula_customer_id");
        String customerEmail = getCustomerEmail(data);
        String subscriptionId = (String) data.get("subscription_id");

        if (customerId == null && customerEmail == null) {
            log.warn("subscription.cancelled webhook missing both nebula_customer_id and customer email: {}", data);
            return;
        }

        log.info("Subscription cancelled: {} for customer id={} email={}", subscriptionId, customerId, customerEmail);
        billingService.handleSubscriptionCancelled(customerId, customerEmail);
    }

    @SuppressWarnings("unchecked")
    private String getCustomerEmail(Map<String, Object> data) {
        Object customer = data.get("customer");
        if (customer instanceof Map) {
            Object email = ((Map<String, Object>) customer).get("email");
            return email != null ? email.toString() : null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMetadata(Map<String, Object> data) {
        Object meta = data.get("metadata");
        if (meta instanceof Map) {
            return (Map<String, Object>) meta;
        }
        return Map.of();
    }

    private String getStringFromMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * NOWPayments IPN webhook endpoint.
     * Handles both legacy one-time payments (order_id = nebula_<customerId>_<ts>)
     * and recurring subscription payments (identified by customer email).
     *
     * Signature verification:
     *   1. Sort JSON body keys alphabetically
     *   2. HMAC-SHA512(sorted_json, ipn_secret)
     *   3. Compare with 'x-nowpayments-sig' header
     *
     * Statuses we act on:
     *   - finished       → payment completed, activate/renew subscription
     *   - partially_paid → log warning (underpayment)
     */
    @PostMapping("/nowpayments")
    @SuppressWarnings("unchecked")
    public ResponseEntity<String> handleNowPaymentsWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "x-nowpayments-sig", required = false) String signature) {

        // Use BigDecimal for floats to avoid scientific notation (e.g. 2.8471E-4 vs 0.00028471)
        // which would cause HMAC mismatch with NOWPayments' signature
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
                .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

        Map<String, Object> payload;
        try {
            payload = mapper.readValue(rawBody, Map.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid JSON");
        }

        // Verify IPN signature — NOWPayments requires sorted keys for HMAC
        String sortedJson;
        try {
            sortedJson = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Serialization error");
        }

        if (!nowPaymentsService.verifyIpnSignature(sortedJson, signature)) {
            log.warn("Invalid NOWPayments IPN signature");
            return ResponseEntity.status(401).body("Invalid signature");
        }

        String paymentStatus = (String) payload.get("payment_status");
        String orderId = (String) payload.get("order_id");
        Object paymentId = payload.get("payment_id");
        String purchaseId = payload.get("purchase_id") != null ? payload.get("purchase_id").toString() : null;

        log.info("Received NOWPayments webhook: payment_status={}, order_id={}, payment_id={}, purchase_id={}",
                paymentStatus, orderId, paymentId, purchaseId);

        if ("finished".equals(paymentStatus)) {
            try {
                if (orderId != null && orderId.startsWith("nebula_")) {
                    // Legacy one-time payment: order_id = nebula_<customerId>_<timestamp>
                    String[] parts = orderId.split("_", 3);
                    if (parts.length < 3) {
                        log.warn("NOWPayments webhook order_id missing parts: {}", orderId);
                        return ResponseEntity.badRequest().body("Invalid order_id format");
                    }
                    String customerId = parts[1];
                    nowPaymentsService.handlePaymentCompleted(customerId, "PRO");
                } else {
                    // Subscription payment: identify customer by order_description (email)
                    // or by looking up the subscription via purchase_id
                    String orderDescription = (String) payload.get("order_description");
                    if (orderDescription != null && orderDescription.contains("@")) {
                        // order_description contains the customer email for subscription payments
                        nowPaymentsService.handleSubscriptionPaymentCompleted(orderDescription.trim());
                    } else if (orderId != null) {
                        // Try to extract email or subscription info from order_id
                        log.warn("NOWPayments subscription payment with unrecognized order_id: {}, purchase_id: {}",
                                orderId, purchaseId);
                        return ResponseEntity.ok("OK");
                    } else {
                        log.warn("NOWPayments webhook with no order_id or order_description");
                        return ResponseEntity.badRequest().body("Cannot identify customer");
                    }
                }
            } catch (Exception e) {
                log.error("Error processing NOWPayments payment (order={}, payment_id={}): {}",
                        orderId, paymentId, e.getMessage(), e);
                return ResponseEntity.internalServerError().body("Processing error");
            }
        } else if ("partially_paid".equals(paymentStatus)) {
            log.warn("NOWPayments partial payment: order_id={}, payment_id={}", orderId, paymentId);
        } else if ("failed".equals(paymentStatus) || "expired".equals(paymentStatus)) {
            log.warn("NOWPayments payment failed/expired: status={}, order_id={}", paymentStatus, orderId);
        } else {
            log.debug("NOWPayments status update: status={}, order_id={}", paymentStatus, orderId);
        }

        return ResponseEntity.ok("OK");
    }

    /**
     * Verifies the Dodo webhook signature using HMAC-SHA256.
     * Signature format: "v1,<base64-signature>"
     * Signed content: "{webhook-id}.{webhook-timestamp}.{body}"
     */
    private boolean verifySignature(String body, String webhookId, String timestamp, String signature, String secret) {
        if (webhookId == null || timestamp == null || signature == null) {
            return false;
        }

        try {
            String signedContent = webhookId + "." + timestamp + "." + body;

            // Dodo webhook secret is base64-encoded, prefixed with "whsec_"
            String secretKey = secret.startsWith("whsec_") ? secret.substring(6) : secret;
            byte[] secretBytes = Base64.getDecoder().decode(secretKey);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            byte[] hash = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = Base64.getEncoder().encodeToString(hash);

            // Signature header can contain multiple signatures separated by spaces: "v1,<sig1> v1,<sig2>"
            for (String sig : signature.split(" ")) {
                String[] parts = sig.split(",", 2);
                if (parts.length == 2 && "v1".equals(parts[0])) {
                    if (MessageDigest.isEqual(
                            expectedSignature.getBytes(StandardCharsets.UTF_8),
                            parts[1].getBytes(StandardCharsets.UTF_8))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.error("Error verifying webhook signature: {}", e.getMessage());
            return false;
        }
    }
}
