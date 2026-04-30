package com.nebula.api.controller;

import com.nebula.api.service.ApiKeyService;
import com.nebula.api.service.AuthService;
import com.nebula.api.service.BillingService;
import com.nebula.api.service.NowPaymentsService;
import com.nebula.api.service.ProTrialService;
import com.nebula.api.service.UsageTrackingService;
import com.nebula.common.dto.ApiKeyDto;
import com.nebula.common.dto.UsageStatsDto;
import com.nebula.common.dto.request.CreateApiKeyRequest;
import com.nebula.common.dto.response.ApiResponse;
import com.nebula.common.entity.Customer;
import com.nebula.common.exception.UnauthorizedException;
import com.nebula.common.util.ApiKeyGenerator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/account")
@RequiredArgsConstructor
@Tag(name = "Account", description = "Account management endpoints")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final ApiKeyService apiKeyService;
    private final UsageTrackingService usageTrackingService;
    private final BillingService billingService;
    private final NowPaymentsService nowPaymentsService;
    private final AuthService authService;
    private final ProTrialService proTrialService;

    @GetMapping("/api-keys")
    @Operation(summary = "List all API keys")
    public ResponseEntity<ApiResponse<List<ApiKeyDto>>> getApiKeys(
            @AuthenticationPrincipal Customer customer) {
        requireAuth(customer);
        log.info("List API keys: customer={}, tier={}", customer.getEmail(), customer.getTier());
        List<ApiKeyDto> keys = apiKeyService.getCustomerApiKeys(customer.getId());
        return ResponseEntity.ok(ApiResponse.success(keys));
    }

    @PostMapping("/api-keys")
    @Operation(summary = "Create a new API key")
    public ResponseEntity<ApiResponse<Map<String, String>>> createApiKey(
            @AuthenticationPrincipal Customer customer,
            @Valid @RequestBody CreateApiKeyRequest request) {
        requireAuth(customer);
        log.info("Create API key: name={}, customer={}, tier={}", request.getName(), customer.getEmail(), customer.getTier());
        ApiKeyGenerator.GeneratedKey generated = apiKeyService.createApiKey(customer, request);

        Map<String, String> response = Map.of(
                "apiKey", generated.rawKey(),
                "message", "Store this key securely. It won't be shown again."
        );

        return ResponseEntity.ok(ApiResponse.success(response, "API key created successfully"));
    }

    @DeleteMapping("/api-keys/{keyId}")
    @Operation(summary = "Revoke an API key")
    public ResponseEntity<ApiResponse<Void>> revokeApiKey(
            @AuthenticationPrincipal Customer customer,
            @PathVariable UUID keyId) {
        requireAuth(customer);
        log.info("Revoke API key: keyId={}, customer={}, tier={}", keyId, customer.getEmail(), customer.getTier());
        apiKeyService.revokeApiKey(customer.getId(), keyId);
        return ResponseEntity.ok(ApiResponse.success(null, "API key revoked successfully"));
    }

    @GetMapping("/usage")
    @Operation(summary = "Get API usage statistics")
    public ResponseEntity<ApiResponse<UsageStatsDto>> getUsage(
            @AuthenticationPrincipal Customer customer) {
        requireAuth(customer);
        log.info("Usage stats: customer={}, tier={}", customer.getEmail(), customer.getTier());
        UsageStatsDto stats = usageTrackingService.getUsageStats(customer);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @PostMapping("/subscription/checkout")
    @Operation(summary = "Create checkout session for subscription upgrade")
    public ResponseEntity<ApiResponse<Map<String, String>>> createCheckoutSession(
            @AuthenticationPrincipal Customer customer,
            @RequestParam Customer.SubscriptionTier tier) {

        requireAuth(customer);
        log.info("Checkout requested: customer={}, tier={}", customer.getEmail(), tier);
        String checkoutUrl = billingService.getCheckoutUrl(customer, tier);
        return ResponseEntity.ok(ApiResponse.success(Map.of("checkoutUrl", checkoutUrl)));
    }

    @PostMapping("/subscription/crypto-checkout")
    @Operation(summary = "Create crypto subscription for upgrade (payment link sent via email)")
    public ResponseEntity<ApiResponse<Map<String, String>>> createCryptoCheckoutSession(
            @AuthenticationPrincipal Customer customer,
            @RequestParam Customer.SubscriptionTier tier) {

        requireAuth(customer);
        log.info("Crypto subscription requested: customer={}, tier={}", customer.getEmail(), tier);
        String subscriptionId = nowPaymentsService.createSubscription(customer, tier);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("subscriptionId", subscriptionId,
                       "message", "A payment link has been sent to " + customer.getEmail() + ". Please check your inbox to complete the payment."),
                "Crypto subscription created. Check your email for the payment link."
        ));
    }

    @PostMapping("/activate-pro-trial")
    @Operation(summary = "Activate free 7-day Pro trial for eligible users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> activateProTrial(
            @AuthenticationPrincipal Customer customer) {

        requireAuth(customer);
        log.info("Pro trial activation requested: customer={}", customer.getEmail());
        Customer updated = proTrialService.activateProTrial(customer);
        Map<String, Object> response = Map.of(
                "tier", updated.getTier().name(),
                "trialExpiresAt", updated.getProTrialExpiresAt().toString()
        );
        return ResponseEntity.ok(ApiResponse.success(response, "Pro trial activated for 7 days"));
    }

    @PostMapping("/subscription/cancel")
    @Operation(summary = "Cancel current subscription at end of billing cycle")
    public ResponseEntity<ApiResponse<Map<String, String>>> cancelSubscription(
            @AuthenticationPrincipal Customer customer) {

        requireAuth(customer);
        log.info("Subscription cancellation requested: customer={}, tier={}", customer.getEmail(), customer.getTier());

        // Route to the correct payment provider based on which subscription ID is set
        if (customer.getCryptoSubscriptionId() != null && !customer.getCryptoSubscriptionId().isBlank()) {
            nowPaymentsService.cancelSubscription(customer);
        } else {
            billingService.cancelSubscription(customer);
        }

        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "Your subscription will be cancelled at the end of the current billing cycle."),
                "Subscription cancellation scheduled"
        ));
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change account password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal Customer customer,
            @RequestBody Map<String, String> request) {

        requireAuth(customer);
        String currentPassword = request.get("currentPassword");
        String newPassword = request.get("newPassword");
        authService.changePassword(customer, currentPassword, newPassword);
        return ResponseEntity.ok(ApiResponse.success(null, "Password changed successfully"));
    }

    private void requireAuth(Customer customer) {
        if (customer == null) {
            throw new UnauthorizedException("Authentication required");
        }
    }
}
