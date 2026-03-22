package com.nebula.api.controller;

import com.nebula.api.service.BillingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final BillingService billingService;

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
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "webhook-id", required = false) String webhookId,
            @RequestHeader(value = "webhook-signature", required = false) String webhookSignature,
            @RequestHeader(value = "webhook-timestamp", required = false) String webhookTimestamp) {

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
        String tier = getStringFromMetadata(metadata, "tier");
        String customerEmail = getCustomerEmail(data);
        String subscriptionId = (String) data.get("subscription_id");

        if (customerId == null && customerEmail == null) {
            log.warn("subscription.active webhook missing both nebula_customer_id and customer email: {}", data);
            return;
        }

        String effectiveTier = (tier != null && !tier.isBlank()) ? tier : "PRO";
        billingService.handleSubscriptionActive(customerId, customerEmail, effectiveTier, subscriptionId);
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
}
