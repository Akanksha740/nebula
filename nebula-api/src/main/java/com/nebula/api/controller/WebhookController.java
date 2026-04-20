package com.nebula.api.controller;

import com.nebula.api.service.BillingService;
import com.nebula.api.service.CryptomusService;
import com.nebula.api.service.NowPaymentsService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    private final CryptomusService cryptomusService;
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
     * Cryptomus webhook endpoint.
     * See: https://doc.cryptomus.com/merchant-api/payments/webhook
     *
     * Cryptomus sends a POST when invoice status changes.
     * Payload fields: type, uuid, order_id, amount, payment_amount, status,
     *                 is_final, additional_data, sign, network, currency, etc.
     *
     * Signature verification:
     *   1. Extract 'sign' from body, remove it
     *   2. Re-encode remaining data as JSON
     *   3. sign = MD5( base64(json) + apiPaymentKey )
     *
     * Statuses we act on:
     *   - paid      → payment completed exactly
     *   - paid_over → client overpaid (still successful)
     *
     * We also check is_final to ensure the invoice is finalized.
     */
    @PostMapping("/cryptomus")
    @SuppressWarnings("unchecked")
    public ResponseEntity<String> handleCryptomusWebhook(@RequestBody String rawBody) {

        Map<String, Object> payload;
        try {
            // Jackson readValue with Map.class produces LinkedHashMap — preserves key order
            payload = new com.fasterxml.jackson.databind.ObjectMapper().readValue(rawBody, Map.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid JSON");
        }

        // Step 1: Extract and remove 'sign' before verification
        String receivedSign = payload.get("sign") != null ? payload.get("sign").toString() : null;

        // Step 2: Build JSON body without 'sign' for verification (preserving key order)
        Map<String, Object> bodyWithoutSign = new java.util.LinkedHashMap<>(payload);
        bodyWithoutSign.remove("sign");
        String bodyForVerification;
        try {
            bodyForVerification = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(bodyWithoutSign);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Serialization error");
        }

        // Step 3: Verify sign — CryptomusService handles base64 + MD5 + slash escaping
        if (!cryptomusService.verifyWebhookSign(bodyForVerification, receivedSign)) {
            log.warn("Invalid Cryptomus webhook signature");
            return ResponseEntity.status(401).body("Invalid signature");
        }

        String status = (String) payload.get("status");
        String orderId = (String) payload.get("order_id");
        String uuid = (String) payload.get("uuid");
        String additionalData = (String) payload.get("additional_data");
        Object isFinalObj = payload.get("is_final");
        boolean isFinal = isFinalObj instanceof Boolean ? (Boolean) isFinalObj : false;

        log.info("Received Cryptomus webhook: status={}, is_final={}, order_id={}, uuid={}",
                status, isFinal, orderId, uuid);

        if ("paid".equals(status) || "paid_over".equals(status)) {
            if (additionalData == null || !additionalData.contains("|")) {
                log.warn("Cryptomus webhook missing additional_data: order_id={}", orderId);
                return ResponseEntity.badRequest().body("Missing additional_data");
            }
            String[] parts = additionalData.split("\\|", 2);
            String customerId = parts[0];
            String tierName = parts[1];

            try {
                cryptomusService.handlePaymentCompleted(customerId, tierName);
            } catch (Exception e) {
                log.error("Error processing Cryptomus payment (order={}, uuid={}): {}",
                        orderId, uuid, e.getMessage(), e);
                return ResponseEntity.internalServerError().body("Processing error");
            }
        } else if ("cancel".equals(status) || "fail".equals(status) || "system_fail".equals(status)) {
            log.warn("Cryptomus payment failed: status={}, order_id={}, uuid={}", status, orderId, uuid);
        } else {
            log.debug("Cryptomus status update: status={}, order_id={}", status, orderId);
        }

        return ResponseEntity.ok("OK");
    }

    /**
     * NOWPayments IPN webhook endpoint.
     * See: https://documenter.getpostman.com/view/7907941/2s93JusNJt#ipn-callbacks
     *
     * NOWPayments sends a POST when payment status changes.
     * Payload fields: payment_id, payment_status, pay_address, price_amount,
     *                 price_currency, pay_amount, pay_currency, order_id,
     *                 order_description, outcome_amount, outcome_currency, etc.
     *
     * Signature verification:
     *   1. Sort JSON body keys alphabetically
     *   2. HMAC-SHA512(sorted_json, ipn_secret)
     *   3. Compare with 'x-nowpayments-sig' header
     *
     * Statuses we act on:
     *   - finished       → payment completed
     *   - partially_paid → log warning (underpayment)
     */
    @PostMapping("/nowpayments")
    @SuppressWarnings("unchecked")
    public ResponseEntity<String> handleNowPaymentsWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "x-nowpayments-sig", required = false) String signature) {

        Map<String, Object> payload;
        try {
            payload = new com.fasterxml.jackson.databind.ObjectMapper().readValue(rawBody, Map.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid JSON");
        }

        // Verify IPN signature — NOWPayments requires sorted keys for HMAC
        String sortedJson;
        try {
            TreeMap<String, Object> sorted = new TreeMap<>(payload);
            sortedJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(sorted);
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

        log.info("Received NOWPayments webhook: payment_status={}, order_id={}, payment_id={}",
                paymentStatus, orderId, paymentId);

        if ("finished".equals(paymentStatus)) {
            // order_id format: nebula_<customerId>_<timestamp>
            if (orderId == null || !orderId.startsWith("nebula_")) {
                log.warn("NOWPayments webhook with invalid order_id: {}", orderId);
                return ResponseEntity.badRequest().body("Invalid order_id");
            }

            String[] parts = orderId.split("_", 3);
            if (parts.length < 3) {
                log.warn("NOWPayments webhook order_id missing parts: {}", orderId);
                return ResponseEntity.badRequest().body("Invalid order_id format");
            }
            String customerId = parts[1];

            try {
                nowPaymentsService.handlePaymentCompleted(customerId, "PRO");
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
