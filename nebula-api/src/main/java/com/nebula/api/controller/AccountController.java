package com.nebula.api.controller;

import com.nebula.api.service.ApiKeyService;
import com.nebula.api.service.BillingService;
import com.nebula.api.service.UsageTrackingService;
import com.nebula.common.dto.ApiKeyDto;
import com.nebula.common.dto.UsageStatsDto;
import com.nebula.common.dto.request.CreateApiKeyRequest;
import com.nebula.common.dto.response.ApiResponse;
import com.nebula.common.entity.Customer;
import com.nebula.common.util.ApiKeyGenerator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    private final ApiKeyService apiKeyService;
    private final UsageTrackingService usageTrackingService;
    private final BillingService billingService;

    @GetMapping("/api-keys")
    @Operation(summary = "List all API keys")
    public ResponseEntity<ApiResponse<List<ApiKeyDto>>> getApiKeys(
            @AuthenticationPrincipal Customer customer) {
        List<ApiKeyDto> keys = apiKeyService.getCustomerApiKeys(customer.getId());
        return ResponseEntity.ok(ApiResponse.success(keys));
    }

    @PostMapping("/api-keys")
    @Operation(summary = "Create a new API key")
    public ResponseEntity<ApiResponse<Map<String, String>>> createApiKey(
            @AuthenticationPrincipal Customer customer,
            @Valid @RequestBody CreateApiKeyRequest request) {
        
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
        
        apiKeyService.revokeApiKey(customer.getId(), keyId);
        return ResponseEntity.ok(ApiResponse.success(null, "API key revoked successfully"));
    }

    @GetMapping("/usage")
    @Operation(summary = "Get API usage statistics")
    public ResponseEntity<ApiResponse<UsageStatsDto>> getUsage(
            @AuthenticationPrincipal Customer customer) {
        UsageStatsDto stats = usageTrackingService.getUsageStats(customer);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/subscription")
    @Operation(summary = "Get subscription details")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSubscription(
            @AuthenticationPrincipal Customer customer) {
        Map<String, Object> details = billingService.getSubscriptionDetails(customer);
        return ResponseEntity.ok(ApiResponse.success(details));
    }

    @PostMapping("/subscription/checkout")
    @Operation(summary = "Create checkout session for subscription upgrade")
    public ResponseEntity<ApiResponse<Map<String, String>>> createCheckoutSession(
            @AuthenticationPrincipal Customer customer,
            @RequestParam Customer.SubscriptionTier tier) {
        
        String checkoutUrl = billingService.createCheckoutSession(customer, tier);
        return ResponseEntity.ok(ApiResponse.success(Map.of("checkoutUrl", checkoutUrl)));
    }
}
