package com.nebula.api.service;

import com.nebula.api.repository.CustomerRepository;
import com.nebula.common.entity.Customer;
import com.nebula.common.exception.NebulaException;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private final CustomerRepository customerRepository;

    @Value("${stripe.api.secret-key:}")
    private String stripeSecretKey;

    @Value("${stripe.prices.starter:}")
    private String starterPriceId;

    @Value("${stripe.prices.pro:}")
    private String proPriceId;

    @Value("${nebula.app.base-url:http://localhost:3000}")
    private String appBaseUrl;

    @PostConstruct
    public void init() {
        if (stripeSecretKey != null && !stripeSecretKey.isBlank()) {
            Stripe.apiKey = stripeSecretKey;
        }
    }

    @Transactional
    public String createCheckoutSession(Customer customer, Customer.SubscriptionTier tier) {
        validateStripeConfigured();

        try {
            String stripeCustomerId = getOrCreateStripeCustomer(customer);
            String priceId = getPriceIdForTier(tier);

            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setCustomer(stripeCustomerId)
                    .setSuccessUrl(appBaseUrl + "/billing/success?session_id={CHECKOUT_SESSION_ID}")
                    .setCancelUrl(appBaseUrl + "/billing/cancel")
                    .addLineItem(
                            SessionCreateParams.LineItem.builder()
                                    .setPrice(priceId)
                                    .setQuantity(1L)
                                    .build()
                    )
                    .putMetadata("customer_id", customer.getId().toString())
                    .putMetadata("tier", tier.name())
                    .build();

            Session session = Session.create(params);
            log.info("Created checkout session {} for customer {}", session.getId(), customer.getId());
            
            return session.getUrl();
        } catch (StripeException e) {
            log.error("Stripe error creating checkout session", e);
            throw new NebulaException("Failed to create checkout session", "BILLING_ERROR", 500, e);
        }
    }

    @Transactional
    public void handleSubscriptionCreated(String subscriptionId, String customerId) {
        try {
            Subscription subscription = Subscription.retrieve(subscriptionId);
            String stripeCustomerId = subscription.getCustomer();

            customerRepository.findByStripeCustomerId(stripeCustomerId)
                    .ifPresent(customer -> {
                        Customer.SubscriptionTier tier = determineTierFromSubscription(subscription);
                        customer.setTier(tier);
                        customer.setStripeSubscriptionId(subscriptionId);
                        customerRepository.save(customer);
                        log.info("Updated customer {} to tier {}", customer.getId(), tier);
                    });
        } catch (StripeException e) {
            log.error("Failed to handle subscription created event", e);
        }
    }

    @Transactional
    public void handleSubscriptionCanceled(String subscriptionId) {
        customerRepository.findAll().stream()
                .filter(c -> subscriptionId.equals(c.getStripeSubscriptionId()))
                .findFirst()
                .ifPresent(customer -> {
                    customer.setTier(Customer.SubscriptionTier.FREE);
                    customer.setStripeSubscriptionId(null);
                    customerRepository.save(customer);
                    log.info("Downgraded customer {} to FREE tier", customer.getId());
                });
    }

    public Map<String, Object> getSubscriptionDetails(Customer customer) {
        if (customer.getStripeSubscriptionId() == null) {
            return Map.of(
                    "tier", customer.getTier().name(),
                    "status", "none"
            );
        }

        try {
            Subscription subscription = Subscription.retrieve(customer.getStripeSubscriptionId());
            return Map.of(
                    "tier", customer.getTier().name(),
                    "status", subscription.getStatus(),
                    "currentPeriodEnd", subscription.getCurrentPeriodEnd(),
                    "cancelAtPeriodEnd", subscription.getCancelAtPeriodEnd()
            );
        } catch (StripeException e) {
            log.error("Failed to retrieve subscription details", e);
            return Map.of(
                    "tier", customer.getTier().name(),
                    "status", "unknown"
            );
        }
    }

    private String getOrCreateStripeCustomer(Customer customer) throws StripeException {
        if (customer.getStripeCustomerId() != null) {
            return customer.getStripeCustomerId();
        }

        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(customer.getEmail())
                .setName(customer.getCompanyName())
                .putMetadata("nebula_customer_id", customer.getId().toString())
                .build();

        com.stripe.model.Customer stripeCustomer = com.stripe.model.Customer.create(params);
        
        customer.setStripeCustomerId(stripeCustomer.getId());
        customerRepository.save(customer);

        return stripeCustomer.getId();
    }

    private String getPriceIdForTier(Customer.SubscriptionTier tier) {
        return switch (tier) {
            case STARTER -> starterPriceId;
            case PRO -> proPriceId;
            default -> throw new NebulaException("Invalid tier for subscription", "INVALID_TIER", 400);
        };
    }

    private Customer.SubscriptionTier determineTierFromSubscription(Subscription subscription) {
        String priceId = subscription.getItems().getData().get(0).getPrice().getId();
        if (priceId.equals(starterPriceId)) {
            return Customer.SubscriptionTier.STARTER;
        } else if (priceId.equals(proPriceId)) {
            return Customer.SubscriptionTier.PRO;
        }
        return Customer.SubscriptionTier.FREE;
    }

    private void validateStripeConfigured() {
        if (stripeSecretKey == null || stripeSecretKey.isBlank()) {
            throw new NebulaException("Billing not configured", "BILLING_NOT_CONFIGURED", 503);
        }
    }
}
