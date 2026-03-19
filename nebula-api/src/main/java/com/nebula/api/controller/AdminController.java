package com.nebula.api.controller;

import com.nebula.api.repository.CustomerRepository;
import com.nebula.common.dto.response.ApiResponse;
import com.nebula.common.entity.Customer;
import com.nebula.common.entity.Customer.SubscriptionTier;
import com.nebula.common.exception.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Admin endpoints for managing customers")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private final CustomerRepository customerRepository;

    @PutMapping("/customers/{customerId}/tier")
    @Operation(summary = "Update customer subscription tier")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateCustomerTier(
            @PathVariable UUID customerId,
            @RequestParam SubscriptionTier tier) {
        
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId.toString()));
        
        SubscriptionTier previousTier = customer.getTier();
        customer.setTier(tier);
        customerRepository.save(customer);
        
        log.info("Updated customer {} tier from {} to {}", customerId, previousTier, tier);
        
        Map<String, Object> response = Map.of(
                "customerId", customerId,
                "email", customer.getEmail(),
                "previousTier", previousTier,
                "currentTier", tier
        );
        
        return ResponseEntity.ok(ApiResponse.success(response, "Customer tier updated successfully"));
    }

    @PutMapping("/customers/by-email/{email}/tier")
    @Operation(summary = "Update customer subscription tier by email")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateCustomerTierByEmail(
            @PathVariable String email,
            @RequestParam SubscriptionTier tier) {
        
        Customer customer = customerRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", email));
        
        SubscriptionTier previousTier = customer.getTier();
        customer.setTier(tier);
        customerRepository.save(customer);
        
        log.info("Updated customer {} tier from {} to {}", email, previousTier, tier);
        
        Map<String, Object> response = Map.of(
                "customerId", customer.getId(),
                "email", customer.getEmail(),
                "previousTier", previousTier,
                "currentTier", tier
        );
        
        return ResponseEntity.ok(ApiResponse.success(response, "Customer tier updated successfully"));
    }

    @GetMapping("/customers/{customerId}")
    @Operation(summary = "Get customer details")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCustomer(@PathVariable UUID customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId.toString()));
        
        return ResponseEntity.ok(ApiResponse.success(customerToMap(customer)));
    }

    @GetMapping("/customers/by-email/{email}")
    @Operation(summary = "Get customer details by email")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCustomerByEmail(@PathVariable String email) {
        Customer customer = customerRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", email));
        
        return ResponseEntity.ok(ApiResponse.success(customerToMap(customer)));
    }

    private Map<String, Object> customerToMap(Customer customer) {
        return Map.of(
                "id", customer.getId(),
                "email", customer.getEmail(),
                "companyName", customer.getCompanyName() != null ? customer.getCompanyName() : "",
                "tier", customer.getTier(),
                "isActive", customer.getIsActive(),
                "emailVerified", customer.getEmailVerified(),
                "createdAt", customer.getCreatedAt(),
                "updatedAt", customer.getUpdatedAt()
        );
    }
}
