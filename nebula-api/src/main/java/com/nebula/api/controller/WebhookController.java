package com.nebula.api.controller;

import com.nebula.api.service.BillingService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final BillingService billingService;

    @Value("${stripe.webhook.secret:}")
    private String webhookSecret;

    @PostMapping("/stripe")
    public ResponseEntity<String> handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("Stripe webhook secret not configured");
            return ResponseEntity.ok("Webhook not configured");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.error("Webhook signature verification failed", e);
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        log.info("Received Stripe webhook: {}", event.getType());

        switch (event.getType()) {
            case "customer.subscription.created":
            case "customer.subscription.updated":
                handleSubscriptionUpdate(event);
                break;
            case "customer.subscription.deleted":
                handleSubscriptionCanceled(event);
                break;
            case "invoice.payment_failed":
                handlePaymentFailed(event);
                break;
            default:
                log.debug("Unhandled event type: {}", event.getType());
        }

        return ResponseEntity.ok("OK");
    }

    private void handleSubscriptionUpdate(Event event) {
        Subscription subscription = (Subscription) event.getDataObjectDeserializer()
                .getObject().orElse(null);
        if (subscription != null) {
            billingService.handleSubscriptionCreated(subscription.getId(), subscription.getCustomer());
        }
    }

    private void handleSubscriptionCanceled(Event event) {
        Subscription subscription = (Subscription) event.getDataObjectDeserializer()
                .getObject().orElse(null);
        if (subscription != null) {
            billingService.handleSubscriptionCanceled(subscription.getId());
        }
    }

    private void handlePaymentFailed(Event event) {
        log.warn("Payment failed event received: {}", event.getId());
    }
}
